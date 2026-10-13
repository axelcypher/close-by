package net.pendzialek.closeby

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.pendzialek.closeby.api.AuthResponse
import net.pendzialek.closeby.api.DeviceInfo
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.DeviceUpdate
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameAdmin
import net.pendzialek.closeby.api.FrameCommandRequest
import net.pendzialek.closeby.api.FrameCommands
import net.pendzialek.closeby.api.FrameConfig
import net.pendzialek.closeby.api.FrameContact
import net.pendzialek.closeby.api.FrameContactEntry
import net.pendzialek.closeby.api.FrameRef
import net.pendzialek.closeby.api.FrameUpdate
import net.pendzialek.closeby.api.InviteResponse
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.PairingClaimRequest
import net.pendzialek.closeby.api.PairingClaimResponse
import net.pendzialek.closeby.api.PairingStartRequest
import net.pendzialek.closeby.api.PairingStartResponse
import net.pendzialek.closeby.api.PairingState
import net.pendzialek.closeby.api.PairingStatus
import net.pendzialek.closeby.api.PersonAdmin
import net.pendzialek.closeby.api.PersonInfo
import net.pendzialek.closeby.api.PersonUpdate
import net.pendzialek.closeby.api.PushTokenRequest
import net.pendzialek.closeby.api.RegisterRequest
import net.pendzialek.closeby.api.Slideshow
import net.pendzialek.closeby.api.StartCallRequest
import net.pendzialek.closeby.api.StatusReport
import net.pendzialek.closeby.api.UpdateMeRequest
import java.io.File
import kotlin.time.Duration.Companion.seconds

const val DEVICE_AUTH = "device"

private const val PAIRING_TTL_MS = 10 * 60 * 1000L
private const val INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
private const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
private const val MAX_PHOTO_BYTES = 30 * 1024 * 1024
private const val LAST_SEEN_THROTTLE_MS = 30_000L

data class DevicePrincipal(val device: Device)

suspend fun Services.authenticate(token: String): DevicePrincipal? {
    val device = store.deviceByTokenHash(Secrets.sha256(token)) ?: return null
    if (device.lastSeen == null || store.now() - device.lastSeen > LAST_SEEN_THROTTLE_MS) store.touch(device.id)
    return DevicePrincipal(device)
}

fun Application.routes(services: Services) = routing {
    get("/health") { call.respondText("ok") }
    publicRoutes(services)
    realtime(services)
    authenticate(DEVICE_AUTH) {
        deviceRoutes(services)
        frameRoutes(services)
        callRoutes(services)
        route("/api/admin") { adminRoutes(services) }
    }
    val web = File(services.config.webDir)
    if (web.isDirectory) {
        staticFiles("/", web) {
            // Vite fingerprints assets/; the page is revalidated, so a release shows on the next load
            cacheControl { file ->
                if (file.parentFile?.name == "assets") listOf(CacheControl.MaxAge(maxAgeSeconds = 365 * 86_400)) else listOf(CacheControl.NoCache(null))
            }
            // unknown paths get the page, like default("index.html"), but uncached as well
            fallback { _, call ->
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondFile(web.resolve("index.html"))
            }
        }
    }
}

// ---- public: registration, pairing, app download ----

