# Lyriamp

Lyriamp is a native Android remote controller for [Lyrion Music Server (LMS)](https://lyrion.org/). The app sends LMS JSON-RPC commands to a server and controls its connected players; audio playback remains on those players.

> **Project status:** Lyriamp connects to LMS, browses its media library, and controls a selected player. It uses separate full-screen connection, setup, player-selection, library, and Now Playing screens. Queue editing and synchronized player groups are not implemented yet.

## Features

- Connect manually to an LMS server over HTTP or HTTPS, with optional username and password.
- Automatically save a server profile after a successful connection and reconnect to saved profiles from the Connections screen.
- List server players, inspect their connection and power state, select a connected player, and control player power.
- Browse Home, artists, albums, genres, songs, and playlists in tabs with bounded infinite scrolling.
- Choose artwork-grid, compact, or comfortable layouts for artists, albums, and genres; songs, playlists, and search offer compact and comfortable lists. Layout choices are remembered separately by type.
- Search artists, albums, and tracks across the library, and view LMS artwork when available.
- Choose light, dark, or system appearance under Settings; colors follow the device's Android palette.
- Play a track, album, or playlist now on the selected LMS player; playback remains on that player.
- View current track title, artist, album, playback position, and duration.
- Open a full-screen Now Playing view with artwork, seek, transport, volume, shuffle, and repeat controls.
- Play, pause, stop, skip tracks, and seek when supported by the player.
- Set volume, mute, shuffle mode, and repeat mode.
- Save server profiles locally. Credentials are encrypted with Android Keystore AES-GCM.
- Restore the last server, player, active tab, search, and per-tab browse locations and positions on launch when available. If the saved player is unavailable, choose another connected player.
- Switch players without leaving the current library location.
- Expose the selected LMS player through an Android Media3 media session. Media-session transport commands are forwarded to LMS; the app does not decode or render audio.

## Not Yet Implemented

- Queue editing, adding items without replacing the current playlist, and saved queues.
- LMS-synchronized groups or locally saved multi-player sets.
- Automatic server discovery or remote/cloud access.

## Requirements

- An Android device running API 36 or later. The project compiles and targets API 37.
- An LMS server reachable from the device at its JSON-RPC endpoint, normally `http(s)://<host>:9000/jsonrpc.js`.
- For development: Android SDK platform 37, Android Build Tools 36.0.0, and JDK 25 for the Gradle daemon. Kotlin and Java source/target compatibility is Java 17.

No LMS version compatibility matrix has been published yet. A reachable LMS server must expose the JSON-RPC methods used by the app.

## Connect to LMS

1. From Connections, choose **Add server** and enter a hostname or IP address without a scheme or path. The default port is `9000`; change it if your LMS server uses another port.
2. Choose HTTP or HTTPS, enter LMS credentials if authentication is enabled, and connect. On API 37, grant local-network access when Android prompts for it. Successful connections are saved as profiles.
3. Select a connected player to open the library. Choose a tab or search artists, albums, and tracks. Use the layout controls in each view to choose how to browse. Select a track to play it; use an album or playlist's **Play** action to replace the current playlist and start playback.
4. Use the player strip for transport and seek controls. Open the track details for full-screen Now Playing, including volume, shuffle, and repeat. Change players without losing the current library location; use **Settings → Connections** to switch servers and **Settings → Display** to change appearance.
5. Select a saved server profile to reconnect immediately. On later launches, Lyriamp restores the last session automatically when its server and player are available. The saved-session snapshot contains no credentials.

The app does not scan for servers. IPv4, hostnames, and IPv6 addresses can be entered manually.

## Network and Credential Security

- HTTPS is preferred when the LMS server supports it.
- HTTP is unencrypted. Basic authentication over HTTP does not protect the username or password in transit; only use it on a trusted network.
- The Android network security configuration currently permits cleartext traffic to support manually selected HTTP connections. The connection screen warns when HTTP is selected.
- Saved usernames and passwords are encrypted at rest using an Android Keystore AES-GCM key. Saved-server preferences are excluded from cloud backup and device transfer. The app does not automatically discover or contact servers other than the address entered by the user.
- Artwork is requested from the selected LMS server using its HTTP authentication header when the image is served by that same origin. Credentials are not added to artwork URLs or sent to external artwork hosts.
- On API 37, Android's local-network permission is requested before connecting. Denying it prevents local LMS connections.

## Build

The repository includes a Gradle wrapper. Android Studio can open the project and sync the Gradle build. On Windows, use the batch wrapper; on Linux/macOS, invoke the non-executable wrapper script through Bash.

```powershell
.\gradlew.bat :app:assembleDebug
```

```bash
bash ./gradlew :app:assembleDebug
```

The debug APK is written under `app/build/outputs/apk/debug/`. Release assembly is included in CI as a packaging check; release signing is not configured in this repository.

## Tests and Coverage

Local JVM tests cover LMS JSON-RPC and library paging/search/play-now mapping, browse/session restoration state, connection behavior, and saved-server persistence. They do not require a device or emulator.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:koverHtmlReportDebug
.\gradlew.bat :app:koverVerifyCore :app:koverHtmlReportCore
```

```bash
bash ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
bash ./gradlew :app:lintDebug
bash ./gradlew :app:koverHtmlReportDebug
bash ./gradlew :app:koverVerifyCore :app:koverHtmlReportCore
```

The unfiltered app report is at `app/build/reports/kover/htmlDebug/index.html`; its XML report is at `app/build/reports/kover/reportDebug.xml`. It includes Compose and platform code and remains informational. The separate core report is at `app/build/reports/kover/htmlCore/index.html`, and `:app:koverVerifyCore` enforces a 90% minimum line-coverage floor.

The core gate includes LMS data logic, persistence repositories (excluding the Android Keystore adapter), connection and library ViewModels, and library paging. Compose screens, app/navigation wiring, and platform adapters are outside this JVM coverage gate; they remain visible in the full report and need appropriate UI or platform-level checks. Aim as close to 100% as practical for behavior-bearing core code, using tests that verify outcomes and meaningful edge cases rather than merely executing lines.

The instrumented tests include an app-context smoke test and library layout, display-settings, and large-library paging checks. Running them requires a connected API 36+ device or emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Continuous Integration

`.github/workflows/android-ci.yml` runs for pull requests targeting `main`, pushes to `main`, and manual dispatch. The `Android CI / Build and test` job runs both JVM test variants, debug lint, debug and release assembly, verifies the 90% core line-coverage floor, and generates both full-app and core Kover reports. It does not start an emulator; test and coverage reports are uploaded as workflow artifacts.

To make the job a required PR gate, add `Android CI / Build and test` as a required status check in the `main` branch ruleset or branch-protection settings. Repository branch protection cannot be configured from this workflow file.

## Project Layout

- `app/src/main/java/.../data/lms/` - LMS JSON-RPC, player models, and command mapping.
- `app/src/main/java/.../data/persistence/` - saved server profiles and encrypted credentials.
- `app/src/main/java/.../feature/connection/` - connection and player-control UI/ViewModel.
- `app/src/main/java/.../feature/library/` - LMS library navigation, paged search/browsing, and connected player controls.
- `app/src/main/java/.../media/` - Media3 remote player and playback service.
- `app/src/test/` - local JVM tests.
- `app/src/androidTest/` - Android instrumented tests.

## License

See [LICENSE](LICENSE).
