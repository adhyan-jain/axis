// Test fixture only: a stand-in that has WhatsApp's package name so Axis can be exercised on an emulator.
// It is never part of the shipped app.
plugins { id("com.android.application") }

android {
    namespace = "fake.wa"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.whatsapp"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "test"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
