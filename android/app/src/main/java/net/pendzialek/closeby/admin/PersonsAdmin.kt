package net.pendzialek.closeby.admin

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.DeviceInfo
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.InviteResponse
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.PersonAdmin
import net.pendzialek.closeby.api.PersonInfo
import net.pendzialek.closeby.api.PersonUpdate
import net.pendzialek.closeby.contact.Section
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.ui.Danger
import net.pendzialek.closeby.ui.Muted
import net.pendzialek.closeby.ui.QrCode

@Composable
fun PersonsAdmin(persons: List<PersonAdmin>, me: Me?, actions: AdminActions, images: Images, changeAvatar: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CloseByApp
    var invite by remember { mutableStateOf<InviteResponse?>(null) }

    Section {
        Text("Person einladen", fontWeight = FontWeight.SemiBold)
        Text(
            "Die Person installiert CloseBy, öffnet den Link und legt Name und Bild selbst fest. Danach erscheint sie auf allen Rahmen.",
            color = Muted,
        )
        invite?.let { current ->
            QrCode(current.url, Modifier.size(180.dp).align(Alignment.CenterHorizontally))
            Text(current.code, fontSize = 22.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("Gültig bis ${formatDate(current.expiresAt)}, einmal verwendbar.", color = Muted)
            Button(onClick = { shareInvite(context, current) }, modifier = Modifier.fillMaxWidth()) { Text("Link teilen") }
        }
        OutlinedButton(onClick = { actions.perform { invite = app.api.invite() } }, modifier = Modifier.fillMaxWidth()) {
            Text(if (invite == null) "Einladung erstellen" else "Neue Einladung")
        }
    }

    persons.forEach { entry ->
        key(entry.person.id) { PersonCard(entry, persons, me, actions, images, changeAvatar) }
    }
}

private sealed interface PersonConfirm {
    data object RemovePerson : PersonConfirm
    data class RemoveDevice(val device: DeviceInfo) : PersonConfirm
    data class MoveDevice(val device: DeviceInfo, val to: PersonInfo) : PersonConfirm
}

@Composable
private fun PersonCard(
    entry: PersonAdmin,
    persons: List<PersonAdmin>,
    me: Me?,
    actions: AdminActions,
    images: Images,
    changeAvatar: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as CloseByApp
    val person = entry.person
    val others = persons.filter { it.person.id != person.id }
    var displayName by remember(person.displayName) { mutableStateOf(person.displayName.orEmpty()) }
    var confirm by remember { mutableStateOf<PersonConfirm?>(null) }

    Section {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarPicker(person, 64.dp, images) { changeAvatar(person.id) }
            Column(Modifier.weight(1f)) {
                Text(person.name + if (person.id == me?.person?.id) " (du)" else "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (person.isAdmin) "Admin" else "Kontakt", color = Muted)
            }
        }
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(60) },
            label = { Text("Name auf den Rahmen") },
            placeholder = { Text(person.name) },
            supportingText = { Text("Leer lassen für den eigenen Namen") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                actions.perform("Name gespeichert") {
                    app.api.updatePerson(person.id, PersonUpdate(displayName.trim().ifEmpty { null }, person.isAdmin))
                }
            },
            enabled = displayName.trim() != person.displayName.orEmpty(),
        ) { Text("Name speichern") }
        CheckRow("Admin", person.isAdmin) { isAdmin ->
            actions.perform { app.api.updatePerson(person.id, PersonUpdate(person.displayName, isAdmin)) }
        }

        Text("Geräte", fontWeight = FontWeight.SemiBold)
        if (entry.devices.isEmpty()) Text("Keine Geräte", color = Muted)
        entry.devices.forEach { device ->
            key(device.id) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OnlineDot(device.online)
                        Text("${device.name} · ${kindLabel(device.kind)}")
                    }
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (others.isNotEmpty()) MoveMenu(others) { to -> confirm = PersonConfirm.MoveDevice(device, to) }
                        if (device.id == me?.device?.id) {
                            Text("dieses Gerät", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp))
                        } else {
                            TextButton(onClick = { confirm = PersonConfirm.RemoveDevice(device) }) { Text("Abmelden", color = Danger) }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { confirm = PersonConfirm.RemovePerson }) { Text("Person entfernen", color = Danger) }
    }

    val dismiss = { confirm = null }
    when (val pending = confirm) {
        PersonConfirm.RemovePerson -> ConfirmDialog(
            "${person.name} mit allen Geräten entfernen?",
            "Entfernen",
            onConfirm = { actions.perform("${person.name} entfernt") { app.api.deletePerson(person.id) } },
            onDismiss = dismiss,
        )
        is PersonConfirm.RemoveDevice -> ConfirmDialog(
            "${pending.device.name} abmelden? Das Gerät muss danach neu eingerichtet werden.",
            "Abmelden",
            onConfirm = { actions.perform("${pending.device.name} abgemeldet") { app.api.deleteDevice(pending.device.id) } },
            onDismiss = dismiss,
        )
        is PersonConfirm.MoveDevice -> ConfirmDialog(
            // the backend merges a person left without devices into the target
            if (entry.devices.size == 1) {
                "${pending.device.name} gehört dann zu ${pending.to.name}. ${person.name} hat danach kein Gerät mehr " +
                    "und wird mit ${pending.to.name} zusammengelegt (Rahmen, Admin-Rechte, Bild)."
            } else {
                "${pending.device.name} gehört dann zu ${pending.to.name}."
            },
            "Zuordnen",
            onConfirm = {
                actions.perform("${pending.device.name} gehört jetzt zu ${pending.to.name}") {
                    app.api.moveDevice(pending.device.id, pending.to.id)
                }
            },
            onDismiss = dismiss,
        )
        null -> {}
    }
}

/** Hands a phone or PC to another person, e.g. when someone registered twice. */
@Composable
private fun MoveMenu(others: List<PersonAdmin>, onPick: (PersonInfo) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Gehört zu …") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            others.forEach { other ->
                DropdownMenuItem(text = { Text(other.person.name) }, onClick = {
                    open = false
                    onPick(other.person)
                })
            }
        }
    }
}

private fun kindLabel(kind: DeviceKind) = when (kind) {
    DeviceKind.PHONE -> "Handy"
    DeviceKind.DESKTOP -> "PC"
    DeviceKind.FRAME -> "Rahmen"
}

private fun shareInvite(context: Context, invite: InviteResponse) {
    val text = "Du bist zu CloseBy eingeladen: ${invite.url}"
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Einladung teilen"),
    )
}