private fun Route.publicRoutes(services: Services) {
    val store = services.store
    val config = services.config

    post("/api/register") {
        val request = call.receive<RegisterRequest>()
        val personName = request.person.name.cleanName()
        val deviceName = request.device.name.cleanName()
        if (request.device.kind == DeviceKind.FRAME) badRequest("frames pair via /api/pairing/start")

        val isAdmin = when {
            request.adminCode != null -> {
                val expected = config.adminCode ?: forbidden()
                if (!Secrets.constantTimeEquals(request.adminCode!!, expected)) forbidden()
                true
            }
            request.code != null -> false
            else -> badRequest("code or adminCode required")
        }
        val token = Secrets.newToken()
        val (personId, deviceId) = store.registerPerson(
            personName = personName,
            isAdmin = isAdmin,
            deviceKind = request.device.kind,
            deviceName = deviceName,
            tokenHash = Secrets.sha256(token),
            inviteCode = request.code?.let(Secrets::normalizeCode).takeIf { !isAdmin },
        ) ?: forbidden()
        services.notifyAllFrames()
        call.respond(AuthResponse(token, deviceId, personId))
    }

    post("/api/pairing/start") {
        val request = call.receive<PairingStartRequest>()
        val pollSecret = Secrets.newToken()
        val response = PairingStartResponse(
            pairingId = Secrets.newId(),
            code = Secrets.newCode(8),
            pollSecret = pollSecret,
            expiresAt = store.now() + PAIRING_TTL_MS,
        )
        store.createPairing(
            response.pairingId, response.code, Secrets.sha256(pollSecret),
            request.kind, request.name.cleanName(), response.expiresAt,
        )
        call.respond(response)
    }

    get("/api/pairing/{id}") {
        val pairing = store.pairing(call.parameters["id"]!!) ?: notFound()
        val secret = call.request.headers["X-Poll-Secret"] ?: notFound()
        if (!Secrets.constantTimeEquals(Secrets.sha256(secret), pairing.pollHash)) notFound()
        val token = pairing.token
        if (token == null) {
            call.respond(PairingStatus(PairingState.PENDING))
        } else {
            // hand the token out exactly once
            store.deletePairing(pairing.id)
            call.respond(PairingStatus(PairingState.DONE, token, pairing.deviceId, pairing.personId))
        }
    }

    get("/api/app/android") { call.respond(services.releases.latest() ?: notFound()) }

    get(Releases.APK_PATH) {
        val apk = services.releases.apk
        if (!apk.isFile) notFound()
        call.response.headers.append("Content-Disposition", "attachment; filename=\"${Releases.APK_NAME}\"")
        call.respondFile(apk)
    }

    get("/download") { call.respondRedirect(Releases.APK_PATH) }

    get("/download/windows") {
        val installer = File(config.desktopDir, "CloseBy-setup.exe")
        if (!installer.isFile) notFound()
        call.response.headers.append("Content-Disposition", "attachment; filename=\"CloseBy-setup.exe\"")
        call.respondFile(installer)
    }

    // lets invite links open the app directly instead of the browser
    get("/.well-known/assetlinks.json") {
        val links = buildJsonArray {
            addJsonObject {
                putJsonArray("relation") { add("delegate_permission/common.handle_all_urls") }
                putJsonObject("target") {
                    put("namespace", "android_app")
                    put("package_name", "net.pendzialek.closeby")
                    putJsonArray("sha256_cert_fingerprints") { add(config.androidCertSha256) }
                }
            }
        }
        call.respondText(links.toString(), ContentType.Application.Json)
    }

    get("/invite/{code}") {
        val code = Secrets.normalizeCode(call.parameters["code"]!!)
        call.respondText(invitePage(code), ContentType.Text.Html)
    }
}

// ---- realtime ----

