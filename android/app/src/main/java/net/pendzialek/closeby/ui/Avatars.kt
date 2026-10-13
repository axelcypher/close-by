package net.pendzialek.closeby.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import net.pendzialek.closeby.api.FrameContact
import net.pendzialek.closeby.data.Images

@Composable
fun rememberAvatar(contact: FrameContact, images: Images): ImageBitmap? =
    rememberAvatar(contact.personId, contact.avatarVersion, images)

@Composable
fun rememberAvatar(personId: String?, version: Long?, images: Images): ImageBitmap? =
    rememberPicture(personId?.let { Images.personPicture(it, version) }, images)

/** [path] from [Images.personPicture] or [Images.framePicture]; null shows nothing. */
@Composable
fun rememberPicture(path: String?, images: Images): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { images.picture(it)?.asImageBitmap() }
    }
    return bitmap
}

/** Round picture of a person, the first letter of the name while there is none. */
@Composable
fun PersonAvatar(personId: String, version: Long?, name: String, size: Dp, images: Images, modifier: Modifier = Modifier) =
    RoundPicture(Images.personPicture(personId, version), name, size, images, modifier)

/** The frame's own picture, as the persons it calls see it. */
@Composable
fun FrameAvatar(frameId: String, version: Long?, name: String, size: Dp, images: Images, modifier: Modifier = Modifier) =
    RoundPicture(Images.framePicture(frameId, version), name, size, images, modifier)

@Composable
fun RoundPicture(path: String?, name: String, size: Dp, images: Images, modifier: Modifier = Modifier) {
    val avatar = rememberPicture(path, images)
    Box(modifier.size(size).clip(CircleShape).background(Paper), contentAlignment = Alignment.Center) {
        if (avatar != null) {
            Image(avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1), fontSize = (size.value * 0.39f).sp, color = Accent)
        }
    }
}
