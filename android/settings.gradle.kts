// Separate build so the backend image builds without an Android SDK.
// Run with the root wrapper: ./gradlew -p android :app:assembleDebug
rootProject.name = "closeby-android"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // LiveKit's audioswitch fork is only published there
        maven("https://jitpack.io") { content { includeGroup("com.github.davidliu") } }
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

include(":app", ":shared")
project(":shared").projectDir = file("../shared")
