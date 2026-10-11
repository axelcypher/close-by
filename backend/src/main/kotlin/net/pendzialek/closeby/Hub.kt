package net.pendzialek.closeby

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.WebSocketSession
import kotlinx.serialization.json.Json
import net.pendzialek.closeby.api.Event
import java.util.concurrent.ConcurrentHashMap

/** Open websocket sessions per device. A device may hold several (e.g. two browser tabs). */
class Hub {
    private val json = Json { explicitNulls = false }
    private val sessions = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()

    fun add(deviceId: String, session: WebSocketSession) {
        sessions.computeIfAbsent(deviceId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    fun remove(deviceId: String, session: WebSocketSession) {
        sessions.computeIfPresent(deviceId) { _, set -> set.apply { remove(session) }.ifEmpty { null } }
    }

    /** Closes every session of a revoked device. */
    suspend fun disconnect(deviceId: String) {
        sessions.remove(deviceId)?.forEach { session ->
            runCatching { session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked")) }
        }
    }

    fun isOnline(deviceId: String) = sessions[deviceId]?.isNotEmpty() == true

    /** True if at least one session got the event. */
    suspend fun send(deviceId: String, event: Event): Boolean {
        val text = json.encodeToString(Event.serializer(), event)
        var delivered = false
        sessions[deviceId]?.forEach { session ->
            if (runCatching { session.send(Frame.Text(text)) }.isSuccess) delivered = true
        }
        return delivered
    }
}
