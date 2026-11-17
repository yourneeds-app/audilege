# Audilege builds and updates

The Android package ID is `app.yourneeds.foliocue`. Keep it and the signing certificate from version 0.1.1 when replacing an installed copy. Increment `versionCode` in `app/build.gradle` for every installable release.

Run `gradle testDebugUnitTest assembleDebug lintDebug` with JDK 17 or 21, Android SDK 35 and Gradle 8.14. A private CI build can verify source and tests. Release APKs are signed on the existing build machine with the local key. Keep the private key outside the repository.

Verify the candidate using `apksigner verify --print-certs` and compare its certificate digest with the preceding published APK. Use `aapt dump badging` to verify the package ID and increasing version code. Attach the signed APK and its SHA-256 digest to a GitHub release. A developer push and an APK release are separate steps.

For the private test channel, sign in to GitHub in the Android browser and open the repository’s Releases page. Download the APK and open it in Android to install the update. Starting with Audilege 0.3.0, Settings → App updates can check the latest private release. Use a fine-grained GitHub token limited to this repository with read access to Contents.
