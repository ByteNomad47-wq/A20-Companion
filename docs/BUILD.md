# Build, verify and sign

Use a **complete JDK 21**, Python 3.12+ for helper scripts, and Android SDK platform
37.0/API37 with build tools37. The project pins AGP9.3.1, Kotlin2.4.21,
Gradle9.6.0 and Material3 1.5.0-beta02 in its version catalog/wrapper.
Compose BOM is2026.09.00. No dynamic dependency versions are used.

The Android/Kotlin configuration explicitly opts out of AGP's built-in Kotlin
for this pinned compatible toolchain; migration is separate future build work.
The beta Material release supplies Expressive APIs and is an intentional preview
dependency. Formatting uses ktfmt0.64 Kotlin style; it is not required to build.

## SDK

Install platform37.0/API37 and build tools37 through Android Studio, or on Linux:

```sh
python3 scripts/bootstrap_android.py
export ANDROID_HOME="$PWD/.tools/android-sdk"
```

The helper downloads a pinned official command-line-tools archive, validates the
official archive checksum and invokes the Android CLI with metrics disabled.
It does not create an emulator, connect to a phone or install the app. Standard
Android SDK license terms apply. A local `local.properties` with `sdk.dir=...`
can alternatively select your SDK; do not commit it.

## Tests and APKs

```sh
./gradlew :core:protocol:test :core:device:testDebugUnitTest :app:testDebugUnitTest :app:lintRelease
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
python3 scripts/check_repository.py
python3 scripts/check_repository.py --apk app/build/outputs/apk/release/app-release-unsigned.apk
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (Android debug signer).
Default release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.
Release builds shrink code and resources. Published previews should use signed release builds. Do not install an unsigned APK on your phone.

Gradle artifact verification is committed in `gradle/verification-metadata.xml`.
Review dependency/version changes before regenerating checksums with
`--write-verification-metadata sha256`; normal builds should not regenerate it.
The wrapper distribution SHA-256 is pinned too.

## Local signing

Release signing is optional and reads these environment variables:

- `A20_SIGNING_STORE`: absolute path to a keystore outside the checkout.
- `A20_SIGNING_PASSWORD`: password, supplied privately; never commit it.
- Alias is fixed to `a20-companion`.

With those variables set, `./gradlew :app:assembleRelease` creates
`app-release.apk`. Without them CI builds unsigned. Keep the same key across
updates. Keep private signing material outside this repository.

CI builds/tests/lints an unsigned release and uploads it as a workflow artifact.
CI never gets the private signing key. A separate release workflow verifies the committed,
already signed APK checksum and publishes it with GitHub’s built-in token.

## Current validation boundaries

See [TESTING](TESTING.md) and the protocol research validation records. Builds, unit tests and
emulator checks are distinct from the pending physical A20/Samsung checks. Neither
the APK hash nor a successful compilation qualifies the mode-setting protocol.

## Historical qualification build

Historical APKs are not included in this source export. Its temporary build
type was retired after physical qualification. Current debug/release builds share
the guarded mode-control implementation; no qualification build flag is required.
