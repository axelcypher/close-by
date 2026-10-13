package net.pendzialek.closeby.admin

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.DeviceInfo
import net.pendzialek.closeby.api.FrameAdmin
import net.pendzialek.closeby.api.FrameCommands
import net.pendzialek.closeby.api.FrameContactEntry
import net.pendzialek.closeby.api.FrameUpdate
import net.pendzialek.closeby.api.ImmichAlbum
import net.pendzialek.closeby.api.PersonAdmin
import net.pendzialek.closeby.contact.Section
import net.pendzialek.closeby.contact.claim
import net.pendzialek.closeby.contact.pairCode
import net.pendzialek.closeby.contact.scanQr
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.ui.Danger
import net.pendzialek.closeby.ui.Muted
import java.util.Locale

@Composable
fun FramesAdmin(
    frames: List<FrameAdmin>?,
    persons: List<PersonAdmin>,
    albums: List<ImmichAlbum>?,
    albumsFailed: Boolean,
    actions: AdminActions,
    images: Images,
    changeAvatar: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as CloseByApp
    var code by remember { mutableStateOf("") }

    Section {
        Text("Rahmen hinzufügen", fontWeight = FontWeight.SemiBold)
        Text(
            "Starte CloseBy auf dem Tablet und wähle „Dieses Gerät als Rahmen einrichten“. " +
                "Scanne dann den QR-Code oder gib den angezeigten Code ein.",
            color = Muted,
        )
        Button(onClick = {
            scanQr(context) { raw -> actions.perform { actions.say(claim(app, pairCode(raw) ?: raw)) } }
        }) { Text("QR-Code scannen") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val entered = code
                code = ""
                actions.perform { actions.say(claim(app, entered)) }
            }, enabled = code.isNotBlank()) { Text("Übernehmen") }
        }
    }

    if (albumsFailed) Text("Immich ist gerade nicht erreichbar, Alben können nicht geladen werden.", color = Muted)

    when {
        frames == null -> Text("Lädt …", color = Muted)
        frames.isEmpty() -> Section { Text("Noch kein Rahmen eingerichtet.", color = Muted) }
        else -> frames.forEach { frame ->
            key(frame.device.id) { FrameEditor(frame, persons, albums, actions, images, changeAvatar) }
        }
    }
}

private enum class FrameConfirm { RELEASE, REMOVE }

private val commandMessages = mapOf(
    FrameCommands.MAINTENANCE to "Kiosk ist für 10 Minuten aufgehoben, am Rahmen gibt es jetzt einen Knopf für die Android-Einstellungen.",
    FrameCommands.UPDATE to "Der Rahmen sucht jetzt nach einem Update.",
    FrameCommands.RELEASE to "Device Owner ist aufgehoben, das Tablet ist wieder ein normales Android-Gerät.",
)

