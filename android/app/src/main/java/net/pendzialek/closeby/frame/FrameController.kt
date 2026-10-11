package net.pendzialek.closeby.frame

import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pendzialek.closeby.BuildConfig
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.DeviceStatus
import net.pendzialek.closeby.api.EndReasons
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameConfig
import net.pendzialek.closeby.api.FrameContact
import net.pendzialek.closeby.api.SlideshowPhoto
import net.pendzialek.closeby.api.StatusReport
import net.pendzialek.closeby.data.ApiException
import net.pendzialek.closeby.device.DeviceOwner
import java.time.LocalTime

enum class FrameView { CONTACTS, SLIDESHOW, NIGHT }

sealed interface CallUi {
    data object None : CallUi
    data class Outgoing(val contact: FrameContact, val callId: String? = null) : CallUi
    data class Incoming(val callId: String, val name: String, val personId: String, val autoAnswer: Boolean) : CallUi
    data class Active(val callId: String, val name: String) : CallUi
    data class Ended(val name: String, val message: String) : CallUi
}

data class FrameState(
    val config: FrameConfig? = null,
    val photos: List<SlideshowPhoto> = emptyList(),
    val view: FrameView = FrameView.CONTACTS,
    val call: CallUi = CallUi.None,
    val connected: Boolean = false,
)

/**
 * Everything a frame does, independent of whether its screen is visible:
 * lives as long as the app is paired as frame, kept alive by [FrameService].
 */
