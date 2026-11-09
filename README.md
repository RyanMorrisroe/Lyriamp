# Lyriamp

Lyriamp is a native Android remote controller for [Lyrion Music Server (LMS)](https://lyrion.org/). The app sends LMS JSON-RPC commands to a server and controls its connected players; audio playback remains on those players.

> **Project status:** This is a functional controller prototype. The supported behavior is listed below; library browsing, queue management, and group management are not implemented yet.

## Features

- Connect manually to an LMS server over HTTP or HTTPS, with optional username and password.
- Test the connection and display the LMS version and available player count.
- List server players, inspect their connection and power state, select a connected player, and control player power.
- View current track title, artist, album, playback position, and duration.
- Play, pause, stop, skip tracks, and seek when supported by the player.
- Set volume, mute, shuffle mode, and repeat mode.
- Save server profiles locally. Credentials are encrypted with Android Keystore AES-GCM.
- Expose the selected LMS player through an Android Media3 media session. Media-session transport commands are forwarded to LMS; the app does not decode or render audio.

## Not Yet Implemented

- Library browsing, search, artwork browsing, or queue editing.
- LMS-synchronized groups or locally saved multi-player sets.
- Automatic server discovery or remote/cloud access.

## Requirements

- An Android device running API 36 or later. The project compiles and targets API 37.
- An LMS server reachable from the device at its JSON-RPC endpoint, normally `http(s)://<host>:9000/jsonrpc.js`.
- For development: Android SDK platform 37, Android Build Tools 36.0.0, and JDK 25 for the Gradle daemon. Kotlin and Java source/target compatibility is Java 17.

No LMS version compatibility matrix has been published yet. A reachable LMS server must expose the JSON-RPC methods used by the app.

## Connect to LMS

1. Open Lyriamp and enter a hostname or IP address without a scheme or path. The default port is `9000`; change it if your LMS server uses another port.
2. Choose HTTP or HTTPS. Enter LMS credentials if authentication is enabled.
3. Tap **Test connection**. On API 37, grant local-network access when Android prompts for it.
4. Select a connected player to show its controls. Use the player list's power switch to change player power.
5. To save a server, tap **Save server**. Select a saved profile later to restore its connection details, then test the connection.

The app does not scan for servers. IPv4, hostnames, and IPv6 addresses can be entered manually.

## Network and Credential Security

- HTTPS is preferred when the LMS server supports it.
- HTTP is unencrypted. Basic authentication over HTTP does not protect the username or password in transit; only use it on a trusted network.
- The Android network security configuration currently permits cleartext traffic to support manually selected HTTP connections. The connection screen warns when HTTP is selected.
- Saved usernames and passwords are encrypted at rest using an Android Keystore AES-GCM key. Saved-server preferences are excluded from cloud backup and device transfer. The app does not automatically discover or contact servers other than the address entered by the user.
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

Local JVM tests cover LMS JSON-RPC request/response mapping, connection ViewModel behavior, and saved-server repository behavior. They do not require a device or emulator.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:koverHtmlReportDebug
```

```bash
bash ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
bash ./gradlew :app:lintDebug
bash ./gradlew :app:koverHtmlReportDebug
```

The HTML report is at `app/build/reports/kover/htmlDebug/index.html`; the XML report is at `app/build/reports/kover/reportDebug.xml`. Coverage is currently informational: the report includes app-module code and is not filtered to core logic, and no coverage threshold is configured.

The instrumented test source set currently contains a small app-context smoke test. Running it requires a connected API 36+ device or emulator:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Continuous Integration

`.github/workflows/android-ci.yml` runs for pull requests targeting `main`, pushes to `main`, and manual dispatch. The `Android CI / Build and test` job runs both JVM test variants, debug lint, debug and release assembly, and generates the debug Kover report. It does not start an emulator; test and coverage reports are uploaded as workflow artifacts.

To make the job a required PR gate, add `Android CI / Build and test` as a required status check in the `main` branch ruleset or branch-protection settings. Repository branch protection cannot be configured from this workflow file.

## Project Layout

- `app/src/main/java/.../data/lms/` - LMS JSON-RPC, player models, and command mapping.
- `app/src/main/java/.../data/persistence/` - saved server profiles and encrypted credentials.
- `app/src/main/java/.../feature/connection/` - connection and player-control UI/ViewModel.
- `app/src/main/java/.../media/` - Media3 remote player and playback service.
- `app/src/test/` - local JVM tests.
- `app/src/androidTest/` - Android instrumented tests.

## License

See [LICENSE](LICENSE).
