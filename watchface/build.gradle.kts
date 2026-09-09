plugins {
    alias(libs.plugins.android.application)
}

// Resource-only by construction. Watch Face Format requires hasCode="false", and
// Google requires the watch face bundle be completely separate from the bundle
// holding app logic — hence a second APK rather than a second source set.
//
// This is the Pebble watch/phone split reborn as an APK boundary: one side
// decides and fetches, the other only draws. The channel is the complication API
// instead of chunked AppMessage over BLE, which is a considerably better deal.
android {
    namespace = "com.lukemeyer.bif.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lukemeyer.bif.watchface"
        minSdk = 34          // Wear OS 5 == WFF version 2
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
