package net.pendzialek.closeby.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.pendzialek.closeby.BuildConfig
import net.pendzialek.closeby.api.AppRelease
import net.pendzialek.closeby.data.Api
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Installs the APK the backend ships when it is newer than this one. As device
 * owner the install is silent, and so is a self-update on Android 12+ once
 * CloseBy may install apps; otherwise Android asks for confirmation.
 */
class Updater(private val context: Context, private val api: Api) {
    private val busy = Mutex()

    /** No confirmation needed, so an update may run while nobody looks. */
    fun canInstallSilently(): Boolean =
        DeviceOwner.isActive(context) || (Build.VERSION.SDK_INT >= 31 && context.packageManager.canRequestPackageInstalls())

    /** Android 12+ could update without asking, but the user has not allowed CloseBy to install apps yet. */
    fun needsInstallPermission(): Boolean = Build.VERSION.SDK_INT >= 31 && !canInstallSilently()

    /** The published release if it is newer than this app, else null (also when offline). */
    suspend fun newerRelease(): AppRelease? =
        runCatching { api.latestRelease() }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    /** Returns a short status for logs and the admin. */
    suspend fun checkAndInstall(): String {
        val release = runCatching { api.latestRelease() }.getOrNull() ?: return "no release published"
        if (release.versionCode <= BuildConfig.VERSION_CODE) return "up to date (${BuildConfig.VERSION_CODE})"
        return install(release)
    }

    /** Downloads and verifies [release], then hands it to the package installer; the app restarts as the new version. */
    suspend fun install(release: AppRelease): String = busy.withLock { withContext(Dispatchers.IO) { downloadAndInstall(release) } }

    private fun downloadAndInstall(release: AppRelease): String {
        val apk = File(context.cacheDir, "update.apk")
        api.client.newCall(Request.Builder().url(release.url).build()).execute().use { response ->
            if (!response.isSuccessful) return "download failed: ${response.code}"
            apk.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
        }
        if (sha256(apk) != release.sha256.lowercase()) {
            apk.delete()
            return "checksum mismatch, update skipped"
        }
        commit(apk)
        return "installing ${release.versionName}"
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // honoured for self-updates with UPDATE_PACKAGES_WITHOUT_USER_ACTION and the install permission granted
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
