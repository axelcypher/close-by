package net.pendzialek.closeby.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// API models shared by the backend and the Android app. The web client mirrors
// them in web/src/api.ts; keep both in sync. See docs/architecture.md.

@Serializable
enum class DeviceKind {
    @SerialName("frame") FRAME,
    @SerialName("phone") PHONE,
    @SerialName("desktop") DESKTOP,
}

@Serializable
data class ApiError(val error: String)

// ---- registration and pairing ----

@Serializable
data class NewDevice(val kind: DeviceKind, val name: String)

@Serializable
data class NewPerson(val name: String)

/** Registers a new person with its first device, via an invite [code] or the [adminCode]. */
@Serializable
data class RegisterRequest(
    val code: String? = null,
    val adminCode: String? = null,
    val device: NewDevice,
    val person: NewPerson,
)

@Serializable
data class AuthResponse(val token: String, val deviceId: String, val personId: String? = null)

/** Started by a device without a person of its own (frame) or one that joins an existing person (desktop). */
@Serializable
data class PairingStartRequest(val kind: DeviceKind, val name: String)

@Serializable
data class PairingStartResponse(
    val pairingId: String,
    /** Short code shown as text and inside the QR payload. */
    val code: String,
    /** Proves the poller is the device that started the pairing; send as `X-Poll-Secret`. */
    val pollSecret: String,
    val expiresAt: Long,
)

@Serializable
data class PairingClaimRequest(val code: String)

@Serializable
data class PairingClaimResponse(val deviceId: String, val kind: DeviceKind)

@Serializable
enum class PairingState {
    @SerialName("pending") PENDING,
    @SerialName("done") DONE,
}

@Serializable
data class PairingStatus(
    val state: PairingState,
    val token: String? = null,
    val deviceId: String? = null,
    val personId: String? = null,
)

@Serializable
data class InviteResponse(val code: String, val url: String, val expiresAt: Long)

// ---- devices and persons ----

@Serializable
data class DeviceStatus(
    val battery: Int? = null,
    val charging: Boolean? = null,
    val wifiRssi: Int? = null,
    /** The app is device owner: kiosk, silent updates and permissions without prompts work. */
    val deviceOwner: Boolean? = null,
    /** The frame is locked to CloseBy right now (false during maintenance). */
    val kiosk: Boolean? = null,
)

@Serializable
data class StatusReport(val appVersion: Int? = null, val status: DeviceStatus = DeviceStatus())

@Serializable
data class DeviceInfo(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    val personId: String? = null,
    val appVersion: Int? = null,
    val lastSeen: Long? = null,
    val online: Boolean = false,
    val status: DeviceStatus? = null,
)

@Serializable
data class PersonInfo(
    val id: String,
    val name: String,
    /** Name shown on frames instead of [name], set by an admin. */
    val displayName: String? = null,
    val isAdmin: Boolean = false,
    /** Changes whenever the avatar changes; null without avatar. Avatar: `GET /api/persons/{id}/avatar`. */
    val avatarVersion: Long? = null,
)

@Serializable
data class Me(val device: DeviceInfo, val person: PersonInfo? = null)

@Serializable
data class UpdateMeRequest(val name: String)

@Serializable
data class PushTokenRequest(val fcmToken: String? = null)

// ---- frames ----

@Serializable
data class FrameSettings(
    val slideshowIntervalSeconds: Int = 15,
    /** Seconds without touch before the frame returns to the slideshow. */
    val idleTimeoutSeconds: Int = 60,
    /** Night mode as "HH:mm", both or neither. */
    val nightStart: String? = null,
    val nightEnd: String? = null,
    /** 0-100, null leaves the device setting alone. */
    val volume: Int? = null,
    val brightness: Int? = null,
    /** Resolve the backend via DNS over HTTPS, for networks whose DNS answers with internal addresses. */
    val forcePublicDns: Boolean = true,
)

@Serializable
data class FrameContact(
    val personId: String,
    val name: String,
    val avatarVersion: Long? = null,
    val autoAnswer: Boolean = false,
)

