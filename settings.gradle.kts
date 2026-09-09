// :core and :tools are plain Kotlin/JVM and need no Android Gradle Plugin, no
// SDK and no device — which is the whole point of keeping :core free of Android
// imports: the pipeline can be exercised against a real Plex server headlessly.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bif-watchface-wearos"

include(":core")
include(":tools")

// Two Android APKs, and they must stay two: Watch Face Format requires
// hasCode="false", and Google requires the watch face bundle be completely
// separate from the bundle carrying app logic.
include(":data")       // Android — disk cache, settings, prefetch worker
include(":app")        // Kotlin — fetches, decides, publishes complications
include(":watchface")  // XML only — renders what the complications hand it
