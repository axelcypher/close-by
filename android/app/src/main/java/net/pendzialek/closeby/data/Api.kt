package net.pendzialek.closeby.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.pendzialek.closeby.api.ApiError
import net.pendzialek.closeby.api.AppRelease
import net.pendzialek.closeby.api.AuthResponse
import net.pendzialek.closeby.api.DeviceUpdate
import net.pendzialek.closeby.api.FrameAdmin
import net.pendzialek.closeby.api.FrameCommandRequest
import net.pendzialek.closeby.api.FrameContactEntry
import net.pendzialek.closeby.api.FrameRef
import net.pendzialek.closeby.api.FrameUpdate
import net.pendzialek.closeby.api.ImmichAlbum
import net.pendzialek.closeby.api.InviteResponse
import net.pendzialek.closeby.api.PersonAdmin
import net.pendzialek.closeby.api.PersonUpdate
import net.pendzialek.closeby.api.PairingClaimRequest
import net.pendzialek.closeby.api.PairingClaimResponse
import net.pendzialek.closeby.api.PushTokenRequest
import net.pendzialek.closeby.api.RegisterRequest
import net.pendzialek.closeby.api.UpdateMeRequest
import net.pendzialek.closeby.api.CallJoin
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.FrameConfig
import net.pendzialek.closeby.api.Me
import net.pendzialek.closeby.api.PairingStartRequest
import net.pendzialek.closeby.api.PairingStartResponse
import net.pendzialek.closeby.api.PairingStatus
import net.pendzialek.closeby.api.Slideshow
import net.pendzialek.closeby.api.StartCallRequest
import net.pendzialek.closeby.api.StatusReport
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message)

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * HTTP client for the backend. With [Prefs.forcePublicDns] the server name is
 * resolved through Cloudflare DNS over HTTPS, so a local resolver that answers
 * with an internal address (grandma's site-to-site VPN) can't get in the way.
 */
class Api(private val prefs: Prefs, cacheDir: File) {
    private val base = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(cacheDir, "http"), 100L * 1024 * 1024))
        .build()

    private val publicDns: Dns = DnsOverHttps.Builder()
        .client(base)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
        .build()

    private val viaPublicDns = base.newBuilder().dns(publicDns).build()

    /** Also handed to LiveKit, so its signaling connection resolves the same way. */
    val client: OkHttpClient get() = if (prefs.forcePublicDns) viaPublicDns else base

    // ---- public ----

    suspend fun startPairing(kind: DeviceKind, name: String): PairingStartResponse =
        post("/api/pairing/start", PairingStartRequest(kind, name), PairingStartRequest.serializer(), PairingStartResponse.serializer())

    suspend fun pollPairing(id: String, pollSecret: String): PairingStatus =
        call(request("/api/pairing/$id").header("X-Poll-Secret", pollSecret).get().build(), PairingStatus.serializer())

    /** The APK the backend ships; throws when none is published. */
    suspend fun latestRelease(): AppRelease = get("/api/app/android", AppRelease.serializer())

    // ---- device ----

    suspend fun me(): Me = get("/api/me", Me.serializer())

    suspend fun reportStatus(report: StatusReport) =
        post<StatusReport, Unit>("/api/me/status", report, StatusReport.serializer(), null)

    suspend fun frameConfig(): FrameConfig = get("/api/frame/config", FrameConfig.serializer())

    suspend fun slideshow(): Slideshow = get("/api/frame/slideshow", Slideshow.serializer())

    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request(path).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "GET $path: ${response.code}")
            response.body!!.bytes()
        }
    }

    // ---- contact mode ----

    suspend fun register(request: RegisterRequest): AuthResponse =
        post("/api/register", request, RegisterRequest.serializer(), AuthResponse.serializer())

    suspend fun claimPairing(code: String): PairingClaimResponse =
        post("/api/pairing/claim", PairingClaimRequest(code), PairingClaimRequest.serializer(), PairingClaimResponse.serializer())

    suspend fun frames(): List<FrameRef> = get("/api/frames", ListSerializer(FrameRef.serializer()))

    suspend fun rename(name: String) = call<Unit>(
        request("/api/me").patch(json.encodeToString(UpdateMeRequest.serializer(), UpdateMeRequest(name)).toRequestBody(JSON)).build(),
        null,
    )

    suspend fun uploadAvatar(jpeg: ByteArray) = call<Unit>(
        request("/api/me/avatar").put(jpeg.toRequestBody(JPEG)).build(),
        null,
    )

    suspend fun setPushToken(token: String?) = call<Unit>(
        request("/api/me/push").put(json.encodeToString(PushTokenRequest.serializer(), PushTokenRequest(token)).toRequestBody(JSON)).build(),
        null,
    )

    suspend fun signOutDevice() = call<Unit>(request("/api/me").delete().build(), null)

    suspend fun sharePhoto(frameId: String, jpeg: ByteArray) = call<Unit>(
        request("/api/frames/$frameId/photos").post(jpeg.toRequestBody(JPEG)).build(),
        null,
    )

    suspend fun callFrame(frameId: String): CallJoin =
        post("/api/calls", StartCallRequest(frameId = frameId), StartCallRequest.serializer(), CallJoin.serializer())

    // ---- admin ----

    suspend fun invite(): InviteResponse = post("/api/admin/invites", InviteResponse.serializer())

    suspend fun adminFrames(): List<FrameAdmin> = get("/api/admin/frames", ListSerializer(FrameAdmin.serializer()))

    suspend fun updateFrame(id: String, update: FrameUpdate) = put("/api/admin/frames/$id", update, FrameUpdate.serializer())

    suspend fun setFrameContacts(id: String, contacts: List<FrameContactEntry>) =
        put("/api/admin/frames/$id/contacts", contacts, ListSerializer(FrameContactEntry.serializer()))

    /** The frame's own picture, shown to the persons it calls. */
    suspend fun uploadFrameAvatar(id: String, jpeg: ByteArray) = call<Unit>(
        request("/api/admin/frames/$id/avatar").put(jpeg.toRequestBody(JPEG)).build(),
        null,
    )

    suspend fun frameCommand(id: String, command: String) = post<FrameCommandRequest, Unit>(
        "/api/admin/frames/$id/command",
        FrameCommandRequest(command),
        FrameCommandRequest.serializer(),
        null,
    )

    suspend fun albums(): List<ImmichAlbum> = get("/api/admin/immich/albums", ListSerializer(ImmichAlbum.serializer()))

    suspend fun adminPersons(): List<PersonAdmin> = get("/api/admin/persons", ListSerializer(PersonAdmin.serializer()))

    suspend fun updatePerson(id: String, update: PersonUpdate) = put("/api/admin/persons/$id", update, PersonUpdate.serializer())

    suspend fun deletePerson(id: String) = call<Unit>(request("/api/admin/persons/$id").delete().build(), null)

    /** The picture frames show for the person; replaces whatever the person set. */
    suspend fun uploadPersonAvatar(id: String, jpeg: ByteArray) = call<Unit>(
        request("/api/admin/persons/$id/avatar").put(jpeg.toRequestBody(JPEG)).build(),
        null,
    )

    suspend fun deleteDevice(id: String) = call<Unit>(request("/api/admin/devices/$id").delete().build(), null)

    suspend fun moveDevice(id: String, personId: String) = put("/api/admin/devices/$id", DeviceUpdate(personId), DeviceUpdate.serializer())

    // ---- calls ----

    suspend fun startCall(personId: String): CallJoin =
        post("/api/calls", StartCallRequest(personId = personId), StartCallRequest.serializer(), CallJoin.serializer())

    suspend fun acceptCall(callId: String): CallJoin = post("/api/calls/$callId/accept", CallJoin.serializer())

    suspend fun declineCall(callId: String) = post<Unit>("/api/calls/$callId/decline", null)

    suspend fun endCall(callId: String) = post<Unit>("/api/calls/$callId/end", null)

    // ---- plumbing ----

    fun request(path: String): Request.Builder = Request.Builder().url(prefs.server + path).apply {
        prefs.token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T = call(request(path).get().build(), serializer)

    private suspend fun <T> post(path: String, serializer: KSerializer<T>?): T =
        call(request(path).post(EMPTY).build(), serializer)

    private suspend fun <B, T> post(path: String, body: B, bodySerializer: KSerializer<B>, serializer: KSerializer<T>?): T =
        call(request(path).post(json.encodeToString(bodySerializer, body).toRequestBody(JSON)).build(), serializer)

    private suspend fun <B> put(path: String, body: B, bodySerializer: KSerializer<B>) =
        call<Unit>(request(path).put(json.encodeToString(bodySerializer, body).toRequestBody(JSON)).build(), null)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> call(request: Request, serializer: KSerializer<T>?): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.decodeFromString(ApiError.serializer(), text).error }.getOrDefault(text)
                throw ApiException(response.code, message)
            }
            if (serializer == null) Unit as T else json.decodeFromString(serializer, text)
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val JPEG = "image/jpeg".toMediaType()
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}
