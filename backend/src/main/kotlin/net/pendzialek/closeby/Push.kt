package net.pendzialek.closeby

import com.google.auth.oauth2.GoogleCredentials
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.Base64

enum class PushResult { SENT, UNREGISTERED, FAILED }

interface Push {
    /** High-priority data message; [data] arrives as-is in the app's messaging service. */
    suspend fun send(fcmToken: String, data: Map<String, String>): PushResult
}

object NoPush : Push {
    override suspend fun send(fcmToken: String, data: Map<String, String>) = PushResult.FAILED
}

/** Firebase Cloud Messaging HTTP v1 with a service account. */
class FcmPush(serviceAccountB64: String, private val http: HttpClient) : Push {
    private val log = LoggerFactory.getLogger(FcmPush::class.java)
    private val serviceAccount = Base64.getDecoder().decode(serviceAccountB64.trim())
    private val projectId = Json.parseToJsonElement(serviceAccount.decodeToString())
        .jsonObject.getValue("project_id").jsonPrimitive.content
    private val credentials = GoogleCredentials.fromStream(serviceAccount.inputStream())
        .createScoped("https://www.googleapis.com/auth/firebase.messaging")

    override suspend fun send(fcmToken: String, data: Map<String, String>): PushResult {
        val message = buildJsonObject {
            putJsonObject("message") {
                put("token", fcmToken)
                putJsonObject("data") { data.forEach { (key, value) -> put(key, value) } }
                putJsonObject("android") {
                    put("priority", "HIGH")
                    // a ring that arrives late is worse than none
                    put("ttl", "30s")
                }
            }
        }
        // a failing push must never fail the call itself
        return runCatching {
            val accessToken = withContext(Dispatchers.IO) {
                credentials.refreshIfExpired()
                credentials.accessToken?.tokenValue ?: error("no fcm access token")
            }
            val response = http.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(message.toString())
            }
            when {
                response.status.value in 200..299 -> PushResult.SENT
                response.status == HttpStatusCode.NotFound -> PushResult.UNREGISTERED
                else -> {
                    log.warn("fcm send failed: {} {}", response.status, response.bodyAsText().take(500))
                    PushResult.FAILED
                }
            }
        }.getOrElse {
            log.warn("fcm send failed: {}", it.message)
            PushResult.FAILED
        }
    }
}
