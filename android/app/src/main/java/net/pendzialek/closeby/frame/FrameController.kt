package net.pendzialek.closeby.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
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
import net.pendzialek.closeby.call.CallController
import net.pendzialek.closeby.call.CallUi
import net.pendzialek.closeby.api.EndReasons
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameConfig
import net.pendzialek.closeby.api.FrameContact
import net.pendzialek.closeby.api.SlideshowPhoto
import net.pendzialek.closeby.api.StatusReport
import net.pendzialek.closeby.data.ApiException
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.device.DeviceOwner
import java.time.LocalTime

enum class FrameView { CONTACTS, SLIDESHOW, NIGHT }

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
class FrameController(private val app: CloseByApp) : CallController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = app.api
    override val images = app.images
    override val callSession = app.callSession

    private val _state = MutableStateFlow(FrameState())
    val state: StateFlow<FrameState> = _state

    private var lastTouch = System.currentTimeMillis()
    /** Slideshow started by hand: shown even during night hours, until the next touch. */
    private var manualSlideshow = false
    private var ringtone: Ringtone? = null

    /** Shows the incoming call when the activity is in the background. */
    var onIncomingWhileHidden: (name: String) -> Unit = {}

    /** Admin commands from the web client, handled by the activity (kiosk) and [Updater]. */
    var onCommand: (String) -> Unit = {}

    fun start() {
        ContextCompat.registerReceiver(app, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
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
            // new photos show up without a config change; an empty or failed
            // slideshow (e.g. Immich was down) is retried every minute
            while (true) {
                delay(if (slideshowOk && _state.value.photos.isNotEmpty()) 30 * 60_000L else 60_000L)
                loadSlideshow()
            }
        }
    }

    fun touched() {
        lastTouch = System.currentTimeMillis()
        manualSlideshow = false
        if (_state.value.view != FrameView.CONTACTS) _state.update { it.copy(view = FrameView.CONTACTS) }
    }

    /** The "show photos" button: behaves like the idle timeout had just passed. */
    fun showSlideshow() {
        if (_state.value.photos.isEmpty()) return
        lastTouch = 0
        manualSlideshow = true
        tick()
    }

    /**
     * A frame on the wall must not go dark because somebody brushed the power
     * button: while charging and outside the night hours the screen comes back
     * on right away. On battery it stays off, so the tablet is not drained.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val settings = _state.value.config?.settings
            if (!isCharging() || (settings != null && isNight(settings.nightStart, settings.nightEnd))) return
            scope.launch {
                // the power manager ignores a wake-up that lands while it is still going to sleep
                delay(1_000)
                wakeScreen()
            }
        }
    }

    private fun wakeScreen() {
        val power = app.getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "closeby:screen",
        )
        // the activity's keep-screen-on flag takes over once the screen is on
        lock.acquire(5_000)
        touched()
    }

    private fun battery(): Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun isCharging(): Boolean = (battery()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

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

    private var slideshowOk = false

    private suspend fun loadSlideshow() {
        runCatching { api.slideshow() }
            .onSuccess { slideshow ->
                slideshowOk = true
                _state.update { it.copy(photos = slideshow.photos.shuffled()) }
            }
            .onFailure { slideshowOk = false }
    }

    private suspend fun reportStatus() {
        val battery = battery()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = DeviceStatus(
            battery = if (level >= 0) level * 100 / scale else null,
            charging = isCharging(),
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
            manualSlideshow && state.photos.isNotEmpty() -> FrameView.SLIDESHOW
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
        _state.update { it.copy(call = CallUi.Outgoing(contact.name, Images.personPicture(contact.personId, contact.avatarVersion))) }
        scope.launch {
            try {
                val join = api.startCall(contact.personId)
                if (_state.value.call !is CallUi.Outgoing) {
                    // cancelled while the request was running
                    runCatching { api.endCall(join.callId) }
                    return@launch
                }
                _state.update { it.copy(call = (it.call as CallUi.Outgoing).copy(callId = join.callId)) }
                callSession.join(join, _state.value.config?.settings?.volume)
            } catch (e: ApiException) {
                finish(contact.name, if (e.status == 409) "ist gerade nicht erreichbar" else "Anruf hat nicht geklappt")
            } catch (_: Exception) {
                finish(contact.name, "Keine Verbindung zum Internet")
            }
        }
    }

    override fun accept() {
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

    override fun decline() {
        val incoming = _state.value.call as? CallUi.Incoming ?: return
        stopRinging()
        _state.update { it.copy(call = CallUi.None) }
        scope.launch { runCatching { api.declineCall(incoming.callId) } }
    }

    override fun hangUp() {
        val call = _state.value.call
        val callId = when (call) {
            is CallUi.Outgoing -> call.callId
            is CallUi.Active -> call.callId
            is CallUi.Incoming -> call.callId
            else -> null
        }
        val name = when (call) {
            is CallUi.Outgoing -> call.name
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
                val version = _state.value.config?.contacts?.find { it.personId == from.id }?.avatarVersion
                _state.update { it.copy(call = CallUi.Incoming(event.callId!!, from.name, Images.personPicture(from.id, version), auto)) }
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
                _state.update { it.copy(call = CallUi.Active(event.callId!!, call.name)) }
            }
            EventTypes.CALL_DECLINED -> if (call is CallUi.Outgoing && call.callId == event.callId) {
                finish(call.name, "hat gerade keine Zeit")
            }
            EventTypes.CALL_ENDED -> {
                val (callId, name) = when (call) {
                    is CallUi.Outgoing -> call.callId to call.name
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
        runCatching { app.unregisterReceiver(screenOff) }
        stopRinging()
        callSession.leave()
        scope.cancel()
    }
}
