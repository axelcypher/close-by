package net.pendzialek.closeby.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.pendzialek.closeby.BuildConfig
import net.pendzialek.closeby.api.AppRelease
import net.pendzialek.closeby.data.Api
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Installs the APK the backend ships when it is newer than this one. As device
 * owner the install is silent, and so is a self-update on Android 12+ once
 * CloseBy may install apps; otherwise Android asks for confirmation.
 *
 * Never throws: a failed update must not take the app (and a frame's kiosk) down,
 * or the next try, which might bring the fix, never happens.
 */
class Updater(private val context: Context, private val api: Api) {
    private val busy = Mutex()
    private val apk = File(context.cacheDir, "update.apk")

    init {
        // left over if the process died mid-download
        apk.delete()
    }

    /** No confirmation needed, so an update may run while nobody looks. */
    fun canInstallSilently(): Boolean =
        DeviceOwner.isActive(context) || (Build.VERSION.SDK_INT >= 31 && context.packageManager.canRequestPackageInstalls())

    /** Android 12+ could update without asking, but the user has not allowed CloseBy to install apps yet. */
    fun needsInstallPermission(): Boolean = Build.VERSION.SDK_INT >= 31 && !canInstallSilently()

    /** The published release if it is newer than this app, else null (also when offline). */
    suspend fun newerRelease(): AppRelease? =
        runCatching { api.latestRelease() }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    /** Returns a short status for logs and the admin; [mayInstall] is asked again right before the app is replaced. */
    suspend fun checkAndInstall(mayInstall: () -> Boolean = { true }): String {
        val release = runCatching { api.latestRelease() }.getOrNull() ?: return "no release published"
        if (release.versionCode <= BuildConfig.VERSION_CODE) return "up to date (${BuildConfig.VERSION_CODE})"
        return install(release, mayInstall)
    }

    /**
     * Downloads and verifies [release], then hands it to the package installer; the app restarts as the new version.
     * The download takes a while: [mayInstall] (e.g. "no call going on") is checked after it.
     */
    suspend fun install(release: AppRelease, mayInstall: () -> Boolean = { true }): String = busy.withLock {
        val ready = withContext(Dispatchers.IO) { download(release) }
        if (ready != null) return@withLock ready
        try {
            if (!mayInstall()) return@withLock POSTPONED
            withContext(Dispatchers.IO) { commit() }
            "installing ${release.versionName}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "install failed: ${e.javaClass.simpleName} ${e.message}"
        } finally {
            apk.delete()
        }
    }

    /** Null when [apk] holds the verified release, else why not. */
    private fun download(release: AppRelease): String? {
        val problem = try {
            // past the HTTP cache: 50 MB installed once would only push the photos out
            val client = api.client.newBuilder().cache(null).build()
            val failed = client.newCall(Request.Builder().url(downloadUrl(release)).build()).execute().use { response ->
                if (response.isSuccessful) {
                    apk.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                    null
                } else {
                    "download failed: ${response.code}"
                }
            }
            failed ?: if (sha256(apk) == release.sha256.lowercase()) null else "checksum mismatch, update skipped"
        } catch (e: Exception) {
            "download failed: ${e.javaClass.simpleName} ${e.message}"
        }
        if (problem != null) apk.delete()
        return problem
    }

    /**
     * The release names an absolute URL built from the server's PUBLIC_URL. If that points
     * elsewhere than the server this app talks to (a misconfigured PUBLIC_URL), take the path
     * from it and ask our server, so updates still arrive.
     */
    private fun downloadUrl(release: AppRelease): String {
        val server = api.request("/").build().url
        val url = release.url.toHttpUrlOrNull() ?: return api.request(release.url).build().url.toString()
        if (url.host == server.host) return url.toString()
        return api.request(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")).build().url.toString()
    }

    private fun commit() {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // honoured for self-updates with UPDATE_PACKAGES_WITHOUT_USER_ACTION and the install permission granted
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
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
        } catch (e: Exception) {
            // a session left open would hold the copied APK until the next reboot
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    companion object {
        /** [install] found CloseBy in use after the download; try again soon. */
        const val POSTPONED = "postponed, CloseBy is in use"
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
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w("CloseBy", "update needs a confirmation, but it can't be shown: ${it.message}") }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Log.w("CloseBy", "update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
