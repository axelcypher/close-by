package net.pendzialek.closeby.frame

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.pendzialek.closeby.CloseByApp
import net.pendzialek.closeby.api.FrameContact
import net.pendzialek.closeby.call.CallScreen
import net.pendzialek.closeby.call.CallUi
import net.pendzialek.closeby.api.SlideshowPhoto
import net.pendzialek.closeby.data.Images
import net.pendzialek.closeby.device.DeviceOwner
import net.pendzialek.closeby.ui.Accent
import net.pendzialek.closeby.ui.rememberAvatar
import net.pendzialek.closeby.ui.Muted
import net.pendzialek.closeby.ui.Paper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FrameScreen(viewModel: FrameController) {
    val state by viewModel.state.collectAsState()
    val activity = LocalContext.current as Activity

    // night mode dims the backlight as far as the window allows
    LaunchedEffect(state.view) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = if (state.view == FrameView.NIGHT) 0.01f else -1f
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper)
            // every touch anywhere counts as activity, without consuming it
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        viewModel.touched()
                    }
                }
            },
    ) {
        when (state.view) {
            FrameView.CONTACTS -> Contacts(state, viewModel.images, onCall = viewModel::call, onShowPhotos = viewModel::showSlideshow)
            FrameView.SLIDESHOW -> Slideshow(
                photos = state.photos,
                intervalSeconds = state.config?.settings?.slideshowIntervalSeconds ?: 15,
                images = viewModel.images,
            )
            FrameView.NIGHT -> Box(Modifier.fillMaxSize().background(Color.Black))
        }

        if (state.call != CallUi.None) {
            CallScreen(state.call, viewModel)
        }

        MaintenanceBar(Modifier.align(Alignment.TopCenter))
    }
}

/**
 * The offline banner. A locked frame (device owner) can only be opened by an admin command,
 * which needs the internet: after a while offline (new router, changed Wi-Fi password) it
 * offers the Wi-Fi settings on site, through the same ten-minute maintenance window.
 */
@Composable
private fun Offline(since: Long?) {
    val activity = LocalContext.current as Activity
    val app = activity.applicationContext as CloseByApp
    var longOffline by remember { mutableStateOf(false) }
    LaunchedEffect(since) {
        longOffline = false
        if (since == null) return@LaunchedEffect
        delay(since + OFFLINE_ESCAPE_MS - System.currentTimeMillis())
        longOffline = true
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFB4432F)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Keine Verbindung zum Internet. Anrufe sind gerade nicht möglich.",
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        if (longOffline && DeviceOwner.isActive(activity)) {
            Button(onClick = {
                app.startMaintenance()
                // the kiosk has to be open before another app can come to the front
                runCatching { activity.stopLockTask() }
                activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("WLAN einrichten") }
        }
    }
}

private const val OFFLINE_ESCAPE_MS = 5 * 60_000L

/** Only during a maintenance window (admin command): a way into the Android settings. */
@Composable
private fun MaintenanceBar(modifier: Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as CloseByApp
    val until by app.maintenanceUntil.collectAsState()
    if (until <= System.currentTimeMillis()) return
    val end = java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    Row(
        modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF2F2F2F))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Wartung bis ${end.format(DateTimeFormatter.ofPattern("HH:mm"))}", color = Color.White, fontSize = 18.sp)
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Android-Einstellungen") }
        Button(onClick = { app.endMaintenance() }) { Text("Wartung beenden") }
    }
}

@Composable
private fun Contacts(state: FrameState, images: Images, onCall: (FrameContact) -> Unit, onShowPhotos: () -> Unit) {
    val contacts = state.config?.contacts.orEmpty()
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        if (!state.connected) Offline(state.offlineSince)
        if (state.config == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Einen Moment …", fontSize = 28.sp, color = Muted)
            }
            return
        }
        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch keine Kontakte eingerichtet.", fontSize = 28.sp, color = Muted)
            }
            return
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            val landscape = maxWidth > maxHeight
            // as few rows/columns as possible: big faces are the whole point
            val columns = when {
                contacts.size == 1 -> 1
                contacts.size <= 2 -> if (landscape) 2 else 1
                contacts.size <= 4 -> 2
                contacts.size <= 6 -> if (landscape) 3 else 2
                else -> if (landscape) 4 else 3
            }
            val rows = contacts.chunked(columns)
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { contact ->
                            ContactTile(contact, images, Modifier.weight(1f), onCall)
                        }
                        repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (state.photos.isNotEmpty()) {
            Button(
                onClick = onShowPhotos,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
            ) { Text("Bilder anschauen", fontSize = 24.sp) }
        }
    }
}

@Composable
private fun ContactTile(contact: FrameContact, images: Images, modifier: Modifier, onCall: (FrameContact) -> Unit) {
    val avatar = rememberAvatar(contact, images)
    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White)
            .clickable { onCall(contact) }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.weight(1f).aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(18.dp)).background(Paper),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) {
                Image(avatar, contentDescription = contact.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(contact.name.take(1), fontSize = 72.sp, color = Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            contact.name,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun Slideshow(photos: List<SlideshowPhoto>, intervalSeconds: Int, images: Images) {
    var index by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(photos, intervalSeconds) {
        if (photos.isEmpty()) return@LaunchedEffect
        while (true) {
            val photo = photos[index % photos.size]
            images.photo(photo.id)?.let { current = it.asImageBitmap() }
            // warm the cache for the next one while this one is shown
            images.photo(photos[(index + 1) % photos.size].id)
            delay(intervalSeconds * 1000L)
            index++
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(current, animationSpec = tween(1500), label = "slideshow") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Clock(Modifier.align(Alignment.BottomStart).padding(32.dp))
        Text(
            "Zum Anrufen antippen",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
        )
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(10_000)
        }
    }
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(16.dp)) {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
        Text(
            now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)),
            color = Color.White,
            fontSize = 24.sp,
        )
    }
}
