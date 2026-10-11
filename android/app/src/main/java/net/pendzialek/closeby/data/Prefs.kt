package net.pendzialek.closeby.data

import android.content.Context
import androidx.core.content.edit
import net.pendzialek.closeby.BuildConfig

enum class AppMode { FRAME, CONTACT }

/** Everything the app keeps across restarts. Small enough for SharedPreferences. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("closeby", Context.MODE_PRIVATE)

    var server: String
        get() = prefs.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(value) = prefs.edit { putString("server", value.trim().trimEnd('/')) }

    var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit { putString("token", value) }

    var deviceId: String?
        get() = prefs.getString("deviceId", null)
        set(value) = prefs.edit { putString("deviceId", value) }

    var mode: AppMode?
        get() = prefs.getString("mode", null)?.let(AppMode::valueOf)
        set(value) = prefs.edit { putString("mode", value?.name) }

    /** Frames start with public DNS: grandma's network resolves our domain internally. */
    var forcePublicDns: Boolean
        get() = prefs.getBoolean("forcePublicDns", true)
        set(value) = prefs.edit { putBoolean("forcePublicDns", value) }

    fun signOut() = prefs.edit {
        remove("token")
        remove("deviceId")
        remove("mode")
    }
}
