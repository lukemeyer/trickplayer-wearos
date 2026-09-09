// Plugin versions are pinned once here so each module can `alias(...)` without
// restating them; Gradle refuses to resolve a version for a plugin already on
// the classpath otherwise.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
