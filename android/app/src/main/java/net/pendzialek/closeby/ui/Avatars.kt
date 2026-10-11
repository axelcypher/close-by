package net.pendzialek.closeby.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import net.pendzialek.closeby.api.FrameContact
import net.pendzialek.closeby.data.Images

@Composable
fun rememberAvatar(contact: FrameContact, images: Images): ImageBitmap? =
    rememberAvatar(contact.personId, contact.avatarVersion, images)

@Composable
fun rememberAvatar(personId: String?, version: Long?, images: Images): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, personId, version) {
        value = if (personId != null && version != null) images.avatar(personId, version)?.asImageBitmap() else null
    }
    return bitmap
}
