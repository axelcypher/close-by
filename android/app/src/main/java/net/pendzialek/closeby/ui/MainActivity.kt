package net.pendzialek.closeby.ui

import android.Manifest
import android.content.pm.PackageManager
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
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.frame.FrameScreen

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CloseByApp
        setContent {
            CloseByTheme {
                val mode by app.mode.collectAsState()
                LaunchedEffect(mode) { applyWindowMode(mode) }
                when (mode) {
                    null -> SetupScreen()
                    AppMode.FRAME -> FrameScreen()
                    AppMode.CONTACT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Der Kontakt-Modus kommt in einer späteren Version.")
                    }
                }
            }
        }
    }

    /** A frame stays on, fullscreen, and may use camera and microphone without asking mid-call. */
    private fun applyWindowMode(mode: AppMode?) {
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (mode == AppMode.FRAME) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
            val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
