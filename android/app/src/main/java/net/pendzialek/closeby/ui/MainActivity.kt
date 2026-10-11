package net.pendzialek.closeby.ui

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.device.DeviceOwner
import net.pendzialek.closeby.frame.FrameScreen
import net.pendzialek.closeby.frame.FrameService

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val app get() = application as CloseByApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloseByTheme {
                val mode by app.mode.collectAsState()
                val maintenanceUntil by app.maintenanceUntil.collectAsState()
                LaunchedEffect(mode) { applyWindowMode(mode) }
                LaunchedEffect(mode, maintenanceUntil) {
                    if (mode != AppMode.FRAME) return@LaunchedEffect
                    updateKiosk()
                    // lock again once the maintenance window is over
                    val remaining = maintenanceUntil - System.currentTimeMillis()
                    if (remaining > 0) {
                        delay(remaining + 1_000)
                        updateKiosk()
                    }
                }
                when (mode) {
                    null -> SetupScreen()
                    AppMode.FRAME -> app.frameController?.let { FrameScreen(it) }
                    AppMode.CONTACT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Der Kontakt-Modus kommt in einer späteren Version.")
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
        if (app.mode.value == AppMode.FRAME) FrameService.start(this)
    }

    override fun onStop() {
        app.uiVisible = false
        super.onStop()
    }

    /** A frame stays on, fullscreen, and may use camera and microphone without asking mid-call. */
    private fun applyWindowMode(mode: AppMode?) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (mode == AppMode.FRAME) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            app.ensureFrameRunning()
            DeviceOwner.applyFramePolicies(this)
            requestMissingPermissions()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Device owner frames are locked to CloseBy, except during a maintenance window. */
    private fun updateKiosk() {
        val locked = getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        val wanted = DeviceOwner.isActive(this) && System.currentTimeMillis() >= app.maintenanceUntil.value
        if (wanted && !locked) startLockTask()
        if (!wanted && locked) stopLockTask()
        app.kioskActive = wanted
    }

    private fun requestMissingPermissions() {
        // a device owner grants these itself
        if (DeviceOwner.isActive(this)) return
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}
