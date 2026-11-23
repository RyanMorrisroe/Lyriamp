- Main CI-equivalent checks are defined in `.github/workflows/android-ci.yml`: both unit-test variants, debug lint, debug/release assembly, a 90% core line-coverage verification, and full-app/core Kover HTML reports.
- Kover's debug report remains app-wide and unfiltered, including Compose and platform code. A separate `core` variant enforces at least 90% line coverage for LMS data logic, persistence repositories (excluding the Android Keystore adapter), connection and library ViewModels, and library paging. Keep the report scopes distinct; do not present aggregate app coverage as the core-logic gate. Aim near 100% for behavior-bearing core code without adding tests solely to execute lines.
# Agent Guide

## Project Status

Lyriamp is a native Android remote for Lyrion Music Server (LMS). It supports manual server connections, saved profiles, full-screen Connections/Server Setup/Player Selection/Library/Now Playing/Queue screens, last-session restoration, category-based library browsing, global artist/album/track search, bounded infinite scrolling, artwork, play-now, upcoming queue management, playback/mixer controls, and a Media3 remote session. Queue edits must keep the active track fixed; users can add, reorder, and remove upcoming items. It does not implement LMS sync groups, saved player sets, saved queues, or server discovery. Keep documentation and change summaries aligned with the implementation.

Audio is rendered by LMS players; Lyriamp sends remote commands and does not play audio locally.

## Architecture

- `app/src/main/java/.../data/lms/` contains the LMS JSON-RPC client, server/player/library models, and typed query/command mapping.
- `app/src/main/java/.../data/persistence/` contains saved-server persistence and the Android Keystore credential cipher.
- `app/src/main/java/.../feature/connection/` contains the Compose connection/controller screen and its ViewModel.
- `app/src/main/java/.../feature/library/` contains the paged library/search state, browse navigation, and connected library/player Compose screen.
- `app/src/main/java/.../feature/player/` contains the artwork-first Now Playing screen and queue preview/full-queue management.
- `app/src/main/java/.../AppNavigation.kt` coordinates full-screen destinations and last-session restoration through `LastSessionRepository`.
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
- Main CI-equivalent checks are defined in `.github/workflows/android-ci.yml`: both unit-test variants, debug lint, debug/release assembly, a 90% core line-coverage verification, and full-app/core Kover HTML reports.
- Compose Preview runfiles require Android resources in local unit tests. Preserve `testOptions.unitTests.isIncludeAndroidResources = true` and `android.onlyEnableUnitTestForTheTestedBuildType=false`; the latter restores release unit-test components required by AGP Preview tasks.
- `:app:connectedDebugAndroidTest` requires a connected API 36+ device or emulator and is not part of the emulator-free CI job.
- Kover's debug report remains app-wide and unfiltered, including Compose and platform code. A separate `core` variant enforces at least 90% line coverage for LMS data logic, persistence repositories (excluding the Android Keystore adapter), connection and library ViewModels, and library paging. Keep the report scopes distinct; do not present aggregate app coverage as the core-logic gate. Aim near 100% for behavior-bearing core code without adding tests solely to execute lines.

## Pull Requests

The workflow check is named `Android CI / Build and test`. To make it mandatory, configure the `main` branch ruleset or protection rule to require that check; a workflow file cannot enable repository branch protection by itself.
