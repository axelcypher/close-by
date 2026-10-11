package net.pendzialek.closeby.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pendzialek.closeby.BuildConfig
import net.pendzialek.closeby.data.Api
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Installs the APK the backend ships when it is newer than this one. As device
 * owner the install is silent; otherwise Android asks for confirmation.
 */
class Updater(private val context: Context, private val api: Api) {
    /** Returns a short status for logs and the admin. */
    suspend fun checkAndInstall(): String = withContext(Dispatchers.IO) {
        val release = runCatching { api.latestRelease() }.getOrNull() ?: return@withContext "no release published"
        if (release.versionCode <= BuildConfig.VERSION_CODE) return@withContext "up to date (${BuildConfig.VERSION_CODE})"

        val apk = File(context.cacheDir, "update.apk")
        api.client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext "download failed: ${response.code}"
            apk.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
        if (sha256(apk) != release.sha256.lowercase()) {
            apk.delete()
            return@withContext "checksum mismatch, update skipped"
        }
        install(apk)
        "installing ${release.versionName}"
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("closeby.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Without device owner Android wants a confirmation; show it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Log.w("CloseBy", "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