private fun Route.realtime(services: Services) {
    webSocket("/api/ws") {
        // browsers can't send headers on websockets: the token comes as first message
        val first = withTimeoutOrNull(10.seconds) { incoming.receiveCatching().getOrNull() } as? Frame.Text
        val auth = first?.let { runCatching { apiJson.decodeFromString(Event.serializer(), it.readText()) }.getOrNull() }
        val principal = auth?.takeIf { it.type == EventTypes.AUTH }?.token?.let { services.authenticate(it) }
        if (principal == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        val deviceId = principal.device.id
        if (services.hub.add(deviceId, this)) services.hub.broadcast(presence(deviceId, true))
        try {
            send(Frame.Text(apiJson.encodeToString(Event.serializer(), Event(EventTypes.READY))))
            for (frame in incoming) {
                // clients only listen; anything they send is ignored
            }
        } finally {
            // the session's coroutine is usually cancelled by now
            withContext(NonCancellable) {
                if (services.hub.remove(deviceId, this@webSocket)) services.hub.broadcast(presence(deviceId, false))
                services.store.touch(deviceId)
            }
        }
    }
}

// ---- every device ----

private fun Route.deviceRoutes(services: Services) {
    val store = services.store

    get("/api/me") {
        val device = call.device()
        val person = device.personId?.let { store.person(it) }
        call.respond(Me(services.deviceInfo(device), person?.toInfo()))
    }

    delete("/api/me") {
        // signing out revokes this device's token
        val device = call.device()
        store.deleteDevice(device.id)
        services.hub.disconnect(device.id)
        call.respond(HttpStatusCode.NoContent)
    }

    patch("/api/me") {
        val personId = call.device().personId ?: forbidden()
        store.updatePersonName(personId, call.receive<UpdateMeRequest>().name.cleanName())
        services.notifyFramesOf(personId)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/me/avatar") {
        val personId = call.device().personId ?: forbidden()
        store.setAvatar(personId, call.receiveAvatar())
        services.notifyFramesOf(personId)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/api/me/push") {
        store.setFcmToken(call.device().id, call.receive<PushTokenRequest>().fcmToken)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/me/status") {
        val report = call.receive<StatusReport>()
        store.setStatus(call.device().id, report.appVersion, report.status)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/persons/{id}/avatar") {
        val avatar = store.avatar(call.parameters["id"]!!) ?: notFound()
        // clients add ?v=<avatarVersion>, so a changed avatar is a new URL
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 86_400))
        call.respondBytes(avatar.bytes, ContentType.parse(avatar.contentType))
    }

    post("/api/pairing/claim") {
        val claimant = call.device()
        val claimantPersonId = claimant.personId ?: forbidden()
        val code = Secrets.normalizeCode(call.receive<PairingClaimRequest>().code)
        val pairing = store.pairingByCode(code) ?: notFound()
        if (pairing.kind == DeviceKind.FRAME && store.person(claimantPersonId)?.isAdmin != true) forbidden()
        // a frame belongs to nobody; any other device joins the person who scanned it
        val personId = if (pairing.kind == DeviceKind.FRAME) null else claimantPersonId
        val deviceId = store.completePairing(pairing, personId, Secrets.newToken(), claimantPersonId)
            ?: throw ApiException(HttpStatusCode.Conflict, "already claimed")
        call.respond(PairingClaimResponse(deviceId, pairing.kind))
    }

    post("/api/frames/{id}/photos") {
        val personId = call.device().personId ?: forbidden()
        val frame = store.framesOfPerson(personId).find { it.deviceId == call.parameters["id"] } ?: notFound()
        val albumId = frame.albumId ?: throw ApiException(HttpStatusCode.Conflict, "no album")
        val type = call.request.contentType().withoutParameters()
        if (type.contentType != "image") badRequest("only images")
        val bytes = call.receive<ByteArray>()
        if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES) badRequest("photo must be 1 byte to 30 MB")
        val extension = type.contentSubtype.substringAfter('+').ifBlank { "jpg" }
        services.photos.add(albumId, bytes, type.toString(), "closeby-${store.now()}.$extension")
        services.hub.send(frame.deviceId, Event(EventTypes.CONFIG_CHANGED))
        call.respond(HttpStatusCode.NoContent)
    }

    get("/api/frames") {
        val personId = call.device().personId ?: forbidden()
        call.respond(store.framesOfPerson(personId).map { FrameRef(it.deviceId, it.name, services.hub.isOnline(it.deviceId)) })
    }
}

// ---- the frame itself ----

private fun Route.frameRoutes(services: Services) {
    val store = services.store

    get("/api/frame/config") {
        val frame = call.frame(services)
        val contacts = store.frameContacts(frame.deviceId).map {
            FrameContact(it.person.id, it.person.shownName, it.person.avatarVersion, it.autoAnswer)
        }
        call.respond(FrameConfig(frame.deviceId, frame.name, frame.settings, contacts))
    }

    get("/api/frame/slideshow") {
        val albumId = call.frame(services).albumId
        call.respond(Slideshow(albumId?.let { services.photos.slideshow(it) } ?: emptyList()))
    }

    get("/api/photos/{assetId}") {
        val albumId = call.frame(services).albumId ?: notFound()
        val bytes = services.photos.preview(albumId, call.parameters["assetId"]!!) ?: notFound()
        call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 7 * 86_400))
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
}

// ---- calls ----

