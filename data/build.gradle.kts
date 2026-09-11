plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The Android half of the pipeline: everything that needs a Context. :core stays
// clean of this so it can keep running headlessly under :tools.
android {
    namespace = "com.lukemeyer.trickplayer.data"
    compileSdk = 36
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
}