@Composable
private fun FrameEditor(
    frame: FrameAdmin,
    persons: List<PersonAdmin>,
    albums: List<ImmichAlbum>?,
    actions: AdminActions,
    images: Images,
    changeAvatar: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CloseByApp
    val device = frame.device
    // edited on a local copy and saved explicitly, like in the web client
    var name by remember { mutableStateOf(device.name) }
    var albumId by remember { mutableStateOf(frame.albumId) }
    var interval by remember { mutableStateOf(frame.settings.slideshowIntervalSeconds.toString()) }
    var idle by remember { mutableStateOf(frame.settings.idleTimeoutSeconds.toString()) }
    var volume by remember { mutableStateOf(frame.settings.volume?.toString().orEmpty()) }
    var nightMode by remember { mutableStateOf(frame.settings.nightStart != null) }
    var nightStart by remember { mutableStateOf(frame.settings.nightStart ?: "22:00") }
    var nightEnd by remember { mutableStateOf(frame.settings.nightEnd ?: "07:00") }
    var publicDns by remember { mutableStateOf(frame.settings.forcePublicDns) }
    val contacts = remember { frame.contacts.toMutableStateList() }
    var confirm by remember { mutableStateOf<FrameConfirm?>(null) }

    val personById = persons.associateBy { it.person.id }
    val hidden = persons.filter { entry -> contacts.none { it.personId == entry.person.id } }

    fun save() {
        if (name.isBlank()) return actions.say("Der Rahmen braucht einen Namen.")
        val intervalSeconds = interval.toIntOrNull()?.takeIf { it in 5..600 }
            ?: return actions.say("Bildwechsel: 5 bis 600 Sekunden.")
        val idleSeconds = idle.toIntOrNull()?.takeIf { it in 10..3600 }
            ?: return actions.say("Diashow nach: 10 bis 3600 Sekunden.")
        val volumePercent = if (volume.isEmpty()) null else volume.toIntOrNull()?.takeIf { it in 0..100 }
            ?: return actions.say("Lautstärke: 0 bis 100 %.")
        val settings = frame.settings.copy(
            slideshowIntervalSeconds = intervalSeconds,
            idleTimeoutSeconds = idleSeconds,
            volume = volumePercent,
            nightStart = nightStart.takeIf { nightMode },
            nightEnd = nightEnd.takeIf { nightMode },
            forcePublicDns = publicDns,
        )
        val entries = contacts.toList()
        actions.perform("Gespeichert, der Rahmen übernimmt es sofort.") {
            app.api.updateFrame(device.id, FrameUpdate(name.trim(), albumId, settings))
            app.api.setFrameContacts(device.id, entries)
        }
    }

    fun command(command: String) = actions.perform(commandMessages[command]) { app.api.frameCommand(device.id, command) }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in contacts.indices) return
        val moved = contacts[index]
        contacts[index] = contacts[target]
        contacts[target] = moved
    }

    Section {
        Text(device.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnlineDot(device.online)
            Text(status(device), color = Muted, fontSize = 14.sp)
        }

        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        AlbumPicker(albumId, albums) { albumId = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(interval, { interval = it }, "Bildwechsel (s)", Modifier.weight(1f))
            NumberField(idle, { idle = it }, "Diashow nach (s)", Modifier.weight(1f))
        }
        NumberField(volume, { volume = it }, "Gesprächslautstärke in %", Modifier.fillMaxWidth(), hint = "leer = Gerät bestimmt")
        CheckRow("Nachtmodus (Bildschirm dunkel)", nightMode) { nightMode = it }
        if (nightMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton("Von", nightStart, Modifier.weight(1f)) { nightStart = it }
                TimeButton("Bis", nightEnd, Modifier.weight(1f)) { nightEnd = it }
            }
        }
        CheckRow("Server über öffentliches DNS auflösen", publicDns) { publicDns = it }

        Text("Kontakte auf dem Rahmen", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text("Tippe auf ein Bild, um es zu ändern. Es gilt für alle Rahmen.", color = Muted, fontSize = 14.sp)
        contacts.forEachIndexed { index, contact ->
            val person = personById[contact.personId]?.person
            if (person != null) key(contact.personId) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarPicker(person, 48.dp, images) { changeAvatar(person.id) }
                    Column(Modifier.weight(1f)) {
                        Text(person.shownName, fontSize = 18.sp)
                        CheckRow("Auto-Annahme", contact.autoAnswer) { contacts[index] = contact.copy(autoAnswer = it) }
                        Row {
                            TextButton(onClick = { move(index, -1) }, enabled = index > 0) { Text("↑") }
                            TextButton(onClick = { move(index, 1) }, enabled = index < contacts.lastIndex) { Text("↓") }
                            TextButton(onClick = { contacts.removeAt(index) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("Ausblenden", color = Danger)
                            }
                        }
                    }
                }
            }
        }
        if (hidden.isNotEmpty()) {
            Text("Nicht auf diesem Rahmen:", color = Muted, fontSize = 14.sp)
            hidden.forEach { entry ->
                key(entry.person.id) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AvatarPicker(entry.person, 48.dp, images) { changeAvatar(entry.person.id) }
                        Text(entry.person.shownName, color = Muted, modifier = Modifier.weight(1f))
                        TextButton(onClick = { contacts.add(FrameContactEntry(entry.person.id)) }) { Text("Anzeigen") }
                    }
                }
            }
        }

        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Speichern") }
        OutlinedButton(onClick = { command(FrameCommands.UPDATE) }, enabled = device.online, modifier = Modifier.fillMaxWidth()) {
            Text("Nach Update suchen")
        }
        if (device.status?.deviceOwner == true) {
            OutlinedButton(onClick = { command(FrameCommands.MAINTENANCE) }, enabled = device.online, modifier = Modifier.fillMaxWidth()) {
                Text("Wartungsmodus (10 min)")
            }
            OutlinedButton(onClick = { confirm = FrameConfirm.RELEASE }, enabled = device.online, modifier = Modifier.fillMaxWidth()) {
                Text("Device Owner aufheben")
            }
        }
        TextButton(onClick = { confirm = FrameConfirm.REMOVE }) { Text("Rahmen entfernen", color = Danger) }
    }

    when (confirm) {
        FrameConfirm.RELEASE -> ConfirmDialog(
            "Kiosk, Rechte und Startbildschirm zurücksetzen? Das Tablet ist danach wieder ein normales Android-Gerät.",
            "Device Owner aufheben",
            onConfirm = { command(FrameCommands.RELEASE) },
            onDismiss = { confirm = null },
        )
        FrameConfirm.REMOVE -> ConfirmDialog(
            "${device.name} wirklich entfernen? Der Rahmen muss danach neu eingerichtet werden.",
            "Entfernen",
            onConfirm = { actions.perform("${device.name} entfernt") { app.api.deleteDevice(device.id) } },
            onDismiss = { confirm = null },
        )
        null -> {}
    }
}

