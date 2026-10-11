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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.pendzialek.closeby.api.AuthResponse
import net.pendzialek.closeby.api.CallJoin
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.Event
import net.pendzialek.closeby.api.EventTypes
import net.pendzialek.closeby.api.FrameAdmin
import net.pendzialek.closeby.api.FrameCommandRequest
import net.pendzialek.closeby.api.FrameCommands
import net.pendzialek.closeby.api.FrameConfig
import net.pendzialek.closeby.api.FrameSettings
import net.pendzialek.closeby.api.FrameUpdate
import net.pendzialek.closeby.api.ImmichAlbum
import net.pendzialek.closeby.api.InviteResponse
import net.pendzialek.closeby.api.NewDevice
import net.pendzialek.closeby.api.NewPerson
import net.pendzialek.closeby.api.PairingClaimRequest
import net.pendzialek.closeby.api.PairingStartRequest
import net.pendzialek.closeby.api.PairingStartResponse
import net.pendzialek.closeby.api.PairingState
import net.pendzialek.closeby.api.PairingStatus
import net.pendzialek.closeby.api.PushTokenRequest
import net.pendzialek.closeby.api.RegisterRequest
import net.pendzialek.closeby.api.Slideshow
import net.pendzialek.closeby.api.SlideshowPhoto
import net.pendzialek.closeby.api.StartCallRequest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendTest {
    private class FakeRooms : Rooms {
        val closed = mutableListOf<String>()
        override fun joinToken(room: String, identity: String, name: String) = "token:$room:$identity"
        override suspend fun close(room: String) {
            closed += room
        }
    }

    private class FakePush : Push {
        val sent = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun send(fcmToken: String, data: Map<String, String>): PushResult {
            sent += fcmToken to data
            return PushResult.SENT
        }
    }

    private class FakePhotos : PhotoSource {
        val added = mutableListOf<Pair<String, Int>>()
        override suspend fun addToAlbum(albumId: String, image: ByteArray, contentType: String, fileName: String) {
            added += albumId to image.size
        }
        override suspend fun albums() = listOf(ImmichAlbum("album-1", "Familie", 2))
        override suspend fun albumPhotos(albumId: String) = listOf(
            SlideshowPhoto("11111111-1111-1111-1111-111111111111"),
            SlideshowPhoto("22222222-2222-2222-2222-222222222222"),
        )
        override suspend fun preview(assetId: String) = byteArrayOf(1, 2, 3)
    }

    private val rooms = FakeRooms()
    private val push = FakePush()
    private val photos = FakePhotos()

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val dir = Files.createTempDirectory("closeby-test").toFile()
        val config = Config(
            databasePath = dir.resolve("test.db").path,
            adminCode = "admin-secret",
            cacheDir = dir.resolve("cache").path,
            apkDir = dir.resolve("apk").path,
            webDir = dir.resolve("web").path,
        )
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
            val incoming = push.sent.single()
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
