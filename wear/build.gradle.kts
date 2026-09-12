plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9's built-in Kotlin support brings its own Kotlin Gradle Plugin
    // (2.2.10) — no org.jetbrains.kotlin.android plugin needed anymore.
    // The Compose compiler plugin is still separate; pin it to the same
    // Kotlin version AGP uses internally to avoid a classpath conflict.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
