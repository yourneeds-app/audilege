# Audilege builds and updates

The Android package ID is `app.yourneeds.foliocue`. Keep it and the signing certificate from version 0.1.1 when replacing an installed copy. Increment `versionCode` in `app/build.gradle` for every installable release.

Run `gradle testDebugUnitTest assembleDebug lintDebug` with JDK 17 or 21, Android SDK 35 and Gradle 8.14. A private CI build can verify source and tests. Release APKs are signed on the existing build machine with the local key. Keep the private key outside the repository.

Verify the candidate using `apksigner verify --print-certs` and compare its certificate digest with the preceding published APK. Use `aapt dump badging` to verify the package ID and increasing version code. Attach the signed APK and its SHA-256 digest to a GitHub release. A developer push and an APK release are separate steps.

Version 0.4.0 reads the latest published GitHub release from Settings → App updates. A public release and APK can be checked and downloaded without a GitHub account. The automatic check can be disabled. A successful automatic check is cached for six hours; a manual check always refreshes the result. For a private channel, existing users can configure optional read-only GitHub access or visit Releases in their signed-in browser.

Name the APK `Audilege-<version>-debug.apk` for compatibility with the 0.3.x updater and tag the release `v<version>`. Open the asset on Android to confirm installation. Devices running 0.3.2 can reach the release in their browser using **Check for updates** or upgrade directly from the signed APK.
