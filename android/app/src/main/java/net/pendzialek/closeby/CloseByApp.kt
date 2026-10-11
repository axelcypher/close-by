package net.pendzialek.closeby

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.pendzialek.closeby.api.FrameCommands
import net.pendzialek.closeby.call.CallSession
import net.pendzialek.closeby.contact.ContactController
import net.pendzialek.closeby.data.Api
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.data.Prefs
import net.pendzialek.closeby.data.Realtime
import net.pendzialek.closeby.device.DeviceOwner
import net.pendzialek.closeby.device.Updater
import net.pendzialek.closeby.frame.FrameController
import net.pendzialek.closeby.frame.FrameService

/** Process-wide objects. The frame lives as long as the process, so do its connections. */
class CloseByApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set
    lateinit var realtime: Realtime
        private set
    lateinit var images: Images
        private set
    lateinit var callSession: CallSession
        private set
    lateinit var updater: Updater
        private set

    /** Null unless paired as frame. */
    var frameController: FrameController? = null
        private set

    /** Null unless paired as a relative's phone. */
    var contactController: ContactController? = null
        private set

    private val _pendingInvite = MutableStateFlow<String?>(null)
    /** Invite code from an opened invite link, consumed by the setup screen. */
    val pendingInvite: StateFlow<String?> = _pendingInvite

    fun openInvite(code: String?) {
        _pendingInvite.value = code
    }
    private var updateJob: Job? = null

    /** Set by the activity; incoming calls need a notification while false. */
    @Volatile
    var uiVisible = false

    /** Set by the activity: CloseBy is locked as kiosk right now. */
    @Volatile
    var kioskActive = false

    private val _maintenanceUntil = MutableStateFlow(0L)
    /** Until this time (ms) the kiosk is lifted, see [FrameCommands.MAINTENANCE]. */
    val maintenanceUntil: StateFlow<Long> = _maintenanceUntil

    private val _mode = MutableStateFlow<AppMode?>(null)
    /** Null until the device is paired. */
    val mode: StateFlow<AppMode?> = _mode

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs, cacheDir)
        // revocation arrives on an OkHttp thread
        realtime = Realtime(prefs, api, scope, onRevoked = { scope.launch { signOut() } })
        val display = resources.displayMetrics
        images = Images(api, maxOf(display.widthPixels, display.heightPixels))
        callSession = CallSession(this, api)
        updater = Updater(this, api)
        _mode.value = prefs.mode.takeIf { prefs.token != null }
        ensureFrameRunning()
        ensureContactRunning()
    }

    fun ensureContactRunning(mode: AppMode? = _mode.value) {
        if (mode != AppMode.CONTACT || contactController != null) return
        contactController = ContactController(this).apply { start() }
    }

    /** Starts the frame logic once; the service and the activity both call this. */
    fun ensureFrameRunning(mode: AppMode? = _mode.value) {
        if (mode != AppMode.FRAME || frameController != null) return
        frameController = FrameController(this).apply {
            onIncomingWhileHidden = { name -> FrameService.showIncoming(this@CloseByApp, name) }
            onCommand = ::onFrameCommand
            start()
        }
        updateJob = scope.launch {
            delay(60_000)
            while (true) {
                // without device owner every update needs a tap; only on request then
                if (DeviceOwner.isActive(this@CloseByApp)) Log.i("CloseBy", "update: ${updater.checkAndInstall()}")
                delay(6 * 60 * 60_000L)
            }
        }
    }

    private fun onFrameCommand(command: String) {
        when (command) {
            FrameCommands.UPDATE -> scope.launch { Log.i("CloseBy", "update: ${updater.checkAndInstall()}") }
            FrameCommands.MAINTENANCE -> _maintenanceUntil.value = System.currentTimeMillis() + 10 * 60_000L
        }
    }

    fun paired(mode: AppMode, token: String, deviceId: String) {
        prefs.token = token
        prefs.deviceId = deviceId
        prefs.mode = mode
        // the UI shows the frame as soon as the mode flips, so the controller must exist first
        ensureFrameRunning(mode)
        ensureContactRunning(mode)
        _mode.value = mode
    }

    fun signOut() {
        updateJob?.cancel()
        frameController?.stop()
        frameController = null
        contactController?.stop()
        contactController = null
        FrameService.stop(this)
        realtime.stop()
        prefs.signOut()
        _mode.value = null
    }
}
