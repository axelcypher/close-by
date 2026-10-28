package net.pendzialek.closeby.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The websocket to the backend, reconnecting with backoff for as long as
 * [start] is in effect. Server events come out of [events]. All state lives on
 * [scope]'s thread (main); OkHttp's callbacks hop over there first.
 */
class Realtime(
    private val prefs: Prefs,
    private val api: Api,
    private val scope: CoroutineScope,
    /** The server refused our token; it may have been revoked, the app checks that. */
    private val onRevoked: () -> Unit,
) {
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var socket: WebSocket? = null
    private var running = false
    private var retryMs = 1_000L
    private var retryJob: Job? = null

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        retryJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _connected.value = false
    }

    private fun connect() {
        val token = prefs.token ?: return
        val url = prefs.server.replaceFirst("http", "ws") + "/api/ws"
        val client = api.client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
        socket = client.newWebSocket(
            okhttp3.Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(json.encodeToString(Event.serializer(), Event(EventTypes.AUTH, token = token)))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val event = runCatching { json.decodeFromString(Event.serializer(), text) }.getOrNull() ?: return
                    scope.launch {
                        if (webSocket != socket) return@launch
                        if (event.type == EventTypes.READY) {
                            _connected.value = true
                            retryMs = 1_000
                        } else {
                            _events.tryEmit(event)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    scope.launch {
                        // unknown token; the app makes sure before it signs out, until then we keep trying
                        if (code == 1008 && webSocket == socket) onRevoked()
                        lost(webSocket)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    scope.launch { lost(webSocket) }
                }
            },
        )
    }

    private fun lost(webSocket: WebSocket) {
        if (webSocket != socket) return
        socket = null
        _connected.value = false
        if (!running) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(retryMs)
            retryMs = (retryMs * 2).coerceAtMost(15_000)
            if (running) connect()
        }
    }
}
