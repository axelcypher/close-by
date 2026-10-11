package net.pendzialek.closeby.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Phone photos are 5-15 MB; frames show at most a tablet screen. */
object Jpeg {
    /** Longest side at most [maxSide], EXIF rotation applied (ImageDecoder does that). */
    suspend fun scaled(context: Context, uri: Uri, maxSide: Int = 2560): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        compress(bitmap, 90)
    }

    /** Center square for avatars. */
    suspend fun square(context: Context, uri: Uri, side: Int = 512): ByteArray = withContext(Dispatchers.IO) {
        val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val edge = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - edge) / 2, (source.height - edge) / 2, edge, edge)
        compress(Bitmap.createScaledBitmap(cropped, side, side, true), 85)
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