/** What a frame needs to render; refetched on `config.changed`. */
@Serializable
data class FrameConfig(
    val frameId: String,
    val name: String,
    val settings: FrameSettings,
    val contacts: List<FrameContact>,
)

@Serializable
data class SlideshowPhoto(val id: String, val takenAt: String? = null)

@Serializable
data class Slideshow(val photos: List<SlideshowPhoto>)

/** A frame as seen by a person's device: something it can call. Picture: `GET /api/frames/{id}/avatar`. */
@Serializable
data class FrameRef(val id: String, val name: String, val online: Boolean, val avatarVersion: Long? = null)

// ---- admin ----

@Serializable
data class FrameContactEntry(val personId: String, val autoAnswer: Boolean = false)

@Serializable
data class FrameAdmin(
    val device: DeviceInfo,
    val albumId: String? = null,
    val settings: FrameSettings,
    val contacts: List<FrameContactEntry>,
    /** The frame's own picture, shown to the persons it calls; set via `PUT /api/admin/frames/{id}/avatar`. */
    val avatarVersion: Long? = null,
)

@Serializable
data class FrameUpdate(val name: String, val albumId: String? = null, val settings: FrameSettings)

@Serializable
data class PersonUpdate(val displayName: String? = null, val isAdmin: Boolean)

@Serializable
data class PersonAdmin(val person: PersonInfo, val devices: List<DeviceInfo>)

/** Admin: hand a phone or PC to another person, e.g. when someone registered twice. */
@Serializable
data class DeviceUpdate(val personId: String)

@Serializable
data class ImmichAlbum(val id: String, val name: String, val assetCount: Int)

// ---- calls ----

@Serializable
enum class PartyKind {
    @SerialName("person") PERSON,
    @SerialName("frame") FRAME,
}

/** [avatarVersion] belongs to the person's or the frame's picture, depending on [kind]. */
@Serializable
data class CallParty(val kind: PartyKind, val id: String, val name: String, val avatarVersion: Long? = null)

/** Exactly one target: a frame calls a person, a person's device calls a frame. */
@Serializable
data class StartCallRequest(val personId: String? = null, val frameId: String? = null)

@Serializable
data class CallJoin(val callId: String, val livekitUrl: String, val token: String)

// ---- realtime ----

object EventTypes {
    const val AUTH = "auth"
    const val READY = "ready"
    const val CALL_INCOMING = "call.incoming"
    const val CALL_ACCEPTED = "call.accepted"
    const val CALL_DECLINED = "call.declined"
    const val CALL_TAKEN = "call.taken"
    const val CALL_ENDED = "call.ended"
    const val CONFIG_CHANGED = "config.changed"
    /** A device came online or went offline; carries [Event.deviceId] and [Event.online]. */
    const val PRESENCE_CHANGED = "presence.changed"
    /** Admin command for a frame; carries [Event.command], see [FrameCommands]. */
    const val FRAME_COMMAND = "frame.command"
}

object FrameCommands {
    /** Leave the kiosk for ten minutes, e.g. to change Wi-Fi on site. */
    const val MAINTENANCE = "maintenance"
    /** Look for a new app version right away. */
    const val UPDATE = "update"
    /** Give up device owner: kiosk off, policies cleared, a normal Android tablet again. */
    const val RELEASE = "release"
    val all = setOf(MAINTENANCE, UPDATE, RELEASE)
}

@Serializable
data class FrameCommandRequest(val command: String)

object EndReasons {
    const val HANGUP = "hangup"
    const val CANCELLED = "cancelled"
    const val DECLINED = "declined"
    const val MISSED = "missed"
}

/** Every websocket message, both directions. FCM data messages carry the same fields as strings. */
@Serializable
data class Event(
    val type: String,
    val token: String? = null,
    val callId: String? = null,
    val from: CallParty? = null,
    val autoAnswer: Boolean? = null,
    val reason: String? = null,
    val deviceId: String? = null,
    val online: Boolean? = null,
    val command: String? = null,
)

// ---- app updates ----

@Serializable
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val url: String,
)
