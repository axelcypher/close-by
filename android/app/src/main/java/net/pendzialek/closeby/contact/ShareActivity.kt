package net.pendzialek.closeby.contact

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.data.AppMode
import net.pendzialek.closeby.ui.CloseByTheme

/** "Teilen → CloseBy" from the gallery: pick a frame, the photos go into its Immich album. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CloseByApp
        val uris = sharedImages(intent)
        setContent {
            CloseByTheme {
                val controller = app.contactController
                val scope = rememberCoroutineScope()
                var message by remember { mutableStateOf<String?>(null) }
                var sending by remember { mutableStateOf(false) }

                if (app.mode.value != AppMode.CONTACT || controller == null) {
                    Info("Richte CloseBy zuerst mit deiner Einladung ein.") { finish() }
                    return@CloseByTheme
                }
                val state by controller.state.collectAsState()
                LaunchedEffect(Unit) { controller.refresh() }

                message?.let { Info(it) { finish() }; return@CloseByTheme }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("${uris.size} Foto(s) an welchen Rahmen?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.frames.isEmpty()) Text("Kein Rahmen verfügbar.")
                            state.frames.forEach { frame ->
                                Button(
                                    onClick = {
                                        sending = true
                                        scope.launch { message = sharePhotos(this@ShareActivity, frame, uris) }
                                    },
                                    enabled = !sending,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (sending) "Wird gesendet …" else frame.name) }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Abbrechen") } },
                )
            }
        }
    }

    private fun sharedImages(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}

@androidx.compose.runtime.Composable
private fun Info(text: String, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        text = { Text(text, modifier = Modifier.padding(top = 8.dp)) },
        confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
    )
}
