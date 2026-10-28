package net.pendzialek.closeby

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.pendzialek.closeby.api.CallJoin
import net.pendzialek.closeby.api.CallParty
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.EndReasons
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.PartyKind
import net.pendzialek.closeby.api.StartCallRequest
import org.slf4j.LoggerFactory

class ApiException(val status: HttpStatusCode, message: String) : Exception(message)

/**
 * Call signaling. Calls live in memory only: after a restart there is nothing
 * worth resuming, the LiveKit rooms close on their own.
 */
class Calls(
    private val config: Config,
    private val store: Store,
    private val hub: Hub,
    private val push: Push,
    private val rooms: Rooms,
    private val scope: CoroutineScope,
) {
    private enum class State { RINGING, ACTIVE }

    private class Call(
        val id: String,
        val caller: Device,
        val from: CallParty,
        val to: CallParty,
        val callees: List<Device>,
        val autoAnswer: Boolean,
    ) {
        var state = State.RINGING
        var answeredBy: String? = null
        var answeredAt = 0L
        var timeout: Job? = null

        fun involves(deviceId: String) = caller.id == deviceId || callees.any { it.id == deviceId }
    }

    private val log = LoggerFactory.getLogger(Calls::class.java)
    private val calls = mutableMapOf<String, Call>()
    private val mutex = Mutex()

    /**
     * FCM messages go out one after another, off the request path: a slow FCM must not
     * hold up the caller's HTTP answer, and a phone must get call.incoming before call.taken.
     */
    private val pushes = Channel<Pair<String, Map<String, String>>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((token, data) in pushes) {
                try {
                    // one stalled send must not hold up the rings queued behind it
                    val result = withTimeoutOrNull(PUSH_TIMEOUT_MS) { push.send(token, data) }
                    if (result == PushResult.UNREGISTERED) store.clearFcmToken(token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("push failed: {}", e.message)
                }
            }
        }
        // a call whose clients vanished without hanging up would block both sides as "busy"
        scope.launch {
            while (true) {
                delay(STALE_CHECK_MS)
                reapStale { true }
            }
        }
    }

    /** Ends active calls whose LiveKit room is empty. */
    private suspend fun reapStale(filter: (Call) -> Boolean) {
        val now = System.currentTimeMillis()
        val candidates = mutex.withLock {
            calls.values.filter { it.state == State.ACTIVE && now - it.answeredAt >= config.staleCallGraceMs && filter(it) }
        }
        for (call in candidates) {
            if (rooms.participantCount(call.id) != 0) continue
            val removed = mutex.withLock { calls[call.id]?.let(::remove) } ?: continue
            notify(listOf(removed.caller) + removed.callees, ended(removed.id, EndReasons.HANGUP))
            rooms.close(removed.id)
        }
    }

    suspend fun start(device: Device, request: StartCallRequest): CallJoin {
        val call = when (device.kind) {
            DeviceKind.FRAME -> frameCallsPerson(device, request.personId ?: badRequest("personId missing"))
            else -> personCallsFrame(device, request.frameId ?: badRequest("frameId missing"))
        }
        if (call.callees.isEmpty()) throw ApiException(HttpStatusCode.Conflict, "unreachable")
        // before answering "busy", make sure the blocking call is still real
        val involved = call.callees.map { it.id }.toSet() + device.id
        reapStale { live -> involved.any(live::involves) }

        mutex.withLock {
            val busy = calls.values.any { live -> live.involves(device.id) || call.callees.any { live.involves(it.id) } }
            if (busy) throw ApiException(HttpStatusCode.Conflict, "busy")
            calls[call.id] = call
            call.timeout = scope.launch {
                delay(config.ringTimeoutSeconds * 1000)
                timeout(call.id)
            }
        }

        notify(
            call.callees,
            Event(EventTypes.CALL_INCOMING, callId = call.id, from = call.from, autoAnswer = call.autoAnswer),
        )
        return join(call, device, call.from.name)
    }

    suspend fun accept(device: Device, callId: String): CallJoin {
        val (call, others) = mutex.withLock {
            val call = calls[callId] ?: notFound()
            if (call.callees.none { it.id == device.id }) notFound()
            if (call.state != State.RINGING) throw ApiException(HttpStatusCode.Conflict, "already answered")
            call.state = State.ACTIVE
            call.answeredBy = device.id
            call.answeredAt = System.currentTimeMillis()
            call.timeout?.cancel()
            call to call.callees.filter { it.id != device.id }
        }
        hub.send(call.caller.id, Event(EventTypes.CALL_ACCEPTED, callId = callId))
        notify(others, Event(EventTypes.CALL_TAKEN, callId = callId))
        return join(call, device, call.to.name)
    }

    suspend fun decline(device: Device, callId: String) {
        val call = mutex.withLock {
            val call = calls[callId] ?: notFound()
            if (call.callees.none { it.id == device.id }) notFound()
            if (call.state != State.RINGING) throw ApiException(HttpStatusCode.Conflict, "already answered")
            remove(call)
        }
        hub.send(call.caller.id, Event(EventTypes.CALL_DECLINED, callId = callId))
        notify(call.callees.filter { it.id != device.id }, ended(callId, EndReasons.DECLINED))
        rooms.close(callId)
    }

    /** Hang up, or cancel while it is still ringing. */
    suspend fun end(device: Device, callId: String) {
        val (call, reason) = mutex.withLock {
            val call = calls[callId] ?: notFound()
            val allowed = device.id == call.caller.id || device.id == call.answeredBy
            if (!allowed) notFound()
            val reason = if (call.state == State.RINGING) EndReasons.CANCELLED else EndReasons.HANGUP
            remove(call) to reason
        }
        val everyone = listOf(call.caller) + call.callees
        notify(everyone.filter { it.id != device.id }, ended(callId, reason))
        rooms.close(callId)
    }

    private suspend fun timeout(callId: String) {
        val call = mutex.withLock {
            val call = calls[callId]?.takeIf { it.state == State.RINGING } ?: return
            // this coroutine is call.timeout: cancelling it would cancel the notifications below
            call.timeout = null
            remove(call)
        }
        notify(listOf(call.caller) + call.callees, ended(callId, EndReasons.MISSED))
        rooms.close(callId)
    }

    private suspend fun frameCallsPerson(frameDevice: Device, personId: String): Call {
        val frame = store.frame(frameDevice.id) ?: notFound()
        val contact = store.frameContacts(frame.deviceId).find { it.person.id == personId } ?: notFound()
        return Call(
            id = Secrets.newId(),
            caller = frameDevice,
            from = CallParty(PartyKind.FRAME, frame.deviceId, frame.name, frame.avatarVersion),
            to = CallParty(PartyKind.PERSON, personId, contact.person.shownName, contact.person.avatarVersion),
            callees = store.devicesOfPerson(personId).filter { it.kind != DeviceKind.FRAME },
            autoAnswer = false,
        )
    }

    private suspend fun personCallsFrame(device: Device, frameId: String): Call {
        val personId = device.personId ?: notFound()
        val frame = store.frame(frameId) ?: notFound()
        val contact = store.frameContacts(frameId).find { it.person.id == personId } ?: notFound()
        val frameDevice = store.device(frameId) ?: notFound()
        return Call(
            id = Secrets.newId(),
            caller = device,
            from = CallParty(PartyKind.PERSON, personId, contact.person.shownName, contact.person.avatarVersion),
            to = CallParty(PartyKind.FRAME, frame.deviceId, frame.name, frame.avatarVersion),
            callees = listOf(frameDevice),
            autoAnswer = contact.autoAnswer,
        )
    }

    private fun remove(call: Call): Call {
        call.timeout?.cancel()
        calls.remove(call.id)
        return call
    }

    private fun join(call: Call, device: Device, name: String) =
        CallJoin(call.id, config.livekitPublicUrl, rooms.joinToken(call.id, device.id, name))

    private fun ended(callId: String, reason: String) = Event(EventTypes.CALL_ENDED, callId = callId, reason = reason)

    /** Websocket for everything that is connected, FCM on top for phones (the app dedups by callId). */
    private suspend fun notify(devices: List<Device>, event: Event) {
        for (device in devices) {
            hub.send(device.id, event)
            val token = device.fcmToken ?: continue
            if (device.kind != DeviceKind.PHONE) continue
            pushes.trySend(token to event.toPushData())
        }
    }

    private fun Event.toPushData() = buildMap {
        put("type", type)
        callId?.let { put("callId", it) }
        from?.let {
            put("fromKind", it.kind.name.lowercase())
            put("fromId", it.id)
            put("fromName", it.name)
            it.avatarVersion?.let { version -> put("fromAvatarVersion", version.toString()) }
        }
        autoAnswer?.let { put("autoAnswer", it.toString()) }
        reason?.let { put("reason", it) }
    }
}

private const val STALE_CHECK_MS = 30_000L
private const val PUSH_TIMEOUT_MS = 10_000L

fun notFound(): Nothing = throw ApiException(HttpStatusCode.NotFound, "not found")
fun badRequest(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, message)
fun forbidden(): Nothing = throw ApiException(HttpStatusCode.Forbidden, "forbidden")
