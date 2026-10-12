package net.pendzialek.closeby

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface Rooms {
    /** Access token for [identity] to join [room]. */
    fun joinToken(room: String, identity: String, name: String): String

    /** Disconnects everyone; best effort. */
    suspend fun close(room: String)

    /** Participants in [room] right now; null if LiveKit could not be asked. */
    suspend fun participantCount(room: String): Int?
}

/** LiveKit access tokens are HS256 JWTs signed with the API secret. */
class LiveKitRooms(
    private val config: Config,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : Rooms {
    private val log = LoggerFactory.getLogger(LiveKitRooms::class.java)

    override fun joinToken(room: String, identity: String, name: String): String = sign(
        buildJsonObject {
            put("sub", identity)
            put("name", name)
            putJsonObject("video") {
                put("roomJoin", true)
                put("room", room)
                put("canPublish", true)
                put("canSubscribe", true)
            }
        },
    )

    override suspend fun close(room: String) {
        val token = sign(buildJsonObject { putJsonObject("video") { put("roomCreate", true) } })
        runCatching {
            http.post("${config.livekitInternalUrl}/twirp/livekit.RoomService/DeleteRoom") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("room", room) }.toString())
            }
        }.onFailure { log.warn("closing room {} failed: {}", room, it.message) }
    }

    override suspend fun participantCount(room: String): Int? {
        val token = sign(
            buildJsonObject {
                putJsonObject("video") {
                    put("roomAdmin", true)
                    put("room", room)
                }
            },
        )
        return runCatching {
            val response = http.post("${config.livekitInternalUrl}/twirp/livekit.RoomService/ListParticipants") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("room", room) }.toString())
            }
            when {
                // an unknown room has nobody in it
                response.status == HttpStatusCode.NotFound -> 0
                !response.status.isSuccess() -> null
                else -> Json.parseToJsonElement(response.bodyAsText()).jsonObject["participants"]?.jsonArray?.size ?: 0
            }
        }.getOrElse {
            log.warn("listing participants of {} failed: {}", room, it.message)
            null
        }
    }

    private fun sign(claims: JsonObject): String {
        val now = clock() / 1000
        val payload = buildJsonObject {
            put("iss", config.livekitApiKey)
            put("nbf", now)
            put("exp", now + TOKEN_TTL_SECONDS)
            claims.forEach { (key, value) -> put(key, value) }
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val body = encoder.encodeToString(payload.toString().toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(config.livekitApiSecret.toByteArray(), "HmacSHA256"))
        }
        val signature = encoder.encodeToString(mac.doFinal("$header.$body".toByteArray()))
        return "$header.$body.$signature"
    }

    private companion object {
        const val TOKEN_TTL_SECONDS = 6 * 60 * 60
    }
}
