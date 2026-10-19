# Authoring a PocketSubstrate sub-app

Start by copying `examples/touch-game` or `examples/field-marker`. Give the app a new lowercase ID in `manifest.json`. The included host samples use separate `sample-*` IDs, so publishing the examples does not overwrite their bundled copies.

## Contract

Each app contains `manifest.json`, `web/index.html`, web assets, and `backend/main.cjs`. The manifest follows [the schema](../schemas/manifest.schema.json). List every native capability with a plain-language reason. The user can deny any capability; handle rejection and keep the rest of the app usable.

Build web assets ahead of publishing. The publisher bundles the backend and its pure-JS dependencies with esbuild for Node 18. It does not run npm installation on the phone. Keep the backend under 128 KiB after bundling, each web file under 2 MiB, the bundle payload under 8 MiB, and the file count under 200. Bundle paths must be simple relative paths without traversal or symbolic links.

Load the host SDK before your frontend script:

```html
<script src="/__substrate.js" defer></script>
<script src="app.js" defer></script>
```

Use external JS files. Inline scripts, inline event handlers, remote scripts, workers, frames, and direct browser network requests are blocked. Cookies and DOM storage are disabled. Use the native storage SDK for supported durable state; browser database/cache APIs are outside the sub-app compatibility contract. All WebView resources are restricted to that app's synthetic origin.

Export backend methods from CommonJS:

```js
module.exports = {
  async increment({ amount = 1 }) {
    const next = (Number(await substrate.storage.get('count')) || 0) + amount;
    await substrate.storage.set('count', next);
    return { count: next };
  }
};
```

Call them from the frontend:

```js
try {
  const result = await substrate.backend.call('increment', { amount: 1 });
  document.querySelector('#count').textContent = result.count;
} catch (error) {
  document.querySelector('#status').textContent = `${error.code}: ${error.message}`;
}
```

Keep messages small and JSON-serializable. Host requests are bounded, time out, and are cancelled when a runtime session ends. A Node output line must be under 64 KiB and a web request under 32 KiB. More than 64 concurrent host calls or excessive backend output stops the session. Do not write to stdout directly; use `console.log`, which becomes a diagnostic event. Sharing diagnostic output with the publisher is off by default.

## Native capabilities

The same `substrate` API is provided to the web frontend and Node backend, except that `backend.call` is frontend-only. Type definitions are in `packages/sdk/index.d.ts`.

| API | Capability | Behavior |
| --- | --- | --- |
| `storage.get(key)` / `storage.set(key, value)` | `storage` | App-scoped JSON data, 128 KiB total, keys 1–128 characters |
| `haptics.pulse(milliseconds)` | `haptics` | Pulse clamped to 1–200 ms |
| `motion.start()` / `motion.stop()` | `motion` | Accelerometer events, throttled to about 10 Hz; listen with `substrate.on('motion', callback)` |
| `location.current()` | `location` | One foreground approximate fix; host Android permission must also be granted |
| `network.fetch(url)` | `network` | HTTPS GET to a declared exact origin; returns `{status, body}`, maximum 16 KiB; redirects rechecked against the grant |
| `capabilities()` | none | Reports effective sub-app grants and global bans; OS permission can still prevent a call |

For HTTPS access, declare `{"name":"network","reason":"Read the weather API","origins":["https://api.example.com"]}`. Wildcards, HTTP, credentials in URLs, and arbitrary sockets are unsupported. Expanding the origin list requires a new grant. Browser networking is disabled at the host's Android permission boundary. The separate signed helper performs authorized network calls.

Errors include `PERMISSION_DENIED`, `GLOBALLY_BLOCKED`, `OS_PERMISSION_DENIED`, `UNSUPPORTED`, `TIMEOUT`, `INVALID_REQUEST`, and backend-specific failures. Granting a capability to one app does not grant it to another. The publisher cannot approve capabilities.

## Publish and iterate

From the repository root:

```sh
npm ci
npm run bundle -- examples/touch-game --out dist/touch-game.psub
npm run publish -- examples/touch-game --host YOUR_LAN_IP
```

The bundle command creates an importable file. The publish command starts a TLS publisher and watches for changes. Pair the phone from Connect with its QR code or link. Pairing codes are single-use and expire after five minutes. The publisher identity is stored outside the checkout in the user's configuration directory; `--identity` can select another private directory. Do not share or commit a pairing link, private key, or client credential.

New apps and capability expansions go to Ready for native review. Users can enable live edits in an installed app's Manage screen. While that app is running in an active session, edits within existing grants reload automatically. The host must remain in the foreground; after backgrounding, launch the app again to opt back into live editing. On reconnect the latest bundle is delivered again; identical active/pending revisions are idempotent.

For a custom desktop integration, see the [publisher protocol](publisher-protocol.md). The CLI already handles bundling, identity, transport, and watching; a coding agent can simply edit the app directory.

Every activation restarts Node and the web document. Heap state, timers, and sensor subscriptions do not survive reload. Persist useful state with the SDK. A previous code revision can be restored; this does not undo changes made to persisted data. Keep storage backward-compatible until migration/snapshot support exists.

The current runtime supports pure-JS Node logic and core modules within the isolated process's privileges. It does not support native addons, shell execution, arbitrary file access, Node HTTP servers, or raw sockets. Request any new native API through the capability broker rather than working around the permission model.
