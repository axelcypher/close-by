package net.pendzialek.closeby.contact

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.AppRelease
import net.pendzialek.closeby.api.CallParty
import net.pendzialek.closeby.api.EndReasons
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameRef
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.PartyKind
import net.pendzialek.closeby.call.CallController
import net.pendzialek.closeby.call.CallUi
import net.pendzialek.closeby.call.MicrophoneNotAllowed
import net.pendzialek.closeby.data.ApiException
import net.pendzialek.closeby.data.Images
import kotlin.coroutines.resume

data class ContactState(
    val me: Me? = null,
    val frames: List<FrameRef> = emptyList(),
    val call: CallUi = CallUi.None,
    val connected: Boolean = false,
    /** Bumped on presence and config events, so the admin screens reload. */
    val revision: Int = 0,
    /** A newer app version, offered on the contact screen; [net.pendzialek.closeby.device.UpdateJob] installs it silently where it can. */
    val update: AppRelease? = null,
    val updating: Boolean = false,
)

/**
 * The phone of a relative. The websocket runs only while the app is visible or
 * a call is going on; in the background calls arrive via FCM ([PushService]).
 */
class ContactController(private val app: CloseByApp) : CallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = app.api
    override val images = app.images
    override val callSession = app.callSession

    private val _state = MutableStateFlow(ContactState())
    val state: StateFlow<ContactState> = _state

    private var uiVisible = false

    fun start() {
        callSession.onPeerLeft = { if (_state.value.call is CallUi.Active) hangUp() }
        callSession.onPeerJoined = {
            val call = _state.value.call
            if (call is CallUi.Outgoing && call.callId != null) answered(call.callId, call.name)
        }
        callSession.onDisconnected = { if (currentCallId() != null) end("Verbindung unterbrochen") }
        scope.launch { app.realtime.events.collect(::onEvent) }
        scope.launch {
            app.realtime.connected.collect { connected ->
                _state.update { it.copy(connected = connected) }
                if (connected) refresh()
            }
        }
        scope.launch { refresh() }
        scope.launch { registerPushToken() }
    }

    fun stop() {
        CallNotifications.clearIncoming(app)
        OngoingCallService.stop(app)
        callSession.leave()
        scope.cancel()
    }

    fun onUiVisible(visible: Boolean) {
        uiVisible = visible
        updateRealtime()
        if (visible) {
            scope.launch { refresh() }
            checkForUpdate()
        }
    }

    suspend fun refresh() {
        try {
            val me = api.me()
            val frames = api.frames()
            _state.update { it.copy(me = me, frames = frames) }
        } catch (e: ApiException) {
            if (e.status == 401) app.checkRevoked()
        } catch (_: Exception) {
            // offline
        }
    }

    // ---- app updates ----

    private var lastUpdateCheck = 0L

    private fun checkForUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheck < UPDATE_CHECK_MS) return
        lastUpdateCheck = now
        scope.launch {
            val release = app.updater.newerRelease()
            _state.update { it.copy(update = release) }
        }
    }

    /** "Jetzt aktualisieren": the app is replaced right away (not during a call), Android may ask first. */
    fun installUpdate() {
        val release = _state.value.update ?: return
        if (_state.value.updating) return
        _state.update { it.copy(updating = true) }
        scope.launch {
            Log.i("CloseBy", "update: ${app.updater.install(release) { !app.inCall() }}")
            // still here: the download failed or Android waits for a confirmation
            _state.update { it.copy(updating = false) }
        }
    }

    // ---- push ----

    fun onPushToken(token: String) {
        scope.launch { runCatching { api.setPushToken(token) } }
    }

    private suspend fun registerPushToken() {
        val token = suspendCancellableCoroutine<String?> { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener {
                    Log.w("CloseBy", "no FCM token: ${it.message}")
                    continuation.resume(null)
                }
        } ?: return
        runCatching { api.setPushToken(token) }
    }

    /** FCM data messages carry the event fields as strings. */
    fun onPush(data: Map<String, String>) {
        val type = data["type"] ?: return
        val from = data["fromName"]?.let { name ->
            CallParty(
                kind = PartyKind.FRAME,
                id = data["fromId"].orEmpty(),
                name = name,
                avatarVersion = data["fromAvatarVersion"]?.toLongOrNull(),
            )
        }
        onEvent(Event(type, callId = data["callId"], from = from, reason = data["reason"]))
    }

    // ---- calls ----

    fun call(frame: FrameRef) {
        if (_state.value.call != CallUi.None) return
        _state.update { it.copy(call = CallUi.Outgoing(frame.name, Images.framePicture(frame.id, frame.avatarVersion))) }
        updateRealtime()
        scope.launch {
            var callId: String? = null
            try {
                val join = api.callFrame(frame.id)
                callId = join.callId
                if (_state.value.call !is CallUi.Outgoing) {
                    runCatching { api.endCall(join.callId) }
                    return@launch
                }
                _state.update { it.copy(call = (it.call as CallUi.Outgoing).copy(callId = join.callId)) }
                armRingTimeout(join.callId)
                OngoingCallService.start(app, frame.name)
                callSession.join(join, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // over already (hung up, declined, timed out): whoever ended it cleaned up
                val stillOurs = if (callId == null) _state.value.call is CallUi.Outgoing else currentCallId() == callId
                if (!stillOurs) return@launch
                // the frame would ring on, or sit in an empty room after an auto-answer
                callId?.let { id -> scope.launch { runCatching { api.endCall(id) } } }
                finish(frame.name, failure(e))
            }
        }
    }

    override fun accept() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        CallNotifications.clearIncoming(app)
        ringTimeout?.cancel()
        _state.update { it.copy(call = CallUi.Active(incoming.callId, incoming.name)) }
        updateRealtime()
        scope.launch {
            try {
                val join = api.acceptCall(incoming.callId)
                if (currentCallId() != incoming.callId) {
                    // hung up meanwhile; that hang-up may have reached the server before the accept
                    runCatching { api.endCall(incoming.callId) }
                    return@launch
                }
                OngoingCallService.start(app, incoming.name)
                callSession.join(join, null, peerExpected = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (currentCallId() != incoming.callId) return@launch
                scope.launch { runCatching { api.endCall(incoming.callId) } }
                finish(incoming.name, failure(e))
            }
        }
    }

    override fun decline() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        CallNotifications.clearIncoming(app)
        ringTimeout?.cancel()
        remember(incoming.callId)
        _state.update { it.copy(call = CallUi.None) }
        updateRealtime()
        scope.launch { runCatching { api.declineCall(incoming.callId) } }
    }

    override fun hangUp() = end("Gespräch beendet")

    /** Ends the call here and on the server, showing [message]. */
    private fun end(message: String) {
        val call = _state.value.call
        val callId = currentCallId()
        val name = when (call) {
            is CallUi.Outgoing -> call.name
            is CallUi.Active -> call.name
            is CallUi.Incoming -> call.name
            else -> ""
        }
        finish(name, message)
        if (callId != null) scope.launch { runCatching { api.endCall(callId) } }
    }

    private fun currentCallId(): String? = when (val call = _state.value.call) {
        is CallUi.Outgoing -> call.callId
        is CallUi.Active -> call.callId
        is CallUi.Incoming -> call.callId
        else -> null
    }

    private fun failure(e: Exception) = when {
        e is MicrophoneNotAllowed -> "Mikrofon ist nicht erlaubt"
        e is ApiException && e.status == 409 -> "ist gerade nicht erreichbar"
        e is ApiException -> "Anruf hat nicht geklappt"
        else -> "Keine Verbindung zum Internet"
    }

    /** The frame took the call (call.accepted, or it showed up in the room). */
    private fun answered(callId: String, name: String) {
        ringTimeout?.cancel()
        _state.update { it.copy(call = CallUi.Active(callId, name)) }
        callSession.expectPeer()
    }

    private var ringTimeout: Job? = null

    /**
     * In case call.ended never arrives (phone offline when the ring timed out, FCM dropped it),
     * ringing stops on its own instead of blocking every later call as "busy".
     */
    private fun armRingTimeout(callId: String) {
        ringTimeout?.cancel()
        ringTimeout = scope.launch {
            delay(RING_TIMEOUT_MS)
            val call = _state.value.call
            if ((call is CallUi.Incoming || call is CallUi.Outgoing) && currentCallId() == callId) end("nicht erreicht")
        }
    }

    /**
     * Calls that are over here. The ring comes twice (websocket and FCM), and a copy that
     * arrives after the call was declined or finished must not ring again.
     */
    private val handled = mutableListOf<String>()

    private fun remember(callId: String) {
        if (callId in handled) return
        handled += callId
        if (handled.size > 20) handled.removeAt(0)
    }

    private fun onEvent(event: Event) {
        val call = _state.value.call
        when (event.type) {
            EventTypes.PRESENCE_CHANGED, EventTypes.CONFIG_CHANGED -> {
                _state.update { it.copy(revision = it.revision + 1) }
                scope.launch { refresh() }
            }
            EventTypes.CALL_INCOMING -> {
                val from = event.from ?: return
                val callId = event.callId ?: return
                // websocket and FCM both deliver the ring
                if (callId in handled || (call is CallUi.Incoming && call.callId == callId)) return
                if (call != CallUi.None && call !is CallUi.Ended) return
                _state.update { it.copy(call = CallUi.Incoming(callId, from.name, Images.framePicture(from.id, from.avatarVersion))) }
                armRingTimeout(callId)
                CallNotifications.showIncoming(app, from.name)
                updateRealtime()
            }
            EventTypes.CALL_ACCEPTED -> if (call is CallUi.Outgoing && call.callId != null && call.callId == event.callId) {
                answered(call.callId, call.name)
            }
            EventTypes.CALL_DECLINED -> if (call is CallUi.Outgoing && call.callId == event.callId) {
                finish(call.name, "hat gerade keine Zeit")
            }
            EventTypes.CALL_TAKEN -> {
                // also when its ring is still on the way: it must not start once it arrives
                event.callId?.let(::remember)
                if (call is CallUi.Incoming && call.callId == event.callId) {
                    // answered on another device of ours
                    CallNotifications.clearIncoming(app)
                    ringTimeout?.cancel()
                    _state.update { it.copy(call = CallUi.None) }
                    updateRealtime()
                }
            }
            EventTypes.CALL_ENDED -> {
                event.callId?.let(::remember)
                val (callId, name) = when (call) {
                    is CallUi.Outgoing -> call.callId to call.name
                    is CallUi.Incoming -> call.callId to call.name
                    is CallUi.Active -> call.callId to call.name
                    else -> return
                }
                if (callId != event.callId) return
                finish(name, if (event.reason == EndReasons.MISSED) "nicht erreicht" else "Gespräch beendet")
            }
        }
    }

    private fun finish(name: String, message: String) {
        currentCallId()?.let(::remember)
        ringTimeout?.cancel()
        CallNotifications.clearIncoming(app)
        OngoingCallService.stop(app)
        callSession.leave()
        _state.update { it.copy(call = CallUi.Ended(name, message)) }
        scope.launch {
            delay(3_000)
            if (_state.value.call is CallUi.Ended) _state.update { it.copy(call = CallUi.None) }
            updateRealtime()
        }
    }

    /** Websocket only while someone looks at the app or a call needs its events. */
    private fun updateRealtime() {
        if (uiVisible || _state.value.call != CallUi.None) app.realtime.start() else app.realtime.stop()
    }

    private companion object {
        const val UPDATE_CHECK_MS = 30 * 60_000L
        /** A bit longer than the server's ring timeout (CALL_RING_TIMEOUT_SECONDS, 45 s). */
        const val RING_TIMEOUT_MS = 60_000L
    }
}
