# Desktop publishing protocol v1

Use `npm run publish` for the supported implementation. This document describes the wire contract for other desktop agents. Neither this protocol nor the publisher can grant permissions or remotely approve an app.

## Pairing and transport

The phone receives a `pocketsubstrate://pair` URI through native QR scanning or paste. Its query parameters are:

- `endpoint`: a percent-encoded HTTPS origin with the publisher port.
- `fingerprint`: lowercase SHA-256 of the publisher's DER leaf certificate.
- `secret`: a 32-byte cryptographically random single-use secret encoded as lowercase hex; expires after five minutes.

The helper verifies the exact pinned certificate and its validity, then sends `POST /pair` with `Authorization: Bearer <secret>`. The response is `{"protocol":1,"token":"<64 hex characters>"}`. The phone stores the token privately; the desktop stores only its SHA-256 hash. The explicit certificate pin provides publisher identity for this local self-signed TLS transport. Ordinary brokered HTTPS calls use normal certificate/hostname validation, not this pairing trust policy.

The phone opens a TLS WebSocket at `/session`, supplying the token in the Authorization header. An unknown token receives HTTP 401. The server first sends `{"protocol":1,"type":"hello"}` and then its latest bundle, if any. The phone reconnects while foregrounded with bounded backoff. There is no inbound phone listener or background-delivery guarantee.

Treat pairing links, private keys, and phone tokens as credentials. Keep identities outside the checkout. The current CLI keeps one latest app bundle per server process; multiple apps can be imported or delivered in separate publishing sessions using the same persistent publisher identity.

## Publish envelope

```json
{
  "protocol": 1,
  "type": "publish",
  "deliveryId": "<payload SHA-256 hex>",
  "digest": "<same payload SHA-256 hex>",
  "payload": "<base64 UTF-8 JSON bytes>"
}
```

The decoded payload is `{"manifest":{...},"files":{"web/index.html":"<base64 bytes>","backend/main.cjs":"<base64 bytes>","web/app.js":"<base64 bytes>"}}`. Hash the actual UTF-8 payload bytes, then base64-encode those same bytes. The manifest follows [manifest.schema.json](../schemas/manifest.schema.json). The desktop bundler uses deterministic ordering/content; the host verifies the digest rather than trusting file names or a sender-provided revision label.

Limits: decoded payload 8 MiB; encoded payload 12 MiB; 2–200 files; each web file 2 MiB; backend 128 KiB. Both entry files must exist. Files may only be under `web/` or exactly `backend/main.cjs`. Paths must use simple relative segments; traversal, absolute paths, empty segments, and reserved `web/__substrate*` names are rejected. The host creates the manifest file itself.

The authenticated certificate fingerprint owns each delivered app ID. A different publisher cannot replace that ID. File imports and included samples use distinct local source identities. Equal active/pending digests are idempotent. Other valid revisions become pending; they do not inherently have ordering or permission authority. There is no remote activate command or monotonic delivery counter.

Staging is separate from approval. New apps or expanded capabilities wait in native review. Opt-in live editing may activate a revision within existing grants only for the currently running foreground app/session. The native approval operation checks the reviewed revision digest atomically, so a later delivery cannot substitute different code under a stale review.

## Phone messages

Phone status messages have `protocol: 1`, `type: "status"`, `appId`, `revision`, and `state`. Implemented states are `review-required`, `running`, and `failed`. `running` follows the Node and frontend readiness handshake. Delivery/validation failures currently appear in the host connection status; the protocol does not provide a durable delivery receipt or a complete remote error catalog.

With explicit user-enabled sharing, `type: "diagnostic"` includes `appId` and a bounded `message`. Sharing is off by default because app logs can include app data. Diagnostics are informational and cannot issue host commands.

Invalid messages, interrupted transfers, and ownership errors must never change the active revision. Startup failure restores the previous pointer and retains the candidate for review. After reconnect, resending the latest bundle is sufficient; partial-transfer resumption is not implemented.
