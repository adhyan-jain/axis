# Releasing Axis

You (the owner) create and keep the release key. Nobody else, including this repo's tooling, should ever hold it.

1. Generate the key once, offline, and back it up somewhere safe. If you lose it you can never update the app for existing users.

   keytool -genkeypair -v -keystore ~/axis-release.jks -alias axis -keyalg RSA -keysize 4096 -validity 10000

2. Create `keystore.properties` in the project root (it is git-ignored):

   storeFile=/absolute/path/to/axis-release.jks
   storePassword=...
   keyAlias=axis
   keyPassword=...

3. Build: `gradle :app:assembleRelease`. The signed APK is in `app/build/outputs/apk/release/`.

4. Publish the signing certificate fingerprint on your website so customers can verify a download:

   apksigner verify --print-certs app-release.apk     (use the SHA-256 line)

5. Before each release: bump `versionCode` and `versionName` in `app/build.gradle.kts`, run all tests, and install the
   release build on a real phone.

Never commit the keystore, `keystore.properties`, or the `.env` file with API keys.
