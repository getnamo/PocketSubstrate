# PocketSubstrate
An experimental Android substrate for agent-created apps: embedded Node.js backends, web frontends, native capability grants, and live edits from a desktop over Wi-Fi.

The first pass implements M0–M4 of the [Android-first plan](docs/android-first-plan.md). It includes a native launcher and source/permission review, two example apps, transactional revisions and rollback, a desktop publisher, and foreground lifecycle recovery. Unreal, phone-driven generation, and llama.cpp are later work.

**Experimental runtime:** the pinned mobile Node 18.20.4 build is obsolete and requires a **4 KiB-page** Android device. This is a development prototype, not a production security release. Emulator checks pass; physical-phone acceptance remains pending. See [validation and limitations](docs/validation.md).

## Build and install

Requirements:

- Desktop Node.js 22 or later and npm.
- JDK 17 or later compatible with Gradle 8.13; select it with `JAVA_HOME`.
- Android SDK platform 36, Build Tools 35.0.1, NDK 27.2.12479018, CMake 3.22.1, and platform-tools. Set `ANDROID_HOME` or an ignored `android/local.properties` with `sdk.dir`.
- Android 10/API 29 or later, arm64-v8a or x86_64, 4 KiB memory pages, and an updated Android System WebView. Check the page size with `adb shell getconf PAGE_SIZE` (must report `4096`).

From the repository root:

```sh
npm ci
npm run prepare:android
cd android
./gradlew :app:assembleDebug :network:assembleDebug
cd ..
adb install -r android/network/build/outputs/apk/debug/network-debug.apk
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat` instead of `./gradlew`. The wrapper downloads pinned Gradle; preparation downloads and verifies the Node archive and license, then generates SDK/example assets. Run preparation again after editing SDK or example files. Native binaries, generated assets, build output, and local SDK settings are ignored.

**Install both APKs, signed with the same key.** PocketSubstrate has no INTERNET permission. Its separate network helper holds that permission and exposes a signature-protected service; it has no launcher icon. This split prevents generated WebView content from bypassing native network grants through WebRTC. Normal debug builds use the same debug signing key.

Open PocketSubstrate, select **Ready**, inspect an included example, choose its capabilities, and launch. Orbit Tap uses Node for scoring and saved best scores. Field Notes demonstrates app storage and optional approximate location. Android location permission and the sub-app grant are separate decisions.

## Publish changes over Wi-Fi

Run from the root, with desktop and phone on the same reachable network:

```sh
npm run publish -- examples/touch-game --host YOUR_LAN_IP
```

Allow inbound TCP 9443 for the publisher on your private network if needed. On the phone, open **Connect** and scan the QR code or paste its pairing link. Codes expire after five minutes and can be used once. Restart the publisher for a new code. Its certificate identity and hashed client credentials are saved outside the checkout, under the user's `.pocketsubstrate/publisher` directory; `--identity` selects another private directory.

The publisher watches the app and sends each changed bundle. Review a new app in **Ready**, then use **Manage → Allow live edits** and launch it to opt into automatic reloads within existing grants. New capabilities or expanded HTTPS scopes require review. Keep the host foregrounded to receive changes. After backgrounding, close and launch the app again to re-enable its live session.

Both JS runtimes restart on updates. SDK storage persists; rollback restores code, not data. The native **Close app** and **Rollback** controls remain available. Installed apps work offline.

For file import instead of publishing:

```sh
npm run bundle -- examples/touch-game --out dist/touch-game.psub
```

Transfer the file to the phone and select **Apps → Import bundle**. Imported and paired-source apps have separate ownership; use distinct IDs when changing delivery source, or explicitly delete the old app and its data first.

## Authoring and tests

Read the [sub-app authoring contract](docs/agent-authoring.md) for the manifest, SDK, limits, permissions, and Node compatibility. Agents should begin with [AGENTS.md](AGENTS.md). Machine-specific setup notes and credentials belong outside this repository.

```sh
npm test
npm run check
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :network:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

The instrumentation command requires one connected supported device and installs the helper. It exercises Node isolation, browser networking restrictions, broker grants/bans, real frontend/backend RPC, and revision recovery. The publishing test skips in that suite unless supplied with the live-publisher arguments.

For the complete publisher-to-emulator test, use one dedicated 4 KiB-page emulator:

```sh
cd android
./gradlew :app:assembleDebug :network:assembleDebug :app:assembleDebugAndroidTest
cd ..
npm run test:emulator-e2e
```

Run these from the repository root (the first `cd` enters `android`). The driver installs all APKs, replaces the emulator's test pairing and `e2e-game` app, creates a temporary publisher identity, and checks TLS pairing, live backend edits, capability review, rollback, and lifecycle recovery. It uses the emulator's desktop alias `10.0.2.2`; use the normal publisher for physical phones.
