package net.pendzialek.closeby.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCode(value: String, modifier: Modifier = Modifier) {
    val bitmap = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
                setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }.asImageBitmap()
    }
    // a tiny matrix scaled up: keep the pixels sharp
    Image(bitmap, contentDescription = "QR-Code", modifier = modifier, filterQuality = FilterQuality.None)
}