class FrameController(private val app: CloseByApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = app.api
    val images = app.images
    val callSession = app.callSession

    private val _state = MutableStateFlow(FrameState())
    val state: StateFlow<FrameState> = _state

    private var lastTouch = System.currentTimeMillis()
    private var ringtone: Ringtone? = null

    /** Shows the incoming call when the activity is in the background. */
    var onIncomingWhileHidden: (name: String) -> Unit = {}

    /** Admin commands from the web client, handled by the activity (kiosk) and [Updater]. */
    var onCommand: (String) -> Unit = {}

    fun start() {
        app.realtime.start()
        callSession.onPeerLeft = { if (_state.value.call is CallUi.Active) hangUp() }
        scope.launch { app.realtime.events.collect(::onEvent) }
        scope.launch {
            app.realtime.connected.collect { connected ->
                _state.update { it.copy(connected = connected) }
                // whatever changed while offline was not announced to us
                if (connected) refresh()
            }
        }
        scope.launch {
            while (true) {
                tick()
                delay(1_000)
            }
        }
        scope.launch {
            while (true) {
                reportStatus()
                delay(5 * 60_000)
            }
        }
        scope.launch {
            // new photos in the album show up without a config change
            while (true) {
                delay(30 * 60_000)
                loadSlideshow()
            }
        }
    }

    fun touched() {
        lastTouch = System.currentTimeMillis()
        if (_state.value.view != FrameView.CONTACTS) _state.update { it.copy(view = FrameView.CONTACTS) }
    }

    // ---- loading ----

    private suspend fun refresh() {
        try {
            val config = api.frameConfig()
            app.prefs.forcePublicDns = config.settings.forcePublicDns
            _state.update { it.copy(config = config) }
            loadSlideshow()
        } catch (e: ApiException) {
            if (e.status == 401) app.signOut()
        } catch (_: Exception) {
            // offline; the websocket reconnect triggers the next attempt
        }
    }

    private suspend fun loadSlideshow() {
        runCatching { api.slideshow() }.onSuccess { slideshow ->
            _state.update { it.copy(photos = slideshow.photos.shuffled()) }
        }
    }

    private suspend fun reportStatus() {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = DeviceStatus(
            battery = if (level >= 0) level * 100 / scale else null,
            charging = plugged,
            deviceOwner = DeviceOwner.isActive(app),
            kiosk = app.kioskActive,
        )
        runCatching { api.reportStatus(StatusReport(BuildConfig.VERSION_CODE, status)) }
    }

    /** Every second: slideshow after inactivity, night mode by the clock. */
    private fun tick() {
        val state = _state.value
        val settings = state.config?.settings ?: return
        if (state.call != CallUi.None) return
        val idle = System.currentTimeMillis() - lastTouch > settings.idleTimeoutSeconds * 1000L
        val view = when {
            !idle -> FrameView.CONTACTS
            isNight(settings.nightStart, settings.nightEnd) -> FrameView.NIGHT
            state.photos.isNotEmpty() -> FrameView.SLIDESHOW
            else -> FrameView.CONTACTS
        }
        if (view != state.view) _state.update { it.copy(view = view) }
    }

    private fun isNight(start: String?, end: String?): Boolean {
        if (start == null || end == null) return false
        val from = runCatching { LocalTime.parse(start) }.getOrNull() ?: return false
        val to = runCatching { LocalTime.parse(end) }.getOrNull() ?: return false
        val now = LocalTime.now()
        return if (from <= to) now in from..to else now >= from || now <= to
    }

    // ---- calls ----

    fun call(contact: FrameContact) {
        if (_state.value.call != CallUi.None) return
        touched()
        _state.update { it.copy(call = CallUi.Outgoing(contact)) }
        scope.launch {
            try {
                val join = api.startCall(contact.personId)
                if (_state.value.call !is CallUi.Outgoing) {
                    // cancelled while the request was running
                    runCatching { api.endCall(join.callId) }
                    return@launch
                }
                _state.update { it.copy(call = CallUi.Outgoing(contact, join.callId)) }
                callSession.join(join, _state.value.config?.settings?.volume)
            } catch (e: ApiException) {
                finish(contact.name, if (e.status == 409) "ist gerade nicht erreichbar" else "Anruf hat nicht geklappt")
            } catch (_: Exception) {
                finish(contact.name, "Keine Verbindung zum Internet")
            }
        }
    }

    fun accept() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        stopRinging()
        _state.update { it.copy(call = CallUi.Active(incoming.callId, incoming.name)) }
        scope.launch {
            try {
                callSession.join(api.acceptCall(incoming.callId), _state.value.config?.settings?.volume)
            } catch (_: Exception) {
                finish(incoming.name, "Anruf hat nicht geklappt")
            }
        }
    }

    fun decline() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        stopRinging()
        _state.update { it.copy(call = CallUi.None) }
        scope.launch { runCatching { api.declineCall(incoming.callId) } }
    }

    fun hangUp() {
        val call = _state.value.call
        val callId = when (call) {
            is CallUi.Outgoing -> call.callId
            is CallUi.Active -> call.callId
            is CallUi.Incoming -> call.callId
            else -> null
        }
        val name = when (call) {
            is CallUi.Outgoing -> call.contact.name
            is CallUi.Active -> call.name
            is CallUi.Incoming -> call.name
            else -> ""
        }
        finish(name, "Gespräch beendet")
        if (callId != null) scope.launch { runCatching { api.endCall(callId) } }
    }

    private fun onEvent(event: Event) {
        val call = _state.value.call
        when (event.type) {
            EventTypes.CONFIG_CHANGED -> scope.launch { refresh() }
            EventTypes.FRAME_COMMAND -> event.command?.let(onCommand)
            EventTypes.CALL_INCOMING -> {
                val from = event.from ?: return
                if (call != CallUi.None && call !is CallUi.Ended) return // busy; the backend times it out
                touched()
                if (!app.uiVisible) onIncomingWhileHidden(from.name)
                val auto = event.autoAnswer == true
                _state.update { it.copy(call = CallUi.Incoming(event.callId!!, from.name, from.id, auto)) }
                if (auto) {
                    scope.launch {
                        delay(3_000)
                        if ((_state.value.call as? CallUi.Incoming)?.callId == event.callId) accept()
                    }
                } else {
                    startRinging()
                }
            }
            EventTypes.CALL_ACCEPTED -> if (call is CallUi.Outgoing && call.callId == event.callId) {
                _state.update { it.copy(call = CallUi.Active(event.callId!!, call.contact.name)) }
            }
            EventTypes.CALL_DECLINED -> if (call is CallUi.Outgoing && call.callId == event.callId) {
                finish(call.contact.name, "hat gerade keine Zeit")
            }
            EventTypes.CALL_ENDED -> {
                val (callId, name) = when (call) {
                    is CallUi.Outgoing -> call.callId to call.contact.name
                    is CallUi.Incoming -> call.callId to call.name
                    is CallUi.Active -> call.callId to call.name
                    else -> return
                }
                if (callId != event.callId) return
                val message = when (event.reason) {
                    EndReasons.MISSED -> "ist gerade nicht erreichbar"
                    else -> "Gespräch beendet"
                }
                finish(name, message)
            }
        }
    }

    private fun finish(name: String, message: String) {
        stopRinging()
        callSession.leave()
        _state.update { it.copy(call = CallUi.Ended(name, message)) }
        scope.launch {
            delay(4_000)
            if (_state.value.call is CallUi.Ended) {
                _state.update { it.copy(call = CallUi.None) }
                touched()
            }
        }
    }

    private fun startRinging() {
        stopRinging()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        FrameService.clearIncoming(app)
    }

    fun stop() {
        stopRinging()
        callSession.leave()
        scope.cancel()
    }
}
