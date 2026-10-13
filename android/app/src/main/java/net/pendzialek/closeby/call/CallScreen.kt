package net.pendzialek.closeby.call

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import net.pendzialek.closeby.ui.Danger
import net.pendzialek.closeby.ui.rememberPicture
import net.pendzialek.closeby.ui.Ok

private val Dark = Color(0xFF151515)

@Composable
fun CallScreen(call: CallUi, viewModel: CallController) {
    // swallow touches so nothing behind the overlay reacts
    Box(Modifier.fillMaxSize().background(Dark).pointerInput(Unit) {}) {
        when (call) {
            is CallUi.Active -> ActiveCall(call, viewModel)
            is CallUi.Incoming -> Prompt(
                name = call.name,
                picture = call.picture,
                viewModel = viewModel,
                label = if (call.autoAnswer) "verbindet gleich …" else "ruft an",
            ) {
                if (!call.autoAnswer) {
                    BigButton("Ablehnen", Danger, viewModel::decline)
                    Spacer(Modifier.width(48.dp))
                    BigButton("Annehmen", Ok, viewModel::accept)
                } else {
                    BigButton("Nicht jetzt", Danger, viewModel::decline)
                }
            }
            is CallUi.Outgoing -> Prompt(call.name, call.picture, viewModel, "wird angerufen …") {
                BigButton("Auflegen", Danger, viewModel::hangUp)
            }
            is CallUi.Ended -> Prompt(call.name, null, viewModel, call.message) {}
            CallUi.None -> Unit
        }
    }
}

@Composable
private fun Prompt(
    name: String,
    picture: String?,
    viewModel: CallController,
    label: String,
    buttons: @Composable () -> Unit,
) {
    val avatar = rememberPicture(picture, viewModel.images)
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (avatar != null) {
            Image(
                avatar,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(260.dp).clip(RoundedCornerShape(32.dp)),
            )
            Spacer(Modifier.height(24.dp))
        }
        Text(name, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 28.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { buttons() }
    }
}

@Composable
private fun ActiveCall(call: CallUi.Active, viewModel: CallController) {
    // read all three right away: a state the && below skips would never trigger the next recomposition,
    // and the callee sees this screen before its room exists
    val room = viewModel.callSession.currentRoom.collectAsState().value
    val remote = viewModel.callSession.remoteVideo.collectAsState().value
    val local = viewModel.callSession.localVideo.collectAsState().value

    Box(Modifier.fillMaxSize()) {
        if (room != null && remote != null) {
            VideoView(room, remote, Modifier.fillMaxSize(), mirror = false)
        } else {
            Text(
                "${call.name} — Bild kommt gleich …",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 28.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (room != null && local != null) {
            VideoView(
                room,
                local,
                Modifier.align(Alignment.TopEnd).padding(24.dp).size(width = 200.dp, height = 150.dp).clip(RoundedCornerShape(16.dp)),
                mirror = true,
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
            BigButton("Auflegen", Danger, viewModel::hangUp)
        }
    }
}

/** A fresh renderer per track: LiveKit renderers are released with their track. */
@Composable
private fun VideoView(room: Room, track: VideoTrack, modifier: Modifier, mirror: Boolean) = key(track) {
    val holder = remember { arrayOfNulls<TextureViewRenderer>(1) }
    AndroidView(
        factory = { context ->
            TextureViewRenderer(context).also { renderer ->
                room.initVideoRenderer(renderer)
                renderer.setMirror(mirror)
                track.addRenderer(renderer)
                holder[0] = renderer
            }
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.let { renderer ->
                track.removeRenderer(renderer)
                renderer.release()
            }
        }
    }
}

@Composable
private fun BigButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = CircleShape,
        modifier = Modifier.height(88.dp),
        contentPadding = PaddingValues(horizontal = 48.dp),
    ) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}
