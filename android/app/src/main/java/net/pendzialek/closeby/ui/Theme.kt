package net.pendzialek.closeby.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF2F6F5E)
val Paper = Color(0xFFF6F3EE)
val Ink = Color(0xFF22201C)
val Muted = Color(0xFF6B665D)
val Danger = Color(0xFFC0392B)
val Ok = Color(0xFF2F8F4E)

/** One calm, light look: the frame hangs in a living room, not in a dark IDE. */
@Composable
fun CloseByTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Paper,
            surface = Color.White,
            onBackground = Ink,
            onSurface = Ink,
            error = Danger,
        ),
        content = content,
    )
}
