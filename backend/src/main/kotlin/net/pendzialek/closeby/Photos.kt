package net.pendzialek.closeby

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import net.pendzialek.closeby.api.ImmichAlbum
import net.pendzialek.closeby.api.SlideshowPhoto
import java.io.File

interface PhotoSource {
    suspend fun albums(): List<ImmichAlbum>
    suspend fun albumPhotos(albumId: String): List<SlideshowPhoto>
    suspend fun preview(assetId: String): ByteArray

    /** Uploads an image and adds it to the album; needs `asset.upload` and `albumAsset.create`. */
    suspend fun addToAlbum(albumId: String, image: ByteArray, contentType: String, fileName: String)
}

class PhotoSourceException(message: String) : Exception(message)

object NoPhotoSource : PhotoSource {
    override suspend fun albums() = throw PhotoSourceException("Immich is not configured")
    override suspend fun albumPhotos(albumId: String) = throw PhotoSourceException("Immich is not configured")
    override suspend fun preview(assetId: String) = throw PhotoSourceException("Immich is not configured")
    override suspend fun addToAlbum(albumId: String, image: ByteArray, contentType: String, fileName: String) =
        throw PhotoSourceException("Immich is not configured")
}

/**
 * Talks to Immich with its own HTTP client. When a request fails for network
 * reasons the client is replaced: a connection pool or address resolved while
 * Immich was still down must not stick until the backend restarts.
 */
class ImmichPhotoSource(
    private val baseUrl: String,
    private val apiKey: String,
    private val newClient: () -> HttpClient,
) : PhotoSource {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var http = newClient()

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: PhotoSourceException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val broken = http
        http = newClient()
        broken.close()
        throw PhotoSourceException("Immich unreachable: ${e::class.simpleName} ${e.message}")
    }

    @Serializable
    private data class AlbumDto(
        val id: String,
        val albumName: String,
        val assetCount: Int = 0,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(val id: String, val type: String = "IMAGE", val fileCreatedAt: String? = null)

    @Serializable
    private data class SearchResponse(val assets: SearchAssets)

    @Serializable
    private data class SearchAssets(val items: List<AssetDto> = emptyList(), val nextPage: String? = null)

    override suspend fun albums(): List<ImmichAlbum> = guarded {
        decode<List<AlbumDto>>(http.get("$baseUrl/api/albums") { header("x-api-key", apiKey) })
            .map { ImmichAlbum(it.id, it.albumName, it.assetCount) }
    }

    override suspend fun albumPhotos(albumId: String): List<SlideshowPhoto> = guarded {
        val album = decode<AlbumDto>(
            http.get("$baseUrl/api/albums/$albumId") {
                header("x-api-key", apiKey)
                parameter("withoutAssets", false)
            },
        )
        // newer Immich versions may leave out the asset list; search by album then
        val assets = album.assets.ifEmpty { if (album.assetCount > 0) searchAlbum(albumId) else emptyList() }
        assets.filter { it.type == "IMAGE" }.map { SlideshowPhoto(it.id, it.fileCreatedAt) }
    }

    private suspend fun searchAlbum(albumId: String): List<AssetDto> = buildList {
        var page: String? = "1"
        while (page != null) {
            val response = decode<SearchResponse>(
                http.post("$baseUrl/api/search/metadata") {
                    header("x-api-key", apiKey)
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            putJsonArray("albumIds") { add(albumId) }
                            put("type", "IMAGE")
                            put("size", 1000)
                            put("page", page.toInt())
                        }.toString(),
                    )
                },
            )
            addAll(response.assets.items)
            page = response.assets.nextPage
        }
    }

    override suspend fun preview(assetId: String): ByteArray = guarded {
        val response = http.get("$baseUrl/api/assets/$assetId/thumbnail") {
            header("x-api-key", apiKey)
            parameter("size", "preview")
        }
        if (!response.status.isSuccess()) throw PhotoSourceException("Immich preview $assetId: ${response.status}")
        response.body<ByteArray>()
    }

    @Serializable
    private data class UploadResult(val id: String)

    override suspend fun addToAlbum(albumId: String, image: ByteArray, contentType: String, fileName: String) = guarded {
        val now = java.time.Instant.now().toString()
        val uploaded = decode<UploadResult>(
            http.submitFormWithBinaryData(
                url = "$baseUrl/api/assets",
                formData = formData {
                    // Immich dedups per device by this id; content hash keeps re-shares idempotent
                    append("deviceAssetId", "closeby-${Secrets.sha256(image).take(32)}")
                    append("deviceId", "closeby")
                    append("fileCreatedAt", now)
                    append("fileModifiedAt", now)
                    append(
                        "assetData", image,
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            ) { header("x-api-key", apiKey) },
        )
        val added = http.put("$baseUrl/api/albums/$albumId/assets") {
            header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { putJsonArray("ids") { add(uploaded.id) } }.toString())
        }
        if (!added.status.isSuccess()) throw PhotoSourceException("Immich album add: ${added.status} ${added.bodyAsText().take(200)}")
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw PhotoSourceException("Immich ${response.status}: ${text.take(200)}")
        return json.decodeFromString(text)
    }
}

/**
 * Serves a frame's album: the asset list is cached for a few minutes, preview
 * images on disk. Frames may only fetch assets that are in their album.
 */
class PhotoService(
    private val source: PhotoSource,
    cacheDir: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val previews = File(cacheDir, "previews").apply { mkdirs() }
    private val albums = mutableMapOf<String, Pair<Long, List<SlideshowPhoto>>>()
    private val mutex = Mutex()

    suspend fun albums() = source.albums()

    suspend fun slideshow(albumId: String): List<SlideshowPhoto> = mutex.withLock {
        val cached = albums[albumId]
        if (cached != null && clock() - cached.first < ALBUM_TTL_MS) return cached.second
        val photos = source.albumPhotos(albumId)
        albums[albumId] = clock() to photos
        photos
    }

    /** Adds a shared photo to the album and shows it on the next slideshow load. */
    suspend fun add(albumId: String, image: ByteArray, contentType: String, fileName: String) {
        source.addToAlbum(albumId, image, contentType, fileName)
        mutex.withLock { albums.remove(albumId) }
    }

    suspend fun preview(albumId: String, assetId: String): ByteArray? {
        if (slideshow(albumId).none { it.id == assetId }) return null
        // asset ids are UUIDs; anything else must never reach the file system
        if (!assetId.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        val file = File(previews, "$assetId.jpg")
        if (file.exists()) return withContext(Dispatchers.IO) { file.readBytes() }
        val bytes = source.preview(assetId)
        withContext(Dispatchers.IO) {
            val partial = File(previews, "$assetId.part")
            partial.writeBytes(bytes)
            partial.renameTo(file)
        }
        return bytes
    }

    private companion object {
        const val ALBUM_TTL_MS = 5 * 60 * 1000L
    }
}
