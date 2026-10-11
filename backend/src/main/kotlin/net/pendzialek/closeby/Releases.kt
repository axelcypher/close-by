package net.pendzialek.closeby

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.pendzialek.closeby.api.AppRelease
import java.io.File

/** The APK that CI baked into the image, described by version.json next to it. */
class Releases(private val config: Config) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class VersionFile(val versionCode: Int, val versionName: String, val sha256: String)

    val apk = File(config.apkDir, APK_NAME)

    fun latest(): AppRelease? {
        val versionFile = File(config.apkDir, "version.json")
        if (!versionFile.isFile || !apk.isFile) return null
        val version = json.decodeFromString(VersionFile.serializer(), versionFile.readText())
        return AppRelease(version.versionCode, version.versionName, version.sha256, "${config.publicUrl}$APK_PATH")
    }

    companion object {
        const val APK_NAME = "closeby.apk"
        const val APK_PATH = "/api/app/android/$APK_NAME"
    }
}
