plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lukemeyer.bif.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lukemeyer.bif.app"
        minSdk = 34          // Wear OS 5
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation(project(":data"))
    implementation(libs.androidx.core.ktx)
    // NB: watchface-complications-data-source is NOT part of the deprecated
    // androidx.wear.watchface watch-face surface. The deprecation covers
    // watchface, watchface-client, watchface-style, watchface-editor and
    // friends; data sources remain the supported way to feed a watch face.
    implementation(libs.watchface.complications.data.source.ktx)

    // Compose for Wear OS — the configuration UI only. The complication
    // services and the watch face itself involve none of this.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    // The system's own text input — voice, handwriting or a tiny keyboard,
    // whichever the watch offers. Needed for exactly one field: a Jellyfin
    // server address, which has no account service to discover it from.
    implementation(libs.wear.input)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
}
