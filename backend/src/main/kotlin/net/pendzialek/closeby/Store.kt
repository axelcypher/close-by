package net.pendzialek.closeby

import kotlinx.serialization.json.Json
import net.pendzialek.closeby.api.DeviceKind
import net.pendzialek.closeby.api.DeviceStatus
import net.pendzialek.closeby.api.FrameContactEntry
import net.pendzialek.closeby.api.FrameSettings
import java.sql.ResultSet

data class Device(
    val id: String,
    val kind: DeviceKind,
    val name: String,
    val personId: String?,
    val fcmToken: String?,
    val appVersion: Int?,
    val status: DeviceStatus?,
    val lastSeen: Long?,
)

data class Person(
    val id: String,
    val name: String,
    val displayName: String?,
    val isAdmin: Boolean,
    val avatarVersion: Long?,
) {
    /** What a frame shows. */
    val shownName get() = displayName ?: name
}

data class PhotoFrame(
    val deviceId: String,
    val name: String,
    val albumId: String?,
    val settings: FrameSettings,
    /** The frame's own picture, for the persons it calls. */
    val avatarVersion: Long? = null,
)

data class FrameContactRow(val person: Person, val autoAnswer: Boolean)

data class Avatar(val bytes: ByteArray, val contentType: String)

data class Pairing(
    val id: String,
    val code: String,
    val pollHash: String,
    val kind: DeviceKind,
    val name: String,
    val expiresAt: Long,
    val deviceId: String?,
    val personId: String?,
    val token: String?,
)

