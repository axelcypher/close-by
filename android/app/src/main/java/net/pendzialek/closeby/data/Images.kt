package net.pendzialek.closeby.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads avatars and slideshow photos through the authenticated API.
 * Decoded bitmaps are kept in memory; the HTTP cache keeps the bytes on disk.
 */
class Images(private val api: Api, private val maxSize: Int) {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun avatar(personId: String, version: Long): Bitmap? =
        load("/api/persons/$personId/avatar?v=$version", targetSize = 768)

    suspend fun photo(assetId: String): Bitmap? = load("/api/photos/$assetId", targetSize = maxSize)

    private suspend fun load(path: String, targetSize: Int): Bitmap? {
        cache.get(path)?.let { return it }
        val bytes = runCatching { api.bytes(path) }.getOrNull() ?: return null
        val bitmap = withContext(Dispatchers.Default) { decode(bytes, targetSize) } ?: return null
        cache.put(path, bitmap)
        return bitmap
    }

    /** Decodes at the smallest power-of-two scale that still covers [targetSize]. */
    private fun decode(bytes: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSize) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
