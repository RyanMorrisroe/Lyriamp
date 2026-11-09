# Agent Guide

## Project Status

Lyriamp is a native Android remote for Lyrion Music Server (LMS). This checkout provides a connection-first controller prototype. It supports manual server connections, saved profiles, player selection, playback/mixer controls, and a Media3 remote session. It does not currently implement library browsing, queue management, LMS sync groups, saved player sets, or server discovery. Keep documentation and change summaries aligned with the implementation.

Audio is rendered by LMS players; Lyriamp sends remote commands and does not play audio locally.

## Architecture

- `app/src/main/java/.../data/lms/` contains the LMS JSON-RPC client, server/player models, and player command mapping.
- `app/src/main/java/.../data/persistence/` contains saved-server persistence and the Android Keystore credential cipher.
- `app/src/main/java/.../feature/connection/` contains the Compose connection/controller screen and its ViewModel.
- `app/src/main/java/.../media/` adapts the selected LMS player to a Media3 `MediaSession`.
- Keep network and persistence behavior behind the existing gateway/repository interfaces. Prefer focused Kotlin/JVM tests for deterministic behavior; use Ktor `MockEngine` for HTTP protocol tests.
- Keep dependency versions in `gradle/libs.versions.toml`. Avoid adding a dependency or DI framework when the existing interfaces and application container are sufficient.

## Security Boundaries

- Server entry is manual. Do not add discovery, remote access, or credential logging without an explicit product decision.
- HTTP is unencrypted, including Basic authentication. Preserve the UI warning and prefer HTTPS when the LMS server supports it.
- Saved credentials are encrypted with Android Keystore AES-GCM. Saved-server DataStore files are excluded from Android backup and device transfer; preserve both properties.
- Preserve the API 37 local-network permission flow and do not broaden permissions without need.

## Build and Tests

- Use the Gradle wrapper. The Gradle daemon is configured for JDK 25; Kotlin/Java bytecode targets Java 17. Android compile/target SDK is 37 and `minSdk` is 36.
- On Windows PowerShell, invoke `.\gradlew.bat`; on Linux/macOS, use `bash ./gradlew` because the wrapper is not tracked with an executable bit.
- Local unit tests do not need an emulator: `:app:testDebugUnitTest` and `:app:testReleaseUnitTest`.
- Main CI-equivalent checks are defined in `.github/workflows/android-ci.yml`: both unit-test variants, debug lint, debug/release assembly, and the debug Kover HTML report.
- Compose Preview runfiles require Android resources in local unit tests. Preserve `testOptions.unitTests.isIncludeAndroidResources = true` and `android.onlyEnableUnitTestForTheTestedBuildType=false`; the latter restores release unit-test components required by AGP Preview tasks.
- `:app:connectedDebugAndroidTest` requires a connected API 36+ device or emulator and is not part of the emulator-free CI job.
- Kover currently reports the app module without a core-logic-only filter or coverage threshold. Do not present aggregate coverage as the core-logic gate.

## Pull Requests

The workflow check is named `Android CI / Build and test`. To make it mandatory, configure the `main` branch ruleset or protection rule to require that check; a workflow file cannot enable repository branch protection by itself.
