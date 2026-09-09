plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core must stay free of Android imports. That is not tidiness — it is what
// lets :tools exercise the shipping pipeline against a real Plex server with no
// device, emulator or SDK involved, the same way the Pebble project's bif.js and
// render.js ran under both PebbleKit JS and Node.
dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
