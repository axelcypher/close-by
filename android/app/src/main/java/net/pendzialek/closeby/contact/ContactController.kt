package net.pendzialek.closeby.contact

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.CallParty
import net.pendzialek.closeby.api.EndReasons
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameRef
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.PartyKind
import net.pendzialek.closeby.call.CallController
import net.pendzialek.closeby.call.CallUi
import net.pendzialek.closeby.data.ApiException
import kotlin.coroutines.resume

data class ContactState(
    val me: Me? = null,
    val frames: List<FrameRef> = emptyList(),
    val call: CallUi = CallUi.None,
    val connected: Boolean = false,
    /** Bumped on presence and config events, so the admin screens reload. */
    val revision: Int = 0,
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
        if (visible) scope.launch { refresh() }
    }

    suspend fun refresh() {
        try {
            val me = api.me()
            val frames = api.frames()
            _state.update { it.copy(me = me, frames = frames) }
        } catch (e: ApiException) {
            if (e.status == 401) app.signOut()
        } catch (_: Exception) {
            // offline
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
            )
        }
        onEvent(Event(type, callId = data["callId"], from = from, reason = data["reason"]))
    }

    // ---- calls ----

    fun call(frame: FrameRef) {
        if (_state.value.call != CallUi.None) return
        _state.update { it.copy(call = CallUi.Outgoing(frame.name)) }
        updateRealtime()
        scope.launch {
            try {
                val join = api.callFrame(frame.id)
                if (_state.value.call !is CallUi.Outgoing) {
                    runCatching { api.endCall(join.callId) }
                    return@launch
                }
                _state.update { it.copy(call = (it.call as CallUi.Outgoing).copy(callId = join.callId)) }
                OngoingCallService.start(app, frame.name)
                callSession.join(join, null)
            } catch (e: ApiException) {
                finish(frame.name, if (e.status == 409) "ist gerade nicht erreichbar" else "Anruf hat nicht geklappt")
            } catch (_: Exception) {
                finish(frame.name, "Keine Verbindung zum Internet")
            }
        }
    }

    override fun accept() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        CallNotifications.clearIncoming(app)
        _state.update { it.copy(call = CallUi.Active(incoming.callId, incoming.name)) }
        updateRealtime()
        scope.launch {
            try {
                val join = api.acceptCall(incoming.callId)
                OngoingCallService.start(app, incoming.name)
                callSession.join(join, null)
            } catch (_: Exception) {
                finish(incoming.name, "Anruf hat nicht geklappt")
            }
        }
    }

    override fun decline() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        CallNotifications.clearIncoming(app)
        _state.update { it.copy(call = CallUi.None) }
        updateRealtime()
        scope.launch { runCatching { api.declineCall(incoming.callId) } }
    }

    override fun hangUp() {
        val call = _state.value.call
        val (callId, name) = when (call) {
            is CallUi.Outgoing -> call.callId to call.name
            is CallUi.Active -> call.callId to call.name
            is CallUi.Incoming -> call.callId to call.name
            else -> null to ""
        }
        finish(name, "Gespräch beendet")
        if (callId != null) scope.launch { runCatching { api.endCall(callId) } }
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
                if (call is CallUi.Incoming && call.callId == callId) return
                if (call != CallUi.None && call !is CallUi.Ended) return
                _state.update { it.copy(call = CallUi.Incoming(callId, from.name)) }
                CallNotifications.showIncoming(app, from.name)
                updateRealtime()
            }
            EventTypes.CALL_ACCEPTED -> if (call is CallUi.Outgoing && call.callId == event.callId) {
                _state.update { it.copy(call = CallUi.Active(event.callId!!, call.name)) }
            }
            EventTypes.CALL_DECLINED -> if (call is CallUi.Outgoing && call.callId == event.callId) {
                finish(call.name, "hat gerade keine Zeit")
            }
            EventTypes.CALL_TAKEN -> if (call is CallUi.Incoming && call.callId == event.callId) {
                // answered on another device of ours
                CallNotifications.clearIncoming(app)
                _state.update { it.copy(call = CallUi.None) }
                updateRealtime()
            }
            EventTypes.CALL_ENDED -> {
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
}
