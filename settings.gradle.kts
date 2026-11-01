pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "axis"
include(":core", ":llm", ":agent")

// The Android app builds only where an Android SDK with platform 36 is present (see local.properties or ANDROID_HOME).
val localProps = java.util.Properties().apply {
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
if (sdkDir != null && File(sdkDir, "platforms/android-36").exists()) include(":app", ":testapps:fakewa")
