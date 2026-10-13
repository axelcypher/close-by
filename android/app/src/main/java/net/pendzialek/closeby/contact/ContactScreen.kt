package net.pendzialek.closeby.contact

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.admin.AdminScreen
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.FrameRef
import net.pendzialek.closeby.call.CallScreen
import net.pendzialek.closeby.call.CallUi
import net.pendzialek.closeby.data.ApiException
import net.pendzialek.closeby.ui.Accent
import net.pendzialek.closeby.ui.Muted
import net.pendzialek.closeby.ui.Ok
import net.pendzialek.closeby.ui.Paper
import net.pendzialek.closeby.ui.PersonAvatar
import net.pendzialek.closeby.util.Jpeg

@Composable
fun ContactScreen(controller: ContactController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as CloseByApp
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun say(message: String) = scope.launch { snackbar.showSnackbar(message) }

    val isAdmin = state.me?.person?.isAdmin == true
    var adminOpen by rememberSaveable { mutableStateOf(false) }
    var shareTarget by remember { mutableStateOf<FrameRef?>(null) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        val frame = shareTarget ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch { say(sharePhotos(context, frame, uris)) }
    }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.api.uploadAvatar(Jpeg.square(context, uri)) }
                .onSuccess { controller.refresh(); say("Bild gespeichert") }
                .onFailure { say("Bild konnte nicht gespeichert werden") }
        }
    }

    Box(Modifier.fillMaxSize().background(Paper)) {
        if (adminOpen && isAdmin) {
            AdminScreen(
                me = state.me,
                revision = state.revision,
                say = { say(it) },
                onChanged = { scope.launch { controller.refresh() } },
                onClose = { adminOpen = false },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().safeDrawingPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CloseBy", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Accent, modifier = Modifier.weight(1f))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.connected) Ok else Muted))
                    }
                }

                if (state.frames.isEmpty()) {
                    item { Section { Text("Noch kein Rahmen, den du anrufen kannst.", color = Muted) } }
                }
                items(state.frames, key = { it.id }) { frame ->
                    Section {
                        Text(frame.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (frame.online) "online" else "offline", color = if (frame.online) Ok else Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { controller.call(frame) }, enabled = frame.online && state.call == CallUi.None) {
                                Text("Anrufen")
                            }
                            OutlinedButton(onClick = {
                                shareTarget = frame
                                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("Fotos schicken") }
                        }
                    }
                }

                if (isAdmin) {
                    item {
                        Section {
                            Text("Verwaltung", fontWeight = FontWeight.SemiBold)
                            Text("Rahmen einstellen, Personen einladen und die Bilder ändern, die die Rahmen zeigen.", color = Muted)
                            Button(onClick = { adminOpen = true }) { Text("Verwaltung öffnen") }
                        }
                    }
                }

                state.me?.person?.let { person ->
                    item {
                        var name by remember(person.name) { mutableStateOf(person.name) }
                        Section {
                            Text("So sieht dich der Rahmen", fontWeight = FontWeight.SemiBold)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PersonAvatar(person.id, person.avatarVersion, person.name, 72.dp, app.images)
                                TextButton(onClick = {
                                    pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Text("Bild ändern") }
                            }
                            OutlinedTextField(name, { name = it }, label = { Text("Dein Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { app.api.rename(name.trim()) }
                                        .onSuccess { controller.refresh(); say("Name gespeichert") }
                                        .onFailure { say("Name konnte nicht gespeichert werden") }
                                }
                            }, enabled = name.isNotBlank() && name != person.name) { Text("Name speichern") }
                        }
                    }
                }

                item {
                    var code by remember { mutableStateOf("") }
                    Section {
                        Text("Geräte", fontWeight = FontWeight.SemiBold)
                        Text("Rahmen oder PC verknüpfen: den angezeigten QR-Code scannen oder den Code eingeben.", color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                scanQr(context) { raw -> scope.launch { say(claim(app, pairCode(raw) ?: raw)) } }
                            }) { Text("QR-Code scannen") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { scope.launch { say(claim(app, code)); code = "" } }, enabled = code.isNotBlank()) {
                                Text("Übernehmen")
                            }
                        }
                    }
                }

                item {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { app.api.signOutDevice() }
                            app.signOut()
                        }
                    }) { Text("Abmelden", color = Color(0xFFB4432F)) }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())

        if (state.call != CallUi.None) CallScreen(state.call, controller)
    }
}

@Composable
internal fun Section(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** QR payloads are `closeby://pair?server=…&code=…` (pairing) or `https://…/invite/<code>` (invites). */
fun pairCode(raw: String): String? = runCatching { Uri.parse(raw) }.getOrNull()?.takeIf { it.scheme == "closeby" }?.getQueryParameter("code")

fun inviteCode(raw: String): String? =
    runCatching { Uri.parse(raw) }.getOrNull()?.pathSegments?.takeIf { it.size == 2 && it[0] == "invite" }?.get(1)

/** Google's scanner UI: no camera permission needed, runs in Play services. */
fun scanQr(context: Context, onResult: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
    GmsBarcodeScanning.getClient(context, options).startScan().addOnSuccessListener { barcode ->
        barcode.rawValue?.let(onResult)
    }
}

internal suspend fun claim(app: CloseByApp, code: String): String = try {
    when (app.api.claimPairing(code).kind) {
        DeviceKind.FRAME -> "Rahmen übernommen"
        else -> "Gerät verknüpft"
    }
} catch (e: ApiException) {
    when (e.status) {
        404 -> "Code unbekannt oder abgelaufen"
        403 -> "Rahmen kann nur ein Admin übernehmen"
        else -> "Das hat nicht geklappt"
    }
} catch (_: Exception) {
    "Keine Verbindung zum Server"
}

suspend fun sharePhotos(context: Context, frame: FrameRef, uris: List<Uri>): String {
    val app = context.applicationContext as CloseByApp
    var sent = 0
    for (uri in uris) {
        try {
            app.api.sharePhoto(frame.id, Jpeg.scaled(context, uri))
            sent++
        } catch (e: ApiException) {
            if (e.status == 409) return "Für ${frame.name} ist noch kein Album eingerichtet"
        } catch (_: Exception) {
            // keep going with the rest
        }
    }
    return if (sent == uris.size) "$sent Foto(s) an ${frame.name} geschickt" else "$sent von ${uris.size} Fotos geschickt"
}
