# PocketSubstrate agent guide

Read [README.md](README.md), [the implementation plan](docs/android-first-plan.md), [the authoring contract](docs/agent-authoring.md), and [validation status](docs/validation.md). The originating request is [issue #1](https://github.com/getnamo/PocketSubstrate/issues/1).

## Confirmed scope

- Native Android substrate; Unreal is out of scope.
- Sub-apps have embedded Node.js backends and web frontends. Embedded Node is required from M0; do not replace it with a WebView-only demo.
- A desktop agent publishes changes over local Wi-Fi. Native APIs require per-sub-app grants, with host-wide capability bans.
- M0–M4 cover the runtime, app lifecycle, capabilities, live publishing, examples, and ready/review flow. Phone-driven generation and llama.cpp remain M5/M6.

## Repository map

| Path | Responsibility |
| --- | --- |
| `android/app` | Compose host, review UI, AtomicFile catalog, capability broker, WebView, isolated Node service and JNI |
| `android/network` | Separate signed APK for paired TLS publishing and brokered HTTPS |
| `android/ipc` | Signature-protected host/helper Binder interfaces |
| `packages/sdk` | Web SDK, Node bootstrap, TypeScript API declarations |
| `tools/publisher` | Deterministic bundler and TLS/WebSocket desktop CLI |
| `tools/prepare-android.mjs` | Checksummed mobile Node download and generated SDK/example assets |
| `tools/emulator-e2e.mjs` | Real publisher-to-emulator acceptance driver |
| `examples` | Orbit Tap game and Field Notes location/storage example |
| `schemas`, `tests` | Manifest contract and desktop protocol/bundler tests |

## Architecture findings to preserve

1. **The host must have no INTERNET permission.** A WebRTC probe bypassed WebView network blocking and CSP in the initial design. Networking moved to `android/network`, a separate UID/APK protected by a signature permission. Install both APKs with the same signing key. Do not move networking back into the host without re-establishing the OS boundary.
2. **Node runs under an isolated UID.** `bindIsolatedService` creates a fresh process per session. Backend source and pipe descriptors cross Binder; host storage and credentials do not. Seccomp denies execution of subprocess programs, and Node starts with `--no-addons`. Direct host-file/socket access and repeated process teardown are instrumented. This is experimental isolation, not an audited hostile-code sandbox.
3. **The pinned mobile runtime is a release blocker.** The available Node 18.20.4 binary has 4 KiB ELF alignment and an obsolete Node baseline. Only 4 KiB-page arm64/x86_64 devices are supported. The bootstrap verifies archive and license checksums. A maintained, validated 16 KiB-compatible build is required before production distribution.
4. **Authorize every native operation.** Check manifest scope, app grant, global ban, Android permission, and session/lifecycle. App identity comes from the host session. Frontend and backend use the same broker. Revoke/bans replace active sessions; asynchronous results recheck authorization.
5. **Separate app origins.** Web content uses `https://<app-id>.substrate.invalid`, host-owned CSP, constrained assets, and an origin/main-frame checked message listener. No file/content URLs, direct media/location permission grants, frames, workers, cookies, or DOM storage. Supported persistent state uses native app-scoped storage.
6. **Delivery is not approval.** Content hashes identify immutable revisions; paired certificate fingerprints own app IDs. New capabilities/scopes return to review. Only explicit live editing within existing grants can activate while that app's foreground session is active. Identical active/pending deliveries are idempotent; there is no remote grant or activate command.
7. **Reload replaces both runtimes.** Atomic catalog activation is confirmed after Node and frontend readiness. Failed/incomplete startup restores the previous pointer and returns the candidate to review. Native close/rollback remain available. Code rollback never reverses saved data.
8. **Foreground delivery only.** Backgrounding closes runtimes/subscriptions and disconnects publishing. Reconnection catches up. Ready notifications open native review; the ready queue works without notification permission. Resuming does not silently re-enable live editing.

## Build and validation

From the root: `npm ci`, `npm run prepare:android`, `npm test`, and `npm run check`. From `android`: `./gradlew :app:assembleDebug :network:assembleDebug :app:testDebugUnitTest :app:lintDebug :network:lintDebug`. Use `gradlew.bat` on Windows. After SDK/bootstrap changes, rerun `prepare:android` before Android tests.

With a connected 4 KiB-page device, run `./gradlew :app:connectedDebugAndroidTest` from `android`; it installs the helper. For full publishing acceptance, build `:app:assembleDebugAndroidTest` and run `npm run test:emulator-e2e` from the root with one dedicated emulator. The latter installs APKs and replaces the `e2e-game` test app/pairing. See README for prerequisites and validation.md for coverage and gaps.

Prioritize meaningful tests for permission boundaries, ownership/path validation, activation recovery, runtime identity, and teardown. Emulator results do not establish physical sensor behavior, OEM lifecycle behavior, or production security. Do not claim the physical M4 acceptance gate has passed until it has.

## Portable setup and privacy

Use `ANDROID_HOME` or ignored `local.properties` for SDK discovery and `JAVA_HOME` for Java. Required versions belong in build files and generic setup docs.

Keep personal paths, usernames, installed-software inventories, device identifiers, environment observations, and troubleshooting notes **outside the repository**, including outside ignored repository folders. Never commit credentials, pairing links, signing keys, generated APKs, SDK directories, or dependency binaries. Generated build inputs may use ignored directories; they are not a place for local setup notes.

Keep this guide and validation status current when architecture or evidence changes. For documentation edits, check links and `git diff --check`; do not invent test runs.
