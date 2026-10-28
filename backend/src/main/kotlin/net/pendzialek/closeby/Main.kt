package net.pendzialek.closeby

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.pendzialek.closeby.api.ApiError
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.seconds

class Services(
    val config: Config,
    val store: Store,
    val hub: Hub,
    val calls: Calls,
    val photos: PhotoService,
    val releases: Releases,
) {
    companion object {
        fun create(
            config: Config,
            push: Push? = null,
            rooms: Rooms? = null,
            photoSource: PhotoSource? = null,
            clock: () -> Long = System::currentTimeMillis,
        ): Services {
            val http by lazy { HttpClient(CIO) }
            val store = Store(Db(config.databasePath), clock)
            val hub = Hub()
            val resolvedPush = push
                ?: config.firebaseServiceAccountB64?.let { FcmPush(it, http) }
                ?: NoPush
            val resolvedRooms = rooms ?: LiveKitRooms(config, http, clock)
            val source = photoSource
                ?: if (config.immichUrl != null && config.immichApiKey != null) {
                    ImmichPhotoSource(config.immichUrl, config.immichApiKey) {
                        HttpClient(CIO) {
                            install(HttpTimeout) {
                                connectTimeoutMillis = 5_000
                                // uploads of phone photos may take a while
                                requestTimeoutMillis = 60_000
                            }
                        }
                    }
                } else {
                    NoPhotoSource
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return Services(
                config = config,
                store = store,
                hub = hub,
                calls = Calls(config, store, hub, resolvedPush, resolvedRooms, scope),
                photos = PhotoService(source, config.cacheDir, clock),
                releases = Releases(config),
            )
        }
    }
}

val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

fun main() {
    val config = Config.fromEnv()
    val services = Services.create(config)
    embeddedServer(Netty, port = config.port) { closeby(services) }.start(wait = true)
}

fun Application.closeby(services: Services) {
    install(XForwardedHeaders)
    install(CallLogging) {
        level = Level.INFO
        // the frame polls photos all day; keep the log readable
        filter { call -> !call.request.path().startsWith("/api/photos/") && call.request.path() != "/health" }
    }
    install(ContentNegotiation) { json(apiJson) }
    install(RequestBodyLimit) { bodyLimit { call -> requestBodyLimit(call.request.httpMethod, call.request.path()) } }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 30.seconds
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.message ?: "error")) }
        exception<PayloadTooLargeException> { call, _ -> call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too large")) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad request")) }
        exception<PhotoSourceException> { call, e ->
            call.application.log.warn("photo source: {}", e.message)
            call.respond(HttpStatusCode.BadGateway, ApiError("photos unavailable"))
        }
    }
    install(Authentication) {
        bearer(DEVICE_AUTH) {
            authenticate { credential -> services.authenticate(credential.token) }
        }
    }
    routes(services)
}
