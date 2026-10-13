package net.pendzialek.closeby.admin

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.FrameAdmin
import net.pendzialek.closeby.api.ImmichAlbum
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.PersonAdmin
import net.pendzialek.closeby.api.PersonInfo
import net.pendzialek.closeby.data.ApiException
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.ui.Accent
import net.pendzialek.closeby.ui.Danger
import net.pendzialek.closeby.ui.Muted
import net.pendzialek.closeby.ui.Ok
import net.pendzialek.closeby.ui.Paper
import net.pendzialek.closeby.ui.PersonAvatar
import net.pendzialek.closeby.util.Jpeg
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class AdminTab { FRAMES, PERSONS }

/** Runs an admin call, reports how it went and reloads frames and persons. */
class AdminActions(
    private val scope: CoroutineScope,
    val say: (String) -> Unit,
    private val reload: () -> Unit,
) {
    fun perform(success: String? = null, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                success?.let(say)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(errorText(e))
            }
            reload()
        }
    }
}

fun errorText(e: Exception): String = when {
    e !is ApiException -> "Keine Verbindung zum Server"
    e.status == 403 -> "Dafür braucht es Admin-Rechte."
    e.message == "last admin" -> "Es muss mindestens einen Admin geben."
    e.message == "you would lose admin" -> "Damit wärst du kein Admin mehr."
    e.message == "frame offline" -> "Der Rahmen ist offline."
    else -> "Das hat nicht geklappt: ${e.message}"
}

val PersonInfo.shownName get() = displayName ?: name

/**
 * The admin part of the web client ("Rahmen", "Personen") on the phone.
 * [revision] changes on presence and config events; [onChanged] lets the
 * contact screen pick up changes to the admin's own person.
 */
@Composable
fun AdminScreen(me: Me?, revision: Int, say: (String) -> Unit, onChanged: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CloseByApp
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(AdminTab.FRAMES) }
    var frames by remember { mutableStateOf<List<FrameAdmin>?>(null) }
    var persons by remember { mutableStateOf<List<PersonAdmin>>(emptyList()) }
    var albums by remember { mutableStateOf<List<ImmichAlbum>?>(null) }
    var albumsFailed by remember { mutableStateOf(false) }
    var reloads by remember { mutableIntStateOf(0) }
    val actions = remember {
        AdminActions(scope, say) {
            reloads++
            onChanged()
        }
    }

    BackHandler(onBack = onClose)

    LaunchedEffect(revision, reloads) {
        try {
            val loadedFrames = app.api.adminFrames()
            persons = app.api.adminPersons()
            frames = loadedFrames
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say(errorText(e))
        }
    }
    LaunchedEffect(Unit) {
        runCatching { app.api.albums() }
            .onSuccess { albums = it }
            .onFailure { albumsFailed = true }
    }

    // the picker returns after a possible activity restart, so the target survives it
    var avatarFor by rememberSaveable { mutableStateOf<String?>(null) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val personId = avatarFor ?: return@rememberLauncherForActivityResult
        avatarFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        actions.perform("Bild gespeichert, die Rahmen zeigen es gleich") {
            app.api.uploadPersonAvatar(personId, Jpeg.square(context, uri))
        }
    }
    val changeAvatar: (String) -> Unit = { personId ->
        avatarFor = personId
        pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Zurück") }
            Text("Verwaltung", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabButton("Rahmen", tab == AdminTab.FRAMES, Modifier.weight(1f)) { tab = AdminTab.FRAMES }
            TabButton("Personen", tab == AdminTab.PERSONS, Modifier.weight(1f)) { tab = AdminTab.PERSONS }
        }
        // a plain column keeps every editor composed, so unsaved input survives scrolling
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                AdminTab.FRAMES -> FramesAdmin(frames, persons, albums, albumsFailed, actions, app.images, changeAvatar)
                AdminTab.PERSONS -> PersonsAdmin(persons, me, actions, app.images, changeAvatar)
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
internal fun OnlineDot(online: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) Ok else Muted))
}

/** Asks before something that can't be undone with one tap. */
@Composable
internal fun ConfirmDialog(question: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(question) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirm, color = Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** A person's picture with a visible hint that tapping it replaces the picture on every frame. */
@Composable
internal fun AvatarPicker(person: PersonInfo, size: Dp, images: Images, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClickLabel = "Bild ändern", onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PersonAvatar(person.id, person.avatarVersion, person.shownName, size, images)
        Text("Bild ändern", fontSize = 12.sp, color = Accent)
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

internal fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.GERMANY).format(Date(ms))

internal fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.GERMANY).format(Date(ms))
