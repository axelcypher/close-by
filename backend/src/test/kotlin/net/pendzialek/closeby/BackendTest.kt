package net.pendzialek.closeby

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.pendzialek.closeby.api.AppRelease
import net.pendzialek.closeby.api.AuthResponse
import net.pendzialek.closeby.api.CallJoin
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.DeviceUpdate
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameAdmin
import net.pendzialek.closeby.api.FrameCommandRequest
import net.pendzialek.closeby.api.FrameCommands
import net.pendzialek.closeby.api.FrameConfig
import net.pendzialek.closeby.api.FrameContactEntry
import net.pendzialek.closeby.api.FrameRef
import net.pendzialek.closeby.api.FrameSettings
import net.pendzialek.closeby.api.FrameUpdate
import net.pendzialek.closeby.api.ImmichAlbum
import net.pendzialek.closeby.api.InviteResponse
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.NewDevice
import net.pendzialek.closeby.api.NewPerson
import net.pendzialek.closeby.api.PairingClaimRequest
import net.pendzialek.closeby.api.PairingStartRequest
import net.pendzialek.closeby.api.PairingStartResponse
import net.pendzialek.closeby.api.PairingState
import net.pendzialek.closeby.api.PairingStatus
import net.pendzialek.closeby.api.PersonAdmin
import net.pendzialek.closeby.api.PersonUpdate
import net.pendzialek.closeby.api.PushTokenRequest
import net.pendzialek.closeby.api.RegisterRequest
import net.pendzialek.closeby.api.Slideshow
import net.pendzialek.closeby.api.SlideshowPhoto
import net.pendzialek.closeby.api.StartCallRequest
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendTest {
    private class FakeRooms : Rooms {
        val closed = mutableListOf<String>()
        var participants: Int? = 2
        override suspend fun participantCount(room: String) = participants
        override fun joinToken(room: String, identity: String, name: String) = "token:$room:$identity"
        override suspend fun close(room: String) {
            closed += room
        }
    }

    /** Switches to IO like [FcmPush], so it fails the same way in a cancelled coroutine. */
    private class FakePush : Push {
        val sent = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        override suspend fun send(fcmToken: String, data: Map<String, String>): PushResult {
            withContext(Dispatchers.IO) { sent += fcmToken to data }
            return PushResult.SENT
        }
    }

    private class FakePhotos : PhotoSource {
        val added = mutableListOf<Pair<String, Int>>()
        var down = false
        var albumFetches = 0
        override suspend fun addToAlbum(albumId: String, image: ByteArray, contentType: String, fileName: String) {
            added += albumId to image.size
        }
        override suspend fun albums() = listOf(ImmichAlbum("album-1", "Familie", 2))
        override suspend fun albumPhotos(albumId: String): List<SlideshowPhoto> {
            albumFetches++
            if (down) throw PhotoSourceException("down")
            return listOf(
                SlideshowPhoto("11111111-1111-1111-1111-111111111111"),
                SlideshowPhoto("22222222-2222-2222-2222-222222222222"),
            )
        }
        override suspend fun preview(assetId: String): ByteArray {
            if (down) throw PhotoSourceException("down")
            return byteArrayOf(1, 2, 3)
        }
    }

    private val rooms = FakeRooms()
    private val push = FakePush()
    private val photos = FakePhotos()

    private fun test(
        web: (File) -> Unit = {},
        apk: (File) -> Unit = {},
        configure: Config.() -> Config = { this },
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val dir = Files.createTempDirectory("closeby-test").toFile()
        web(dir.resolve("web"))
        apk(dir.resolve("apk"))
        val config = Config(
            databasePath = dir.resolve("test.db").path,
            adminCode = "admin-secret",
            cacheDir = dir.resolve("cache").path,
            apkDir = dir.resolve("apk").path,
            webDir = dir.resolve("web").path,
            staleCallGraceMs = 0,
        ).configure()
        val services = Services.create(config, push = push, rooms = rooms, photoSource = photos)
        application { closeby(services) }
        val client = createClient {
            install(ContentNegotiation) { json(apiJson) }
            install(WebSockets)
        }
        block(client)
    }

    private suspend fun HttpClient.register(name: String, adminCode: String? = null, code: String? = null, kind: DeviceKind = DeviceKind.PHONE): HttpResponse =
        post("/api/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(code, adminCode, NewDevice(kind, "$name device"), NewPerson(name)))
        }

    /** Pushes and timeouts run on the server's own (real-time) scope. */
    private suspend fun <T> eventually(check: () -> T): T = withContext(Dispatchers.Default) {
        withTimeout(5_000) {
            var result = runCatching(check)
            while (result.isFailure) {
                delay(20)
                result = runCatching(check)
            }
            result.getOrThrow()
        }
    }

    private suspend fun HttpClient.admin(): AuthResponse = register("Tobias", adminCode = "admin-secret").body()

    /** Runs the whole frame pairing; returns the frame's credentials. */
    private suspend fun HttpClient.pairFrame(admin: AuthResponse): AuthResponse {
        val start: PairingStartResponse = post("/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(DeviceKind.FRAME, "Wohnzimmer Oma"))
        }.body()
        val claim = post("/api/pairing/claim") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code.lowercase().chunked(4).joinToString("-")))
        }
        assertEquals(HttpStatusCode.OK, claim.status)
        val status: PairingStatus = get("/api/pairing/${start.pairingId}") { header("X-Poll-Secret", start.pollSecret) }.body()
        assertEquals(PairingState.DONE, status.state)
        return AuthResponse(status.token!!, status.deviceId!!)
    }

    @Test
    fun `admin code registers an admin, a wrong one is refused`() = test { client ->
        assertEquals(HttpStatusCode.Forbidden, client.register("Mallory", adminCode = "guess").status)
        val admin = client.admin()
        val me = client.get("/api/me") { bearerAuth(admin.token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me") { bearerAuth("nope") }.status)
    }

    @Test
    fun `invites work once`() = test { client ->
        val admin = client.admin()
        val invite: InviteResponse = client.post("/api/admin/invites") { bearerAuth(admin.token) }.body()
        assertEquals(HttpStatusCode.OK, client.register("Lisa", code = invite.code).status)
        assertEquals(HttpStatusCode.Forbidden, client.register("Ben", code = invite.code).status)
        assertEquals(HttpStatusCode.Forbidden, client.register("Ben", code = "WRONGCODE1").status)
    }

    @Test
    fun `frame pairing hands the token out once and lists the admin as contact`() = test { client ->
        val admin = client.admin()
        val start: PairingStartResponse = client.post("/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(DeviceKind.FRAME, "Wohnzimmer Oma"))
        }.body()
        val pending: PairingStatus = client.get("/api/pairing/${start.pairingId}") { header("X-Poll-Secret", start.pollSecret) }.body()
        assertEquals(PairingState.PENDING, pending.state)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/pairing/${start.pairingId}") { header("X-Poll-Secret", "wrong") }.status,
        )

        client.post("/api/pairing/claim") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code))
        }
        val done: PairingStatus = client.get("/api/pairing/${start.pairingId}") { header("X-Poll-Secret", start.pollSecret) }.body()
        assertEquals(PairingState.DONE, done.state)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/pairing/${start.pairingId}") { header("X-Poll-Secret", start.pollSecret) }.status,
        )

        val config: FrameConfig = client.get("/api/frame/config") { bearerAuth(done.token!!) }.body()
        assertEquals("Wohnzimmer Oma", config.name)
        assertEquals(listOf(admin.personId), config.contacts.map { it.personId })
    }

    @Test
    fun `only admins claim frames`() = test { client ->
        val admin = client.admin()
        val invite: InviteResponse = client.post("/api/admin/invites") { bearerAuth(admin.token) }.body()
        val lisa: AuthResponse = client.register("Lisa", code = invite.code).body()
        val start: PairingStartResponse = client.post("/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(DeviceKind.FRAME, "Frame"))
        }.body()
        val claim = client.post("/api/pairing/claim") {
            bearerAuth(lisa.token)
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code))
        }
        assertEquals(HttpStatusCode.Forbidden, claim.status)
    }

    @Test
    fun `frame calls a person, the phone gets a push and the caller hears the accept`() = test { client ->
        val admin = client.admin()
        client.put("/api/me/push") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PushTokenRequest("fcm-phone"))
        }
        val frame = client.pairFrame(admin)

        val accepted = CompletableDeferred<Event>()
        val ready = CompletableDeferred<Unit>()
        coroutineScope {
            val listener = launchListener(client, frame.token, ready, accepted)
            withTimeout(5_000) { ready.await() }

            val join: CallJoin = client.post("/api/calls") {
                bearerAuth(frame.token)
                contentType(ContentType.Application.Json)
                setBody(StartCallRequest(personId = admin.personId))
            }.body()
            assertEquals("token:${join.callId}:${frame.deviceId}", join.token)
            val incoming = eventually { push.sent.single() }
            assertEquals("fcm-phone", incoming.first)
            assertEquals(EventTypes.CALL_INCOMING, incoming.second["type"])
            assertEquals("Wohnzimmer Oma", incoming.second["fromName"])

            val busy = client.post("/api/calls") {
                bearerAuth(frame.token)
                contentType(ContentType.Application.Json)
                setBody(StartCallRequest(personId = admin.personId))
            }
            assertEquals(HttpStatusCode.Conflict, busy.status)

            val answer: CallJoin = client.post("/api/calls/${join.callId}/accept") { bearerAuth(admin.token) }.body()
            assertEquals(join.callId, answer.callId)
            assertEquals(EventTypes.CALL_ACCEPTED, withTimeout(5_000) { accepted.await() }.type)

            assertEquals(HttpStatusCode.NoContent, client.post("/api/calls/${join.callId}/end") { bearerAuth(admin.token) }.status)
            assertEquals(listOf(join.callId), rooms.closed)
            listener.cancel()
        }
    }

    @Test
    fun `an answered call with an empty room no longer blocks new calls`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val call: suspend () -> HttpResponse = {
            client.post("/api/calls") {
                bearerAuth(frame.token)
                contentType(ContentType.Application.Json)
                setBody(StartCallRequest(personId = admin.personId))
            }
        }
        val first: CallJoin = call().body()
        client.post("/api/calls/${first.callId}/accept") { bearerAuth(admin.token) }

        // both still in the room: busy
        rooms.participants = 2
        assertEquals(HttpStatusCode.Conflict, call().status)
        // both clients vanished without hanging up: the stale call is reaped
        rooms.participants = 0
        assertEquals(HttpStatusCode.OK, call().status)
        assertTrue(first.callId in rooms.closed)
    }

    @Test
    fun `persons call frames they are a contact of`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val invite: InviteResponse = client.post("/api/admin/invites") { bearerAuth(admin.token) }.body()
        val lisa: AuthResponse = client.register("Lisa", code = invite.code).body()

        // new persons are added to existing frames
        val call = client.post("/api/calls") {
            bearerAuth(lisa.token)
            contentType(ContentType.Application.Json)
            setBody(StartCallRequest(frameId = frame.deviceId))
        }
        assertEquals(HttpStatusCode.OK, call.status)
        val join: CallJoin = call.body()
        // the frame may decline, the caller may not accept its own call
        assertEquals(HttpStatusCode.NotFound, client.post("/api/calls/${join.callId}/accept") { bearerAuth(lisa.token) }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/api/calls/${join.callId}/decline") { bearerAuth(frame.token) }.status)

        client.put("/api/admin/frames/${frame.deviceId}/contacts") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody("[]")
        }
        val hidden = client.post("/api/calls") {
            bearerAuth(lisa.token)
            contentType(ContentType.Application.Json)
            setBody(StartCallRequest(frameId = frame.deviceId))
        }
        assertEquals(HttpStatusCode.NotFound, hidden.status)
    }

    @Test
    fun `frames only get photos of their own album`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val empty: Slideshow = client.get("/api/frame/slideshow") { bearerAuth(frame.token) }.body()
        assertTrue(empty.photos.isEmpty())

        client.put("/api/admin/frames/${frame.deviceId}") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(FrameUpdate("Wohnzimmer Oma", "album-1", FrameSettings(slideshowIntervalSeconds = 20)))
        }
        val frames: List<FrameAdmin> = client.get("/api/admin/frames") { bearerAuth(admin.token) }.body()
        assertEquals("album-1", frames.single().albumId)

        val slideshow: Slideshow = client.get("/api/frame/slideshow") { bearerAuth(frame.token) }.body()
        assertEquals(2, slideshow.photos.size)
        val photo = client.get("/api/photos/${slideshow.photos.first().id}") { bearerAuth(frame.token) }
        assertEquals(HttpStatusCode.OK, photo.status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/photos/33333333-3333-3333-3333-333333333333") { bearerAuth(frame.token) }.status,
        )
        // a phone is not a frame
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/frame/slideshow") { bearerAuth(admin.token) }.status)
    }

    @Test
    fun `devices learn when a frame comes online`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val presence = CompletableDeferred<Event>()
        coroutineScope {
            val listener = launch {
                client.webSocket("/api/ws") {
                    send(apiJson.encodeToString(Event.serializer(), Event(EventTypes.AUTH, token = admin.token)))
                    for (message in incoming) {
                        val event = apiJson.decodeFromString(Event.serializer(), (message as Frame.Text).readText())
                        if (event.type == EventTypes.READY) {
                            // now the frame connects
                            launch {
                                client.webSocket("/api/ws") {
                                    send(apiJson.encodeToString(Event.serializer(), Event(EventTypes.AUTH, token = frame.token)))
                                    incoming.receive()
                                }
                            }
                        }
                        if (event.type == EventTypes.PRESENCE_CHANGED && event.deviceId == frame.deviceId) presence.complete(event)
                    }
                }
            }
            val event = withTimeout(5_000) { presence.await() }
            assertEquals(true, event.online)
            listener.cancel()
        }
    }

    @Test
    fun `a device moved to another person merges a person left behind`() = test { client ->
        val phone = client.admin()
        val frame = client.pairFrame(phone)
        // the PC was registered with the admin code instead of being paired to the phone
        val pc: AuthResponse = client.register("Tobias am PC", adminCode = "admin-secret", kind = DeviceKind.DESKTOP).body()
        client.put("/api/admin/frames/${frame.deviceId}/contacts") {
            bearerAuth(phone.token)
            contentType(ContentType.Application.Json)
            setBody(listOf(FrameContactEntry(pc.personId!!, autoAnswer = true)))
        }

        val frameMove = client.put("/api/admin/devices/${frame.deviceId}") {
            bearerAuth(phone.token)
            contentType(ContentType.Application.Json)
            setBody(DeviceUpdate(phone.personId!!))
        }
        assertEquals(HttpStatusCode.BadRequest, frameMove.status)

        val move = client.put("/api/admin/devices/${pc.deviceId}") {
            bearerAuth(phone.token)
            contentType(ContentType.Application.Json)
            setBody(DeviceUpdate(phone.personId!!))
        }
        assertEquals(HttpStatusCode.NoContent, move.status)

        val persons: List<PersonAdmin> = client.get("/api/admin/persons") { bearerAuth(phone.token) }.body()
        assertEquals(listOf(phone.personId), persons.map { it.person.id })
        assertEquals(setOf(phone.deviceId, pc.deviceId), persons.single().devices.map { it.id }.toSet())
        // the PC's place on the frame now belongs to the merged person, auto answer included
        val config: FrameConfig = client.get("/api/frame/config") { bearerAuth(frame.token) }.body()
        assertEquals(listOf(phone.personId to true), config.contacts.map { it.personId to it.autoAnswer })
        // the PC keeps working under the new person
        val me: Me = client.get("/api/me") { bearerAuth(pc.token) }.body()
        assertEquals(phone.personId, me.person?.id)

        // an admin cannot hand their own device to a contact while their other devices stay admin
        val invite: InviteResponse = client.post("/api/admin/invites") { bearerAuth(phone.token) }.body()
        val relative: AuthResponse = client.register("Oma", code = invite.code).body()
        val lockout = client.put("/api/admin/devices/${pc.deviceId}") {
            bearerAuth(pc.token)
            contentType(ContentType.Application.Json)
            setBody(DeviceUpdate(relative.personId!!))
        }
        assertEquals(HttpStatusCode.BadRequest, lockout.status)
    }

    @Test
    fun `admins set the picture a frame shows for a person`() = test { client ->
        val admin = client.admin()
        val invite: InviteResponse = client.post("/api/admin/invites") { bearerAuth(admin.token) }.body()
        val relative: AuthResponse = client.register("Oma", code = invite.code).body()
        val upload = client.put("/api/admin/persons/${relative.personId}/avatar") {
            bearerAuth(admin.token)
            contentType(ContentType.Image.PNG)
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.NoContent, upload.status)
        val avatar = client.get("/api/persons/${relative.personId}/avatar") { bearerAuth(admin.token) }
        assertEquals(HttpStatusCode.OK, avatar.status)
        assertEquals(listOf<Byte>(1, 2, 3), avatar.body<ByteArray>().toList())
        val persons: List<PersonAdmin> = client.get("/api/admin/persons") { bearerAuth(admin.token) }.body()
        assertTrue(persons.first { it.person.id == relative.personId }.person.avatarVersion != null)
    }

    @Test
    fun `admins give a frame a picture its persons see in their list and when it calls`() = test { client ->
        val admin = client.admin()
        client.put("/api/me/push") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PushTokenRequest("fcm-phone"))
        }
        val frame = client.pairFrame(admin)
        val invite: InviteResponse = client.post("/api/admin/invites") { bearerAuth(admin.token) }.body()
        val relative: AuthResponse = client.register("Oma", code = invite.code).body()

        val refused = client.put("/api/admin/frames/${frame.deviceId}/avatar") {
            bearerAuth(relative.token)
            contentType(ContentType.Image.JPEG)
            setBody(byteArrayOf(4, 5, 6))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        val upload = client.put("/api/admin/frames/${frame.deviceId}/avatar") {
            bearerAuth(admin.token)
            contentType(ContentType.Image.JPEG)
            setBody(byteArrayOf(4, 5, 6))
        }
        assertEquals(HttpStatusCode.NoContent, upload.status)

        val listed: List<FrameRef> = client.get("/api/frames") { bearerAuth(relative.token) }.body()
        val version = listed.single().avatarVersion
        assertTrue(version != null)
        val picture = client.get("/api/frames/${frame.deviceId}/avatar?v=$version") { bearerAuth(relative.token) }
        assertEquals(HttpStatusCode.OK, picture.status)
        assertEquals(listOf<Byte>(4, 5, 6), picture.body<ByteArray>().toList())
        val frames: List<FrameAdmin> = client.get("/api/admin/frames") { bearerAuth(admin.token) }.body()
        assertEquals(version, frames.single().avatarVersion)

        client.post("/api/calls") {
            bearerAuth(frame.token)
            contentType(ContentType.Application.Json)
            setBody(StartCallRequest(personId = admin.personId))
        }
        assertEquals(version.toString(), eventually { push.sent.single() }.second["fromAvatarVersion"])
    }

    @Test
    fun `frame commands need a known command and an online frame`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val unknown = client.post("/api/admin/frames/${frame.deviceId}/command") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(FrameCommandRequest("reboot"))
        }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        val offline = client.post("/api/admin/frames/${frame.deviceId}/command") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(FrameCommandRequest(FrameCommands.UPDATE))
        }
        assertEquals(HttpStatusCode.Conflict, offline.status)
    }

    @Test
    fun `contacts share photos into the frame's album`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val share: suspend () -> HttpResponse = {
            client.post("/api/frames/${frame.deviceId}/photos") {
                bearerAuth(admin.token)
                contentType(ContentType.Image.JPEG)
                setBody(byteArrayOf(1, 2, 3, 4))
            }
        }
        assertEquals(HttpStatusCode.Conflict, share().status) // no album yet
        client.put("/api/admin/frames/${frame.deviceId}") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(FrameUpdate("Wohnzimmer Oma", "album-1", FrameSettings()))
        }
        assertEquals(HttpStatusCode.NoContent, share().status)
        assertEquals(listOf("album-1" to 4), photos.added)
        val links = client.get("/.well-known/assetlinks.json")
        assertTrue(links.bodyAsText().contains("net.pendzialek.closeby"))
    }

    @Test
    fun `revoked devices lose access`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/admin/devices/${frame.deviceId}") { bearerAuth(admin.token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/frame/config") { bearerAuth(frame.token) }.status)
        assertNull(push.sent.firstOrNull())
    }

    @Test
    fun `web client revalidates its page but keeps fingerprinted assets`() = test(web = { dir ->
        dir.resolve("assets").mkdirs()
        dir.resolve("index.html").writeText("<!doctype html>")
        dir.resolve("assets/index-abc123.js").writeText("console.log(1)")
    }) { client ->
        assertEquals("no-cache", client.get("/").headers[HttpHeaders.CacheControl])
        // unknown paths fall back to the page
        val fallback = client.get("/some/where")
        assertEquals(HttpStatusCode.OK, fallback.status)
        assertEquals("no-cache", fallback.headers[HttpHeaders.CacheControl])
        assertEquals("max-age=31536000", client.get("/assets/index-abc123.js").headers[HttpHeaders.CacheControl])
    }


    @Test
    fun `an unanswered call ends as missed, pushes that and closes the room`() = test(configure = { copy(ringTimeoutSeconds = 1) }) { client ->
        val admin = client.admin()
        client.put("/api/me/push") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PushTokenRequest("fcm-phone"))
        }
        val frame = client.pairFrame(admin)
        val join: CallJoin = client.post("/api/calls") {
            bearerAuth(frame.token)
            contentType(ContentType.Application.Json)
            setBody(StartCallRequest(personId = admin.personId))
        }.body()

        val ended = eventually { push.sent.single { it.second["type"] == EventTypes.CALL_ENDED } }
        assertEquals(join.callId, ended.second["callId"])
        assertEquals("missed", ended.second["reason"])
        eventually { assertEquals(listOf(join.callId), rooms.closed) }
    }

    @Test
    fun `migrations survive a rollback to an older image`() {
        val path = Files.createTempDirectory("closeby-db").resolve("test.db").toString()
        Db(path).close()
        fun version() = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { it.executeQuery("PRAGMA user_version").use { rs -> rs.getInt(1) } }
        }
        fun sql(vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s -> statements.forEach(s::execute) }
        }
        val latest = version()

        // an image from before the frame avatars set the version back, the next one migrates again
        sql("PRAGMA user_version = 1")
        Db(path).close()
        assertEquals(latest, version())

        // a real version-1 database still gets the columns
        sql(
            "ALTER TABLE frames DROP COLUMN avatar",
            "ALTER TABLE frames DROP COLUMN avatar_type",
            "ALTER TABLE frames DROP COLUMN avatar_version",
            "PRAGMA user_version = 1",
        )
        Db(path).close()
        sql("SELECT avatar, avatar_type, avatar_version FROM frames")

        // an older image never lowers the version
        sql("PRAGMA user_version = ${latest + 3}")
        Db(path).close()
        assertEquals(latest + 3, version())
    }

    @Test
    fun `oversized bodies are refused before they are read`() = test { client ->
        val pairing = client.post("/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody("{\"kind\":\"frame\",\"name\":\"${"x".repeat(200_000)}\"}")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, pairing.status)
        val admin = client.admin()
        val avatar = client.put("/api/me/avatar") {
            bearerAuth(admin.token)
            contentType(ContentType.Image.JPEG)
            setBody(ByteArray(3 * 1024 * 1024))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, avatar.status)
    }

    @Test
    fun `only an unknown token closes the websocket as revoked`() = test { client ->
        suspend fun closeCode(first: String): Short? {
            var code: Short? = null
            client.webSocket("/api/ws") {
                send(first)
                // the server closes right after the first message
                incoming.receiveCatching()
                code = closeReason.await()?.code
            }
            return code
        }
        assertEquals(4408.toShort(), closeCode("not json"))
        val unknown = apiJson.encodeToString(Event.serializer(), Event(EventTypes.AUTH, token = "nope"))
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeCode(unknown))
    }

    @Test
    fun `a code of another kind is refused and burnt`() = test { client ->
        val admin = client.admin()
        val start: PairingStartResponse = client.post("/api/pairing/start") {
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(DeviceKind.DESKTOP, "Mallory PC"))
        }.body()
        suspend fun claim() = client.post("/api/pairing/claim") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, expectedKind = DeviceKind.FRAME))
        }
        assertEquals(HttpStatusCode.Conflict, claim().status)
        assertEquals(HttpStatusCode.NotFound, claim().status)
    }

    @Test
    fun `frame settings outside the limits are refused`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        suspend fun save(settings: FrameSettings) = client.put("/api/admin/frames/${frame.deviceId}") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(FrameUpdate("Wohnzimmer", settings = settings))
        }.status
        assertEquals(HttpStatusCode.BadRequest, save(FrameSettings(slideshowIntervalSeconds = 0)))
        assertEquals(HttpStatusCode.BadRequest, save(FrameSettings(volume = 150)))
        assertEquals(HttpStatusCode.BadRequest, save(FrameSettings(nightStart = "22:00")))
        assertEquals(HttpStatusCode.BadRequest, save(FrameSettings(nightStart = "late", nightEnd = "07:00")))
        assertEquals(HttpStatusCode.NoContent, save(FrameSettings(nightStart = "22:00", nightEnd = "07:00", volume = 80)))
    }

    @Test
    fun `the last admin stays an admin`() = test { client ->
        val admin = client.admin()
        val demote = client.put("/api/admin/persons/${admin.personId}") {
            bearerAuth(admin.token)
            contentType(ContentType.Application.Json)
            setBody(PersonUpdate(isAdmin = false))
        }
        assertEquals(HttpStatusCode.BadRequest, demote.status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/admin/persons/${admin.personId}") { bearerAuth(admin.token) }.status)
        val me: Me = client.get("/api/me") { bearerAuth(admin.token) }.body()
        assertEquals(true, me.person?.isAdmin)
    }

    @Test
    fun `removing a frame tells the others it is offline`() = test { client ->
        val admin = client.admin()
        val frame = client.pairFrame(admin)
        val offline = CompletableDeferred<Event>()
        val frameOnline = CompletableDeferred<Unit>()
        coroutineScope {
            val adminSocket = launch {
                client.webSocket("/api/ws") {
                    send(apiJson.encodeToString(Event.serializer(), Event(EventTypes.AUTH, token = admin.token)))
                    for (message in incoming) {
                        val event = apiJson.decodeFromString(Event.serializer(), (message as Frame.Text).readText())
                        if (event.type == EventTypes.PRESENCE_CHANGED && event.deviceId == frame.deviceId) {
                            if (event.online == true) frameOnline.complete(Unit) else offline.complete(event)
                        }
                    }
                }
            }
            val frameSocket = launchListener(client, frame.token, CompletableDeferred(), CompletableDeferred())
            withTimeout(5_000) { frameOnline.await() }
            client.delete("/api/admin/devices/${frame.deviceId}") { bearerAuth(admin.token) }
            assertEquals(false, withTimeout(5_000) { offline.await() }.online)
            frameSocket.cancel()
            adminSocket.cancel()
        }
    }

    @Test
    fun `each release has its own uncached download`() = test(apk = { dir ->
        dir.mkdirs()
        dir.resolve("closeby.apk").writeBytes(byteArrayOf(7, 7, 7))
        dir.resolve("version.json").writeText("""{"versionCode":6101,"versionName":"0.1.61","sha256":"abc"}""")
    }) { client ->
        val latest = client.get("/api/app/android")
        assertEquals("no-cache", latest.headers[HttpHeaders.CacheControl])
        val release: AppRelease = latest.body()
        assertTrue(release.url.endsWith("/api/app/android/closeby.apk?v=6101"))
        val download = client.get("/api/app/android/closeby.apk?v=6101")
        assertEquals("no-store", download.headers[HttpHeaders.CacheControl])
        assertEquals(listOf<Byte>(7, 7, 7), download.body<ByteArray>().toList())
    }

    @Test
    fun `unknown api paths are a 404, not the web page`() = test(web = { dir ->
        dir.mkdirs()
        dir.resolve("index.html").writeText("<!doctype html>")
    }) { client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/calls").status)
        assertEquals(HttpStatusCode.OK, client.get("/rahmen").status)
    }

    @Test
    fun `photos keep coming from the cache while Immich is down`() = runBlocking {
        var now = 0L
        val source = FakePhotos()
        val service = PhotoService(source, Files.createTempDirectory("closeby-cache").toString()) { now }
        val asset = "11111111-1111-1111-1111-111111111111"
        assertEquals(2, service.slideshow("album-1").size)
        assertEquals(listOf<Byte>(1, 2, 3), service.preview("album-1", asset)?.toList())

        source.down = true
        now += 60 * 60 * 1000L
        assertEquals(2, service.slideshow("album-1").size)
        assertEquals(listOf<Byte>(1, 2, 3), service.preview("album-1", asset)?.toList())
        // no hammering: the next attempt waits a little
        val fetches = source.albumFetches
        service.slideshow("album-1")
        assertEquals(fetches, source.albumFetches)
        assertNull(service.preview("album-1", "../../etc/passwd"))
    }

    private fun CoroutineScope.launchListener(
        client: HttpClient,
        token: String,
        ready: CompletableDeferred<Unit>,
        accepted: CompletableDeferred<Event>,
    ) = launch {
        client.webSocket("/api/ws") {
            send(apiJson.encodeToString(Event.serializer(), Event(EventTypes.AUTH, token = token)))
            for (frame in incoming) {
                val event = apiJson.decodeFromString(Event.serializer(), (frame as Frame.Text).readText())
                when (event.type) {
                    EventTypes.READY -> ready.complete(Unit)
                    EventTypes.CALL_ACCEPTED -> accepted.complete(event)
                }
            }
        }
    }
}
