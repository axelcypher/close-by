package net.pendzialek.closeby.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.PairingStartResponse
import net.pendzialek.closeby.api.PairingState
import net.pendzialek.closeby.data.AppMode

sealed interface PairingUi {
    data object Idle : PairingUi
    data object Starting : PairingUi
    data class Waiting(val pairing: PairingStartResponse) : PairingUi
    data class Failed(val message: String) : PairingUi
}

class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CloseByApp
    private val _state = MutableStateFlow<PairingUi>(PairingUi.Idle)
    val state: StateFlow<PairingUi> = _state
    private var job: Job? = null

    val server get() = app.prefs.server

    fun startFrame(server: String) {
        app.prefs.server = server
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = PairingUi.Starting
            val pairing = try {
                app.api.startPairing(DeviceKind.FRAME, "Rahmen (${Build.MODEL})")
            } catch (e: Exception) {
                _state.value = PairingUi.Failed("Der Server ist nicht erreichbar.\n${e.message ?: ""}")
                return@launch
            }
            _state.value = PairingUi.Waiting(pairing)
            while (System.currentTimeMillis() < pairing.expiresAt) {
                delay(2_000)
                val status = runCatching { app.api.pollPairing(pairing.pairingId, pairing.pollSecret) }.getOrNull() ?: continue
                if (status.state == PairingState.DONE && status.token != null) {
                    app.paired(AppMode.FRAME, status.token!!, status.deviceId!!)
                    return@launch
                }
            }
            _state.value = PairingUi.Failed("Der Code ist abgelaufen.")
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = PairingUi.Idle
    }
}

@Composable
fun SetupScreen(viewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var server by remember { mutableStateOf(viewModel.server) }

    Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).padding(32.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("CloseBy", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Accent)
            Text("Vertraute Gesichter, ein Tippen entfernt.", color = Muted, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))

            when (val s = state) {
                PairingUi.Idle, is PairingUi.Failed -> {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { viewModel.startFrame(server) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Als Rahmen einrichten", fontSize = 18.sp)
                    }
                    Text(
                        "Der Rahmen zeigt danach einen Code. Ein Admin übernimmt ihn in CloseBy unter „Rahmen“.",
                        color = Muted,
                        textAlign = TextAlign.Center,
                    )
                    if (s is PairingUi.Failed) Text(s.message, color = Danger, textAlign = TextAlign.Center)
                }
                PairingUi.Starting -> Text("Verbinde mit dem Server …", color = Muted)
                is PairingUi.Waiting -> {
                    Text("Diesen Rahmen übernehmen", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    QrCode(
                        "closeby://pair?server=${Uri.encode(server)}&code=${s.pairing.code}",
                        modifier = Modifier.size(220.dp),
                    )
                    Text(
                        s.pairing.code.chunked(4).joinToString("-"),
                        fontSize = 44.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                    )
                    Text(
                        "Am PC: CloseBy öffnen → Rahmen → Code eingeben.\nAm Handy: Gerät hinzufügen → Code scannen.",
                        color = Muted,
                        textAlign = TextAlign.Center,
                    )
                    Row { OutlinedButton(onClick = viewModel::cancel) { Text("Abbrechen") } }
                }
            }
        }
    }
}
