package net.pendzialek.closeby

data class Config(
    val port: Int = 8080,
    val databasePath: String = "closeby.db",
    /** Base URL the apps use, e.g. https://closeby.pendzialek.net */
    val publicUrl: String = "http://localhost:8080",
    /** Registers a person as admin; registration by admin code is disabled when unset. */
    val adminCode: String? = null,
    val livekitPublicUrl: String = "ws://localhost:7880",
    val livekitInternalUrl: String = "http://localhost:7880",
    val livekitApiKey: String = "devkey",
    val livekitApiSecret: String = "secret",
    val immichUrl: String? = null,
    val immichApiKey: String? = null,
    val firebaseServiceAccountB64: String? = null,
    /** Signed APK plus version.json, baked into the image by CI. */
    val apkDir: String = "apk",
    /** Built web client; not served when missing. */
    val webDir: String = "web",
    val cacheDir: String = "cache",
    val ringTimeoutSeconds: Long = 45,
    /** SHA-256 of the APK signing certificate, for Android App Links (`/.well-known/assetlinks.json`). */
    val androidCertSha256: String = "C1:CC:71:B3:FA:EA:B7:B1:A8:AA:8E:8A:CB:95:47:8E:2D:A6:FB:05:9E:F4:93:6C:26:BB:05:F7:11:38:6D:25",
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun get(name: String) = env[name]?.takeIf { it.isNotBlank() }
            val defaults = Config()
            return Config(
                port = get("PORT")?.toInt() ?: defaults.port,
                databasePath = get("DATABASE_PATH") ?: defaults.databasePath,
                publicUrl = (get("PUBLIC_URL") ?: defaults.publicUrl).trimEnd('/'),
                adminCode = get("CLOSEBY_ADMIN_CODE"),
                livekitPublicUrl = get("LIVEKIT_PUBLIC_URL") ?: defaults.livekitPublicUrl,
                livekitInternalUrl = (get("LIVEKIT_INTERNAL_URL") ?: defaults.livekitInternalUrl).trimEnd('/'),
                livekitApiKey = get("LIVEKIT_API_KEY") ?: defaults.livekitApiKey,
                livekitApiSecret = get("LIVEKIT_API_SECRET") ?: defaults.livekitApiSecret,
                immichUrl = get("IMMICH_URL")?.trimEnd('/'),
                immichApiKey = get("IMMICH_API_KEY"),
                firebaseServiceAccountB64 = get("FIREBASE_SERVICE_ACCOUNT_B64"),
                apkDir = get("APK_DIR") ?: defaults.apkDir,
                webDir = get("WEB_DIR") ?: defaults.webDir,
                cacheDir = get("CACHE_DIR") ?: defaults.cacheDir,
                ringTimeoutSeconds = get("CALL_RING_TIMEOUT_SECONDS")?.toLong() ?: defaults.ringTimeoutSeconds,
                androidCertSha256 = get("ANDROID_CERT_SHA256") ?: defaults.androidCertSha256,
            )
        }
    }
}
