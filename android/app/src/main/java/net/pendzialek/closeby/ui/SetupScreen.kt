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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import net.pendzialek.closeby.api.NewDevice
import net.pendzialek.closeby.api.NewPerson
import net.pendzialek.closeby.api.RegisterRequest
import net.pendzialek.closeby.contact.inviteCode
import net.pendzialek.closeby.contact.scanQr
import net.pendzialek.closeby.data.ApiException
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
                val status = try {
                    app.api.pollPairing(pairing.pairingId, pairing.pollSecret)
                } catch (e: ApiException) {
                    // the code is gone (e.g. refused as the wrong kind of device): show a fresh one
                    if (e.status == 404) return@launch startFrame(server)
                    continue
                } catch (_: Exception) {
                    continue
                }
                if (status.state == PairingState.DONE && status.token != null) {
                    app.paired(AppMode.FRAME, status.token!!, status.deviceId!!)
                    return@launch
                }
            }
            _state.value = PairingUi.Failed("Der Code ist abgelaufen.")
        }
    }

    /** A relative's phone: invite code or admin code, plus the name the frame shows. */
    fun registerContact(server: String, code: String, admin: Boolean, name: String) {
        app.prefs.server = server
        app.prefs.forcePublicDns = false
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = PairingUi.Starting
            try {
                val request = RegisterRequest(
                    code = code.trim().takeIf { !admin },
                    adminCode = code.trim().takeIf { admin },
                    device = NewDevice(DeviceKind.PHONE, Build.MODEL),
                    person = NewPerson(name.trim()),
                )
                val auth = app.api.register(request)
                app.openInvite(null)
                app.paired(AppMode.CONTACT, auth.token, auth.deviceId)
            } catch (e: ApiException) {
                _state.value = PairingUi.Failed(
                    if (e.status == 403) {
                        if (admin) "Der Admin-Code stimmt nicht." else "Die Einladung ist ungültig oder abgelaufen."
                    } else {
                        "Das hat nicht geklappt: ${e.message}"
                    },
                )
            } catch (e: Exception) {
                _state.value = PairingUi.Failed("Der Server ist nicht erreichbar.\n${e.message ?: ""}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = PairingUi.Idle
    }
}

private enum class SetupChoice { CHOOSE, INVITE, ADMIN }

@Composable
fun SetupScreen(viewModel: PairingViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as CloseByApp
    val invite by app.pendingInvite.collectAsState()
    var server by remember { mutableStateOf(viewModel.server) }
    var choice by remember { mutableStateOf(SetupChoice.CHOOSE) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // an opened invite link jumps straight to the invite form
    LaunchedEffect(invite) {
        invite?.let {
            code = it
            choice = SetupChoice.INVITE
        }
    }

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
                    when (choice) {
                        SetupChoice.CHOOSE -> {
                            Button(onClick = { choice = SetupChoice.INVITE }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text("Ich habe eine Einladung", fontSize = 18.sp)
                            }
                            OutlinedButton(onClick = { viewModel.startFrame(server) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text("Dieses Gerät als Rahmen einrichten", fontSize = 18.sp)
                            }
                            TextButton(onClick = { choice = SetupChoice.ADMIN }) { Text("Als Admin einrichten") }
                            Text(
                                "Ein Rahmen zeigt nach der Einrichtung einen Code, den ein Admin übernimmt.",
                                color = Muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                        SetupChoice.INVITE, SetupChoice.ADMIN -> {
                            val admin = choice == SetupChoice.ADMIN
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                label = { Text(if (admin) "Admin-Code" else "Einladungscode") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!admin) {
                                TextButton(onClick = {
                                    scanQr(context) { raw -> code = inviteCode(raw) ?: raw }
                                }) { Text("Einladungs-QR-Code scannen") }
                            }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Dein Name, so sieht ihn der Rahmen") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { viewModel.registerContact(server, code, admin, name) },
                                enabled = code.isNotBlank() && name.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { Text("Einrichten", fontSize = 18.sp) }
                            TextButton(onClick = { choice = SetupChoice.CHOOSE }) { Text("Zurück") }
                        }
                    }
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
