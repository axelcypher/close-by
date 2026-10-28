plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    // needs app/google-services.json: CI writes it from GOOGLE_SERVICES_JSON_B64, locally copy it there
    alias(libs.plugins.google.services)
}

// CI passes these; local builds are debug-signed with versionCode 1
val versionCodeFromCi = System.getenv("CLOSEBY_VERSION_CODE")?.toInt() ?: 1
val versionNameFromCi = System.getenv("CLOSEBY_VERSION_NAME") ?: "0.1.$versionCodeFromCi"
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "net.pendzialek.closeby"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.pendzialek.closeby"
        // Fire OS 7 (Fire HD 8) is API 28
        minSdk = 28
        targetSdk = 36
        versionCode = versionCodeFromCi
        versionName = versionNameFromCi
        buildConfigField("String", "DEFAULT_SERVER", "\"https://closeby.pendzialek.net\"")
        // phones and Fire tablets are ARM; x86 builds of WebRTC only bloat the APK
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "closeby"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.livekit.android)
    implementation(libs.okhttp.doh)
    implementation(libs.zxing.core)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.code.scanner)
    implementation(libs.androidx.fragment)
}