private fun status(device: DeviceInfo): String = buildList {
    add(if (device.online) "online" else "zuletzt ${device.lastSeen?.let(::formatDateTime) ?: "nie"}")
    val status = device.status
    if (status?.battery != null) add("Akku ${status.battery} %" + if (status.charging == true) " ⚡" else "")
    device.appVersion?.let { add("App $it") }
    if (status != null) {
        add(
            when {
                status.deviceOwner != true -> "kein Device Owner"
                status.kiosk == true -> "Kiosk"
                else -> "Kiosk aufgehoben"
            },
        )
    }
}.joinToString(" · ")

@Composable
private fun AlbumPicker(albumId: String?, albums: List<ImmichAlbum>?, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = when {
        albumId == null -> "Kein Album"
        albums == null -> "Gewähltes Album"
        else -> albums.firstOrNull { it.id == albumId }?.let { "${it.name} (${it.assetCount})" } ?: "Unbekanntes Album"
    }
    Column {
        Text("Album für die Diashow", color = Muted, fontSize = 14.sp)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = albums != null, modifier = Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Kein Album") }, onClick = {
                    open = false
                    onChange(null)
                })
                albums.orEmpty().forEach { album ->
                    DropdownMenuItem(text = { Text("${album.name} (${album.assetCount})") }, onClick = {
                        open = false
                        onChange(album.id)
                    })
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, hint: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        supportingText = if (hint != null) { { Text(hint) } } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Night mode times as "HH:mm", picked with the system's clock dialog. */
@Composable
private fun TimeButton(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        val parts = value.split(":").map { it.toIntOrNull() ?: 0 }
        TimePickerDialog(
            context,
            { _, hour, minute -> onChange(String.format(Locale.ROOT, "%02d:%02d", hour, minute)) },
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            true,
        ).show()
    }, modifier = modifier) { Text("$label $value") }
}
