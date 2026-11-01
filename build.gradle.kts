// All plugins are declared here so the Kotlin and Android plugins share one classloader.
plugins {
    kotlin("jvm") version "2.2.21" apply false
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
