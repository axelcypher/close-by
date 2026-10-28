package net.pendzialek.closeby

import android.app.Application
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.pendzialek.closeby.api.FrameCommands
import net.pendzialek.closeby.call.CallSession
import net.pendzialek.closeby.call.CallUi
import net.pendzialek.closeby.contact.ContactController
import net.pendzialek.closeby.data.Api
import net.pendzialek.closeby.data.ApiException
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.data.Prefs
import net.pendzialek.closeby.data.Realtime
import net.pendzialek.closeby.device.DeviceOwner
import net.pendzialek.closeby.device.UpdateJob
import net.pendzialek.closeby.device.Updater
import net.pendzialek.closeby.frame.FrameController
import net.pendzialek.closeby.frame.FrameService
import net.pendzialek.closeby.ui.MainActivity

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

    private val _frameController = MutableStateFlow<FrameController?>(null)

    /** Null unless paired as frame (and, after a quick restart, until the rescue check is done). */
    val frameController: FrameController? get() = _frameController.value

    /** Observable [frameController]: the frame screen appears once the controller exists. */
    val frameControllers: StateFlow<FrameController?> = _frameController

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

    /** The previous process started only moments ago: this version may crash, see [ensureFrameRunning]. */
    private var restartedQuickly = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val now = System.currentTimeMillis()
        restartedQuickly = now - prefs.lastStartAt in 0 until QUICK_RESTART_MS
        prefs.lastStartAt = now
        api = Api(prefs, cacheDir)
        realtime = Realtime(prefs, api, scope, onRevoked = ::checkRevoked)
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
        UpdateJob.schedule(this)
    }

    private var rescue: Job? = null

    /**
     * Starts the frame logic once; the service and the activity both call this.
     *
     * Right after a short-lived run (perhaps this release crashes at start, in the frame logic
     * or its screen) nothing of that runs before a look for a newer release: a broken release
     * is replaced by the next one without anybody on site. Without one, the frame starts after
     * at most 15 s (plus the download when there is one).
     */
    fun ensureFrameRunning(mode: AppMode? = _mode.value) {
        if (mode != AppMode.FRAME || frameController != null || rescue?.isActive == true) return
        if (!restartedQuickly) return startFrame()
        rescue = scope.launch {
            // a check of its own, so the timeout holds even while OkHttp blocks on a bad network
            val check = scope.async { updater.newerRelease() }
            val release = withTimeoutOrNull(RESCUE_CHECK_MS) { check.await() }
            if (release != null && updater.canInstallSilently()) {
                // on success the process ends here and the new version starts
                Log.i("CloseBy", "update after a quick restart: ${updater.install(release)}")
            }
            restartedQuickly = false
            startFrame()
        }
    }

    private fun startFrame() {
        startUpdateLoop()
        _frameController.value = FrameController(this).apply {
            onIncomingWhileHidden = { name -> FrameService.showIncoming(this@CloseByApp, name) }
            onCommand = ::onFrameCommand
            start()
        }
    }

    /** Frames update themselves every six hours, but never during a call. */
    private fun startUpdateLoop() {
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(60_000)
            while (true) {
                var retrySoon = inCall()
                // an update that needs a tap waits for an admin's request
                if (!retrySoon && updater.canInstallSilently()) {
                    val result = updater.checkAndInstall { !inCall() }
                    Log.i("CloseBy", "update: $result")
                    retrySoon = result == Updater.POSTPONED
                }
                delay(if (retrySoon) 10 * 60_000L else 6 * 60 * 60_000L)
            }
        }
    }

    /** A call is ringing, being set up or going on; an update would cut it off. */
    fun inCall(): Boolean = listOfNotNull(frameController?.state?.value?.call, contactController?.state?.value?.call)
        .any { it != CallUi.None && it !is CallUi.Ended }

    private var revocationCheck: Job? = null

    /**
     * The server called our token unknown (websocket 1008, HTTP 401). A proxy or a restart
     * can say so as well, and a frame that signs out has to be paired again on site:
     * only /api/me refusing us again and again counts as revoked (a phone: twice within a
     * minute; a frame: three times over a quarter of an hour).
     */
    fun checkRevoked() {
        if (revocationCheck?.isActive == true || prefs.token == null) return
        val frame = _mode.value == AppMode.FRAME
        revocationCheck = scope.launch {
            repeat(if (frame) 3 else 2) { attempt ->
                if (attempt > 0) delay(if (frame) 7 * 60_000L + 30_000L else 60_000L)
                val refused = try {
                    api.me()
                    false
                } catch (e: ApiException) {
                    e.status == 401
                } catch (_: Exception) {
                    false
                }
                if (!refused) return@launch
            }
            signOut()
        }
    }

    private fun onFrameCommand(command: String) {
        when (command) {
            FrameCommands.UPDATE -> scope.launch { Log.i("CloseBy", "update: ${updater.checkAndInstall { !inCall() }}") }
            FrameCommands.MAINTENANCE -> startMaintenance()
            FrameCommands.RELEASE -> _releaseRequested.value = true
        }
    }

    private val _releaseRequested = MutableStateFlow(false)
    /** The activity leaves lock task mode and then calls [releaseDeviceOwner]. */
    val releaseRequested: StateFlow<Boolean> = _releaseRequested

    fun releaseDeviceOwner() {
        DeviceOwner.release(this)
        _releaseRequested.value = false
        _maintenanceUntil.value = 0
    }

    private var maintenanceJob: Job? = null

    fun startMaintenance() {
        _maintenanceUntil.value = System.currentTimeMillis() + MAINTENANCE_MS
        DeviceOwner.setMaintenance(this, true)
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            delay(MAINTENANCE_MS)
            endMaintenance()
        }
    }

    /** Back to kiosk, also when someone is still in the Android settings. */
    fun endMaintenance() {
        maintenanceJob?.cancel()
        _maintenanceUntil.value = 0
        DeviceOwner.setMaintenance(this, false)
        if (_mode.value == AppMode.FRAME) {
            // device owners may start activities from the background
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
    }

    private companion object {
        const val MAINTENANCE_MS = 10 * 60_000L
        const val QUICK_RESTART_MS = 3 * 60_000L
        /** How long a restarted frame waits for the release check (not the download) before it starts anyway. */
        const val RESCUE_CHECK_MS = 15_000L
    }

    fun paired(mode: AppMode, token: String, deviceId: String) {
        prefs.token = token
        prefs.deviceId = deviceId
        prefs.mode = mode
        // the UI shows the frame as soon as the mode flips, so the controller must exist first
        ensureFrameRunning(mode)
        ensureContactRunning(mode)
        _mode.value = mode
        // keeps the websocket alive with the screen off, not only after the next activity start
        if (mode == AppMode.FRAME) FrameService.start(this)
    }

    fun signOut() {
        revocationCheck?.cancel()
        rescue?.cancel()
        updateJob?.cancel()
        frameController?.stop()
        _frameController.value = null
        contactController?.stop()
        contactController = null
        UpdateJob.cancel(this)
        FrameService.stop(this)
        realtime.stop()
        prefs.signOut()
        _mode.value = null
    }
}
