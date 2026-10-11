package net.pendzialek.closeby

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.pendzialek.closeby.call.CallSession
import net.pendzialek.closeby.data.Api
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.data.Prefs
import net.pendzialek.closeby.data.Realtime

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

    private val _mode = MutableStateFlow<AppMode?>(null)
    /** Null until the device is paired. */
    val mode: StateFlow<AppMode?> = _mode

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = Api(prefs, cacheDir)
        realtime = Realtime(prefs, api, scope, onRevoked = ::signOut)
        val display = resources.displayMetrics
        images = Images(api, maxOf(display.widthPixels, display.heightPixels))
        callSession = CallSession(this, api)
        _mode.value = prefs.mode.takeIf { prefs.token != null }
    }

    fun paired(mode: AppMode, token: String, deviceId: String) {
        prefs.token = token
        prefs.deviceId = deviceId
        prefs.mode = mode
        _mode.value = mode
    }

    fun signOut() {
        realtime.stop()
        prefs.signOut()
        _mode.value = null
    }
}
