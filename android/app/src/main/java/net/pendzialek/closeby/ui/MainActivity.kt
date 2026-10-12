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
import net.pendzialek.closeby.contact.CallNotifications
import net.pendzialek.closeby.contact.ContactScreen
import net.pendzialek.closeby.contact.inviteCode
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
        handleIntent(intent)
        setContent {
            CloseByTheme {
                val mode by app.mode.collectAsState()
                val maintenanceUntil by app.maintenanceUntil.collectAsState()
                LaunchedEffect(mode) { applyWindowMode(mode) }
                val releaseRequested by app.releaseRequested.collectAsState()
                LaunchedEffect(releaseRequested) {
                    if (!releaseRequested) return@LaunchedEffect
                    // lock task has to end before device owner can go
                    if (isLocked()) stopLockTask()
                    app.releaseDeviceOwner()
                    app.kioskActive = false
                }
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
                    AppMode.CONTACT -> app.contactController?.let { ContactScreen(it) }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Invite links (`https://…/invite/<code>`) and "Annehmen" from the call notification. */
    private fun handleIntent(intent: android.content.Intent?) {
        intent?.data?.toString()?.let(::inviteCode)?.let { app.openInvite(it) }
        if (intent?.action == CallNotifications.ACTION_ACCEPT) app.contactController?.accept()
    }

    override fun onResume() {
        super.onResume()
        // coming back from the Android settings after maintenance
        if (app.mode.value == AppMode.FRAME) {
            updateKiosk()
            hideSystemBars()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // dialogs, the settings app and the screen turning on bring the bars back
        if (hasFocus && app.mode.value == AppMode.FRAME) hideSystemBars()
    }

    private fun hideSystemBars() {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
        if (app.mode.value == AppMode.FRAME) FrameService.start(this)
        app.contactController?.onUiVisible(true)
    }

    override fun onStop() {
        app.uiVisible = false
        app.contactController?.onUiVisible(false)
        super.onStop()
    }

    /** A frame stays on, fullscreen, and may use camera and microphone without asking mid-call. */
    private fun applyWindowMode(mode: AppMode?) {
        if (mode == AppMode.FRAME) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hideSystemBars()
            app.ensureFrameRunning()
            DeviceOwner.applyFramePolicies(this)
            // a restart during maintenance must not lock the status bar again
            DeviceOwner.setMaintenance(this, app.maintenanceUntil.value > System.currentTimeMillis())
            requestMissingPermissions()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            // a phone needs camera and microphone for calls and notifications to ring
            if (mode == AppMode.CONTACT) requestPermissions(includeCamera = true)
        }
    }

    /** Device owner frames are locked to CloseBy, except during a maintenance window. */
    private fun updateKiosk() {
        val locked = isLocked()
        val wanted = DeviceOwner.isActive(this) && System.currentTimeMillis() >= app.maintenanceUntil.value
        if (wanted && !locked) startLockTask()
        if (!wanted && locked) stopLockTask()
        app.kioskActive = wanted
    }

    private fun isLocked() =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun requestMissingPermissions() {
        requestPermissions(includeCamera = true)
    }

    private fun requestPermissions(includeCamera: Boolean) {
        // a device owner grants these itself
        if (DeviceOwner.isActive(this)) return
        val wanted = buildList {
            if (includeCamera) add(Manifest.permission.CAMERA)
            if (includeCamera) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}