class Store(private val db: Db, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }

    fun now() = clock()

    // ---- persons ----

    suspend fun person(id: String): Person? = db.tx { one("SELECT * FROM persons WHERE id = ?", id, map = ::toPerson) }

    suspend fun persons(): List<Person> = db.tx { query("SELECT * FROM persons ORDER BY created_at", map = ::toPerson) }

    suspend fun updatePersonName(id: String, name: String) = db.tx { exec("UPDATE persons SET name = ? WHERE id = ?", name, id) }

    suspend fun updatePersonAdmin(id: String, displayName: String?, isAdmin: Boolean) = db.tx {
        exec("UPDATE persons SET display_name = ?, is_admin = ? WHERE id = ?", displayName, isAdmin.toInt(), id)
    }

    suspend fun deletePerson(id: String) = db.tx { exec("DELETE FROM persons WHERE id = ?", id) }

    suspend fun adminCount(): Int = db.tx { one("SELECT COUNT(*) FROM persons WHERE is_admin = 1") { it.getInt(1) } ?: 0 }

    suspend fun avatar(personId: String): Avatar? = db.tx {
        one("SELECT avatar, avatar_type FROM persons WHERE id = ? AND avatar IS NOT NULL", personId) {
            Avatar(it.getBytes("avatar"), it.getString("avatar_type"))
        }
    }

    suspend fun setAvatar(personId: String, avatar: Avatar) = db.tx {
        exec(
            "UPDATE persons SET avatar = ?, avatar_type = ?, avatar_version = ? WHERE id = ?",
            avatar.bytes, avatar.contentType, now(), personId,
        )
    }

    // ---- devices ----

    suspend fun deviceByTokenHash(hash: String): Device? =
        db.tx { one("SELECT * FROM devices WHERE token_hash = ?", hash, map = ::toDevice) }

    suspend fun device(id: String): Device? = db.tx { one("SELECT * FROM devices WHERE id = ?", id, map = ::toDevice) }

    suspend fun devices(): List<Device> = db.tx { query("SELECT * FROM devices ORDER BY created_at", map = ::toDevice) }

    suspend fun devicesOfPerson(personId: String): List<Device> =
        db.tx { query("SELECT * FROM devices WHERE person_id = ? ORDER BY created_at", personId, map = ::toDevice) }

    suspend fun deleteDevice(id: String) = db.tx { exec("DELETE FROM devices WHERE id = ?", id) }

    /**
     * Moves a phone or PC from one person to another. A person left without
     * devices is merged into the target: their places on frames, admin rights,
     * and (if the target has none) display name and avatar carry over, then
     * they are deleted. Returns true if that merge happened.
     */
    suspend fun moveDevice(deviceId: String, from: String, to: String): Boolean = db.tx {
        exec("UPDATE devices SET person_id = ? WHERE id = ?", to, deviceId)
        val left = one("SELECT COUNT(*) FROM devices WHERE person_id = ?", from) { it.getInt(1) } ?: 0
        if (left > 0) return@tx false
        exec(
            """
            UPDATE frame_contacts SET person_id = ? WHERE person_id = ?
            AND frame_id NOT IN (SELECT frame_id FROM frame_contacts WHERE person_id = ?)
            """,
            to, from, to,
        )
        exec(
            """
            UPDATE persons SET
                display_name = COALESCE(display_name, (SELECT display_name FROM persons WHERE id = ?)),
                avatar_type = CASE WHEN avatar IS NULL THEN (SELECT avatar_type FROM persons WHERE id = ?) ELSE avatar_type END,
                avatar_version = CASE WHEN avatar IS NULL THEN (SELECT avatar_version FROM persons WHERE id = ?) ELSE avatar_version END,
                avatar = COALESCE(avatar, (SELECT avatar FROM persons WHERE id = ?)),
                is_admin = MAX(is_admin, (SELECT is_admin FROM persons WHERE id = ?))
            WHERE id = ?
            """,
            from, from, from, from, from, to,
        )
        exec("DELETE FROM persons WHERE id = ?", from)
        true
    }

    suspend fun touch(deviceId: String) = db.tx { exec("UPDATE devices SET last_seen = ? WHERE id = ?", now(), deviceId) }

    suspend fun setFcmToken(deviceId: String, token: String?) = db.tx {
        // a token moves with the app install; never keep it on two devices
        if (token != null) exec("UPDATE devices SET fcm_token = NULL WHERE fcm_token = ? AND id <> ?", token, deviceId)
        exec("UPDATE devices SET fcm_token = ? WHERE id = ?", token, deviceId)
    }

    suspend fun clearFcmToken(token: String) = db.tx { exec("UPDATE devices SET fcm_token = NULL WHERE fcm_token = ?", token) }

    suspend fun setStatus(deviceId: String, appVersion: Int?, status: DeviceStatus) = db.tx {
        exec(
            "UPDATE devices SET app_version = COALESCE(?, app_version), status = ?, last_seen = ? WHERE id = ?",
            appVersion, json.encodeToString(DeviceStatus.serializer(), status), now(), deviceId,
        )
    }

    // ---- registration ----

    /** Creates a person with a first device; returns (personId, deviceId). */
    suspend fun registerPerson(
        personName: String,
        isAdmin: Boolean,
        deviceKind: DeviceKind,
        deviceName: String,
        tokenHash: String,
        inviteCode: String?,
    ): Pair<String, String>? = db.tx {
        if (inviteCode != null) {
            val deleted = exec("DELETE FROM invites WHERE code = ? AND expires_at > ?", inviteCode, now())
            if (deleted == 0) return@tx null
        }
        val personId = Secrets.newId()
        exec(
            "INSERT INTO persons (id, name, is_admin, created_at) VALUES (?, ?, ?, ?)",
            personId, personName, isAdmin.toInt(), now(),
        )
        val deviceId = insertDevice(deviceKind, deviceName, personId, tokenHash)
        // new persons show up on every frame; admins hide them per frame
        query("SELECT device_id FROM frames") { it.getString(1) }.forEach { addFrameContact(it, personId) }
        personId to deviceId
    }

    suspend fun createInvite(createdBy: String, code: String, expiresAt: Long) = db.tx {
        exec("DELETE FROM invites WHERE expires_at <= ?", now())
        exec("INSERT INTO invites (code, created_by, expires_at) VALUES (?, ?, ?)", code, createdBy, expiresAt)
    }

    // ---- pairing ----

    suspend fun createPairing(id: String, code: String, pollHash: String, kind: DeviceKind, name: String, expiresAt: Long) =
        db.tx {
            exec("DELETE FROM pairings WHERE expires_at <= ?", now())
            exec(
                "INSERT INTO pairings (id, code, poll_hash, kind, name, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, code, pollHash, kind.dbName, name, expiresAt,
            )
        }

    suspend fun pairing(id: String): Pairing? =
        db.tx { one("SELECT * FROM pairings WHERE id = ? AND expires_at > ?", id, now(), map = ::toPairing) }

    suspend fun pairingByCode(code: String): Pairing? =
        db.tx { one("SELECT * FROM pairings WHERE code = ? AND expires_at > ?", code, now(), map = ::toPairing) }

    /**
     * Creates the device for a pending pairing. The token is kept on the pairing
     * row only until the device picks it up.
     */
    suspend fun completePairing(pairing: Pairing, personId: String?, token: String, claimantPersonId: String): String? = db.tx {
        val stillOpen = one("SELECT device_id FROM pairings WHERE id = ?", pairing.id) { it.getString(1) == null } ?: false
        if (!stillOpen) return@tx null
        val deviceId = insertDevice(pairing.kind, pairing.name, personId, Secrets.sha256(token))
        if (pairing.kind == DeviceKind.FRAME) {
            exec(
                "INSERT INTO frames (device_id, settings) VALUES (?, ?)",
                deviceId, json.encodeToString(FrameSettings.serializer(), FrameSettings()),
            )
            addFrameContact(deviceId, claimantPersonId)
        }
        exec("UPDATE pairings SET device_id = ?, person_id = ?, token = ? WHERE id = ?", deviceId, personId, token, pairing.id)
        deviceId
    }

    suspend fun deletePairing(id: String) = db.tx { exec("DELETE FROM pairings WHERE id = ?", id) }

    // ---- frames ----

    suspend fun frame(deviceId: String): PhotoFrame? = db.tx {
        one(
            "SELECT f.*, d.name FROM frames f JOIN devices d ON d.id = f.device_id WHERE f.device_id = ?",
            deviceId, map = ::toFrame,
        )
    }

    suspend fun frames(): List<PhotoFrame> = db.tx {
        query("SELECT f.*, d.name FROM frames f JOIN devices d ON d.id = f.device_id ORDER BY d.created_at", map = ::toFrame)
    }

    suspend fun framesOfPerson(personId: String): List<PhotoFrame> = db.tx {
        query(
            """
            SELECT f.*, d.name FROM frames f
            JOIN devices d ON d.id = f.device_id
            JOIN frame_contacts c ON c.frame_id = f.device_id
            WHERE c.person_id = ? ORDER BY d.created_at
            """,
            personId, map = ::toFrame,
        )
    }

    suspend fun updateFrame(deviceId: String, name: String, albumId: String?, settings: FrameSettings) = db.tx {
        exec("UPDATE devices SET name = ? WHERE id = ?", name, deviceId)
        exec(
            "UPDATE frames SET album_id = ?, settings = ? WHERE device_id = ?",
            albumId, json.encodeToString(FrameSettings.serializer(), settings), deviceId,
        )
    }

    suspend fun frameAvatar(frameId: String): Avatar? = db.tx {
        one("SELECT avatar, avatar_type FROM frames WHERE device_id = ? AND avatar IS NOT NULL", frameId) {
            Avatar(it.getBytes("avatar"), it.getString("avatar_type"))
        }
    }

    suspend fun setFrameAvatar(frameId: String, avatar: Avatar) = db.tx {
        exec(
            "UPDATE frames SET avatar = ?, avatar_type = ?, avatar_version = ? WHERE device_id = ?",
            avatar.bytes, avatar.contentType, now(), frameId,
        )
    }

    suspend fun frameContacts(frameId: String): List<FrameContactRow> = db.tx {
        query(
            """
            SELECT p.*, c.auto_answer FROM frame_contacts c
            JOIN persons p ON p.id = c.person_id
            WHERE c.frame_id = ? ORDER BY c.position
            """,
            frameId,
        ) { FrameContactRow(toPerson(it), it.getInt("auto_answer") == 1) }
    }

    suspend fun setFrameContacts(frameId: String, contacts: List<FrameContactEntry>) = db.tx {
        exec("DELETE FROM frame_contacts WHERE frame_id = ?", frameId)
        contacts.forEachIndexed { position, contact ->
            exec(
                "INSERT INTO frame_contacts (frame_id, person_id, position, auto_answer) VALUES (?, ?, ?, ?)",
                frameId, contact.personId, position, contact.autoAnswer.toInt(),
            )
        }
    }

    // ---- helpers ----

    private fun Tx.insertDevice(kind: DeviceKind, name: String, personId: String?, tokenHash: String): String {
        val id = Secrets.newId()
        exec(
            "INSERT INTO devices (id, kind, name, person_id, token_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            id, kind.dbName, name, personId, tokenHash, now(),
        )
        return id
    }

    private fun Tx.addFrameContact(frameId: String, personId: String) {
        val position = one("SELECT COALESCE(MAX(position) + 1, 0) FROM frame_contacts WHERE frame_id = ?", frameId) { it.getInt(1) } ?: 0
        exec(
            "INSERT OR IGNORE INTO frame_contacts (frame_id, person_id, position) VALUES (?, ?, ?)",
            frameId, personId, position,
        )
    }

    private fun toPerson(rs: ResultSet) = Person(
        id = rs.getString("id"),
        name = rs.getString("name"),
        displayName = rs.getString("display_name"),
        isAdmin = rs.getInt("is_admin") == 1,
        avatarVersion = rs.longOrNull("avatar_version"),
    )

    private fun toDevice(rs: ResultSet) = Device(
        id = rs.getString("id"),
        kind = deviceKind(rs.getString("kind")),
        name = rs.getString("name"),
        personId = rs.getString("person_id"),
        fcmToken = rs.getString("fcm_token"),
        appVersion = rs.intOrNull("app_version"),
        status = rs.getString("status")?.let { json.decodeFromString(DeviceStatus.serializer(), it) },
        lastSeen = rs.longOrNull("last_seen"),
    )

    private fun toFrame(rs: ResultSet) = PhotoFrame(
        deviceId = rs.getString("device_id"),
        name = rs.getString("name"),
        albumId = rs.getString("album_id"),
        settings = json.decodeFromString(FrameSettings.serializer(), rs.getString("settings")),
        avatarVersion = rs.longOrNull("avatar_version"),
    )

    private fun toPairing(rs: ResultSet) = Pairing(
        id = rs.getString("id"),
        code = rs.getString("code"),
        pollHash = rs.getString("poll_hash"),
        kind = deviceKind(rs.getString("kind")),
        name = rs.getString("name"),
        expiresAt = rs.getLong("expires_at"),
        deviceId = rs.getString("device_id"),
        personId = rs.getString("person_id"),
        token = rs.getString("token"),
    )
}

private fun Boolean.toInt() = if (this) 1 else 0

val DeviceKind.dbName get() = name.lowercase()

private fun deviceKind(value: String) = DeviceKind.valueOf(value.uppercase())