private fun Route.callRoutes(services: Services) {
    post("/api/calls") { call.respond(services.calls.start(call.device(), call.receive<StartCallRequest>())) }
    post("/api/calls/{id}/accept") { call.respond(services.calls.accept(call.device(), call.parameters["id"]!!)) }
    post("/api/calls/{id}/decline") {
        services.calls.decline(call.device(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
    post("/api/calls/{id}/end") {
        services.calls.end(call.device(), call.parameters["id"]!!)
        call.respond(HttpStatusCode.NoContent)
    }
}

// ---- admin ----

private fun Route.adminRoutes(services: Services) {
    val store = services.store

    get("/frames") {
        call.requireAdmin(services)
        call.respond(
            store.frames().map { frame ->
                FrameAdmin(
                    device = services.deviceInfo(store.device(frame.deviceId) ?: notFound()),
                    albumId = frame.albumId,
                    settings = frame.settings,
                    contacts = store.frameContacts(frame.deviceId).map { FrameContactEntry(it.person.id, it.autoAnswer) },
                )
            },
        )
    }

    put("/frames/{id}") {
        call.requireAdmin(services)
        val frame = store.frame(call.parameters["id"]!!) ?: notFound()
        val update = call.receive<FrameUpdate>()
        store.updateFrame(frame.deviceId, update.name.cleanName(), update.albumId?.takeIf { it.isNotBlank() }, update.settings)
        services.notifyFrame(frame.deviceId)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/frames/{id}/contacts") {
        call.requireAdmin(services)
        val frame = store.frame(call.parameters["id"]!!) ?: notFound()
        val contacts = call.receive<List<FrameContactEntry>>()
        val known = store.persons().map { it.id }.toSet()
        if (contacts.any { it.personId !in known }) badRequest("unknown person")
        if (contacts.map { it.personId }.toSet().size != contacts.size) badRequest("duplicate person")
        store.setFrameContacts(frame.deviceId, contacts)
        services.notifyFrame(frame.deviceId)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/frames/{id}/command") {
        call.requireAdmin(services)
        val frame = store.frame(call.parameters["id"]!!) ?: notFound()
        val command = call.receive<FrameCommandRequest>().command
        if (command !in FrameCommands.all) badRequest("unknown command")
        val delivered = services.hub.send(frame.deviceId, Event(EventTypes.FRAME_COMMAND, command = command))
        if (!delivered) throw ApiException(HttpStatusCode.Conflict, "frame offline")
        call.respond(HttpStatusCode.NoContent)
    }

    get("/persons") {
        call.requireAdmin(services)
        call.respond(
            store.persons().map { person ->
                PersonAdmin(person.toInfo(), store.devicesOfPerson(person.id).map { services.deviceInfo(it) })
            },
        )
    }

    put("/persons/{id}") {
        call.requireAdmin(services)
        val person = store.person(call.parameters["id"]!!) ?: notFound()
        val update = call.receive<PersonUpdate>()
        if (person.isAdmin && !update.isAdmin && store.adminCount() <= 1) badRequest("last admin")
        store.updatePersonAdmin(person.id, update.displayName?.cleanName()?.takeIf { it.isNotEmpty() }, update.isAdmin)
        services.notifyFramesOf(person.id)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/persons/{id}") {
        call.requireAdmin(services)
        val person = store.person(call.parameters["id"]!!) ?: notFound()
        if (person.isAdmin && store.adminCount() <= 1) badRequest("last admin")
        val frames = store.framesOfPerson(person.id)
        val devices = store.devicesOfPerson(person.id)
        store.deletePerson(person.id)
        devices.forEach { services.hub.disconnect(it.id) }
        frames.forEach { services.notifyFrame(it.deviceId) }
        call.respond(HttpStatusCode.NoContent)
    }

    /** The frame shows this picture; the person may still replace it on their own device. */
    put("/persons/{id}/avatar") {
        call.requireAdmin(services)
        val person = store.person(call.parameters["id"]!!) ?: notFound()
        store.setAvatar(person.id, call.receiveAvatar())
        services.notifyFramesOf(person.id)
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/devices/{id}") {
        call.requireAdmin(services)
        val device = store.device(call.parameters["id"]!!) ?: notFound()
        store.deleteDevice(device.id)
        services.hub.disconnect(device.id)
        call.respond(HttpStatusCode.NoContent)
    }

    put("/devices/{id}") {
        call.requireAdmin(services)
        val device = store.device(call.parameters["id"]!!) ?: notFound()
        val from = device.personId ?: badRequest("frames belong to nobody")
        val to = store.person(call.receive<DeviceUpdate>().personId) ?: notFound()
        // admin rights only carry over when the old person disappears
        val keepsAdmin = to.isAdmin || store.devicesOfPerson(from).size == 1
        if (device.id == call.device().id && !keepsAdmin) badRequest("you would lose admin")
        if (to.id != from) {
            val merged = store.moveDevice(device.id, from, to.id)
            // the device's own profile changed; frames only if a person disappeared
            services.hub.send(device.id, Event(EventTypes.CONFIG_CHANGED))
            if (merged) services.notifyFramesOf(to.id)
        }
        call.respond(HttpStatusCode.NoContent)
    }

    post("/invites") {
        val admin = call.requireAdmin(services)
        val code = Secrets.newCode(10)
        val expiresAt = store.now() + INVITE_TTL_MS
        store.createInvite(admin.id, code, expiresAt)
        call.respond(InviteResponse(code, "${services.config.publicUrl}/invite/$code", expiresAt))
    }

    get("/immich/albums") {
        call.requireAdmin(services)
        call.respond(services.photos.albums())
    }
}

// ---- helpers ----

private val AVATAR_TYPES = setOf(ContentType.Image.JPEG, ContentType.Image.PNG, ContentType.parse("image/webp"))

private fun ApplicationCall.device(): Device = principal<DevicePrincipal>()!!.device

private suspend fun ApplicationCall.frame(services: Services): PhotoFrame {
    val device = device()
    if (device.kind != DeviceKind.FRAME) forbidden()
    return services.store.frame(device.id) ?: notFound()
}

private suspend fun ApplicationCall.requireAdmin(services: Services): Person {
    val personId = device().personId ?: forbidden()
    return services.store.person(personId)?.takeIf { it.isAdmin } ?: forbidden()
}

private suspend fun ApplicationCall.receiveAvatar(): Avatar {
    val type = request.contentType().withoutParameters()
    if (type !in AVATAR_TYPES) badRequest("avatar must be jpeg, png or webp")
    val bytes = receive<ByteArray>()
    if (bytes.isEmpty() || bytes.size > MAX_AVATAR_BYTES) badRequest("avatar must be 1 byte to 2 MB")
    return Avatar(bytes, type.toString())
}

private fun String.cleanName(): String {
    val name = trim().replace(Regex("\\s+"), " ")
    if (name.isEmpty() || name.length > 60) badRequest("name must be 1 to 60 characters")
    return name
}

private fun Person.toInfo() = PersonInfo(id, name, displayName, isAdmin, avatarVersion)

private fun presence(deviceId: String, online: Boolean) =
    Event(EventTypes.PRESENCE_CHANGED, deviceId = deviceId, online = online)

private fun Services.deviceInfo(device: Device) = DeviceInfo(
    id = device.id,
    kind = device.kind,
    name = device.name,
    personId = device.personId,
    appVersion = device.appVersion,
    lastSeen = device.lastSeen,
    online = hub.isOnline(device.id),
    status = device.status,
)

private suspend fun Services.notifyFrame(frameId: String) {
    hub.send(frameId, Event(EventTypes.CONFIG_CHANGED))
}

private suspend fun Services.notifyFramesOf(personId: String) {
    store.framesOfPerson(personId).forEach { notifyFrame(it.deviceId) }
}

private suspend fun Services.notifyAllFrames() {
    store.frames().forEach { notifyFrame(it.deviceId) }
}

private fun invitePage(code: String) = """
    <!doctype html>
    <html lang="de">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>CloseBy Einladung</title>
      <style>
        body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 3rem auto; padding: 0 1rem; line-height: 1.5; }
        code { font-size: 1.6rem; letter-spacing: .15em; }
        a.button { display: inline-block; padding: .8rem 1.2rem; background: #2f6f5e; color: #fff; border-radius: .5rem; text-decoration: none; }
      </style>
    </head>
    <body>
      <h1>Du bist zu CloseBy eingeladen</h1>
      <h2>Auf dem Handy</h2>
      <p>1. Installiere die App:</p>
      <p><a class="button" href="/download">CloseBy für Android herunterladen</a></p>
      <p>2. Öffne die App, wähle „Ich habe eine Einladung“ und gib diesen Code ein:</p>
      <p><code>$code</code></p>
      <h2>Am PC</h2>
      <p><a href="/?invite=$code">CloseBy im Browser einrichten</a></p>
    </body>
    </html>
""".trimIndent()
