plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.lukemeyer.bif.tools.PipelineKt") }

// Run from the repo root so local.properties (gitignored, holds the dev Plex
// credentials) resolves the same way it does for the Android modules.
tasks.named<JavaExec>("run") { workingDir = rootDir }

tasks.register<JavaExec>("runDiscover") {
    group = "application"
    description = "Exercise Plex sign-in, discovery and browsing against a real account"
    mainClass.set("com.lukemeyer.bif.tools.DiscoverKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

tasks.register<JavaExec>("runJellyfin") {
    group = "application"
    description = "Exercise the Jellyfin provider end to end against a real server"
    mainClass.set("com.lukemeyer.bif.tools.JellyfinPipelineKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

tasks.register<JavaExec>("runSubs") {
    group = "application"
    description = "Investigate whether embedded subtitle streams can be fetched"
    mainClass.set("com.lukemeyer.bif.tools.SubsKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}



