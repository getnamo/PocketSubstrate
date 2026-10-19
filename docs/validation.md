# Validation and known limits

This records reproducible project evidence, not a developer's machine inventory. Local device names, filesystem paths, software-installation observations, screenshots, and troubleshooting notes belong outside the repository.

## Automated coverage

| Check | Coverage |
| --- | --- |
| `npm test` | Deterministic bundles, real Node backend execution, manifest/path/scope rejection, Node SDK IPC, TLS pairing/authentication and reconnect catch-up |
| `npm run check` | SDK TypeScript contract |
| Android JVM tests | Capability declaration/grant/ban intersection, expanded origin scopes, unsafe paths |
| Android instrumentation | Real isolated Node, denied host files/direct sockets/subprocess execution/native addons, process death and replacement; real WebView-to-Node/broker RPC; browser fetch/workers/WebSockets/WebRTC restrictions; grant revocation and global bans; ownership, staging, rollback, saved-data isolation, interrupted activation; failed startup recovery |
| Publisher emulator driver | Real TLS helper/publisher pairing, first review/launch, live backend scoring change, new location capability held for review, rollback, background/resume and activity recreation |
| Android lint and debug builds | Host, helper, shared IPC, JNI and both packaged ABIs |

The tests were run on an Android 15/API 35 x86_64 emulator using 4 KiB pages. The final run passed **5 desktop tests, 3 JVM tests, 6 instrumentation tests, and the dedicated publisher test**. The publisher test is skipped in the ordinary instrumentation suite and run separately with a real server. Debug builds and Android lint completed successfully; lint retains advisory warnings.

Successful publisher runs observed **1.34–1.40 seconds** from sending a small backend change to the phone reporting it running. These are emulator observations, not a physical-device latency guarantee or a model-generation measurement. The exact commands are in [README](../README.md).

Instrumentation and the dedicated publisher driver mutate test apps, grants, and pairing on the target; use a dedicated test device/emulator. Physical-phone testing has not been completed.

## Architecture evidence

The initial WebView configuration blocked conventional web requests but did not stop WebRTC UDP. The implemented host APK therefore omits INTERNET permission and delegates networking to a separate same-signed APK. Current probes check denial of socket creation and absence of WebRTC network candidates, in addition to browser API restrictions. Adding INTERNET to the host would invalidate that boundary.

Node uses an isolated Android service with a distinct UID per session. Direct access to a host-private file and TCP sockets is denied. Program execution is blocked by a seccomp filter on the Node thread and its descendants; native addons are disabled with `--no-addons`. Three successive service sessions are tested, including Binder death after stop. These checks establish specific behavior, not a complete security audit.

## Remaining gates

- **Runtime maintenance and 16 KiB pages:** the pinned nodejs-mobile 18.20.4 distribution is an obsolete Node baseline with 4 KiB ELF alignment. The host rejects other page sizes. Build and validate a maintained runtime before production distribution; do not infer safety from checksums.
- **Physical phone:** arm64 execution, motion/haptic/GPS behavior, Android permission revocation, notification grant/deny and review links, QR scanning, Wi-Fi reconnection, and sustained reload behavior need a physical-device matrix.
- **Broader failure/security coverage:** destination/redirect/cancellation integration, renderer-hang recovery, stale callback races, resource exhaustion, corrupted catalog recovery, malicious bundle fuzzing, and independent review of the helper/Binder boundaries remain follow-up work.
- **Performance:** cold-start, memory, thermal behavior, APK size optimization, reload distributions, and long-running stress measurements are not acceptance-certified.
- **Lifecycle:** first-pass runtimes and publishing operate in the foreground. Activity recreation retains catalog/data and returns to the launcher; background/resume restarts a selected app with live editing paused. There is no always-on background agent or socket.
- **Compatibility:** pure-JS bundled backend dependencies only; no on-device npm, native addons, shell programs, arbitrary filesystem access, raw sockets, or Node HTTP servers. Browser database/cache APIs are outside the supported persistent-state contract.
- **Data:** code rollback preserves current saved data. There are no data snapshots or migration reversals. Deleting an app removes its supported storage, revisions, and grants.
- **Credential administration:** Forget publisher removes the phone's credential. Desktop tokens persist as hashes in the selected publisher identity directory. A dedicated per-client revocation UI and key-rotation/migration flow are not included.

M0–M4 functionality is implemented, but the original physical-phone acceptance gate remains open. Phone-driven prompting (M5), local llama.cpp generation (M6), and Unreal integration are outside this implementation.
