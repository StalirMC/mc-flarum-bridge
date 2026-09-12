# MC Bridge wire protocol

This document is the contract shared by the Flarum extension and the Minecraft
plugin. Both implementations must agree byte-for-byte on the values described
here.

## 1. Transport

- Plain HTTP/1.1 + JSON over TLS (`https://` strongly recommended).
- Request bodies are UTF-8 encoded JSON objects.
- Responses are UTF-8 encoded JSON objects.
- `Content-Type: application/json`, `Accept: application/json`.

## 2. Authentication (machine endpoints)

Every request from the plugin to a machine endpoint carries four headers:

| Header | Value |
|--------|-------|
| `X-MC-Timestamp` | Unix time in **seconds** (decimal digits only) |
| `X-MC-Nonce` | Random, single-use string, 8–128 characters |
| `X-MC-Signature` | Lowercase hex HMAC-SHA256 of the canonical string |
| `X-MC-Server` | Optional server key; the JSON body field takes precedence |

### Canonical string

```
{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}
```

- `{METHOD}` is uppercase (`GET`, `POST`, `DELETE`).
- `{path}` is the request path **starting at the bridge prefix**, with the
  query string removed. For `https://forum.example.com/api/mc-bridge/outbox?peek=1`
  the signed path is `/api/mc-bridge/outbox`.
  If Flarum is served from a sub-directory (`https://example.com/forum/...`),
  the signed path is still `/api/mc-bridge/...` — the sub-directory is not part
  of the signature.
- `{body}` is the raw request body exactly as transmitted. For requests without
  a body (`GET`) it is the empty string.

### Signature

```
signature = hex(HMAC-SHA256(key = secret, message = canonicalString))
```

Comparison is constant-time on the forum side.

### Replay protection

1. The timestamp must be within **±300 seconds** of the forum clock.
2. A nonce may only be used once. Used nonces are cached for 600 seconds; a
   repeat is rejected with `401`.

### Failure responses

| Status | Meaning |
|--------|---------|
| `401` | Missing/malformed headers, bad signature, stale timestamp, reused nonce |
| `403` | Authenticated but not permitted (e.g. non-admin broadcast) |
| `404` | Unknown binding code |
| `409` | Conflict (code already used, account already linked) |
| `410` | Binding code expired |
| `422` | Body failed validation |
| `503` | The forum has no bridge secret configured |

All errors share the shape:

```json
{ "error": "human readable reason" }
```

## 3. Endpoint summary

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| `POST` | `/api/mc-bridge/heartbeat` | HMAC | Server status upsert |
| `POST` | `/api/mc-bridge/events` | HMAC | Batch gameplay events |
| `GET` | `/api/mc-bridge/outbox` | HMAC | Pull + consume pending messages |
| `GET` | `/api/mc-bridge/announcements` | HMAC | Alias of `/outbox` |
| `POST` | `/api/mc-bridge/bind/start` | HMAC | Issue a binding code |
| `GET` | `/api/mc-bridge/bind/status` | HMAC | Is this UUID linked yet? |
| `POST` | `/api/mc-bridge/broadcast` | HMAC **or** admin session | Queue a broadcast |
| `GET` | `/api/mc-bridge/status` | public | Read-only server snapshot |
| `POST` | `/api/mc-bridge/link` | forum session | Consume a binding code |
| `DELETE` | `/api/mc-bridge/link` | forum session | Remove the link |

## 4. Schemas

- [`heartbeat.schema.json`](heartbeat.schema.json)
- [`events.schema.json`](events.schema.json)
- [`outbox.schema.json`](outbox.schema.json)

## 5. Reference signing snippets

### Bash

```bash
FORUM="https://forum.example.com"
SECRET="<the shared secret>"
BODY='{"server_key":"survival","online":true,"players_online":0,"players_max":20}'
TS=$(date +%s)
NONCE=$(openssl rand -hex 16)
PATH_="/api/mc-bridge/heartbeat"

SIG=$(printf '%s' "$TS
$NONCE
POST
$PATH_
$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $NF}')

curl -sS -X POST "$FORUM$PATH_" \
  -H 'Content-Type: application/json' \
  -H "X-MC-Timestamp: $TS" \
  -H "X-MC-Nonce: $NONCE" \
  -H "X-MC-Signature: $SIG" \
  -d "$BODY"
```

### PHP

```php
use Stalir\McBridge\Service\BridgeCrypto;

$timestamp = (string) time();
$nonce = bin2hex(random_bytes(16));
$body = json_encode(['server_key' => 'survival', 'online' => true]);
$path = '/api/mc-bridge/heartbeat';

$signature = BridgeCrypto::sign($secret, $timestamp, $nonce, 'POST', $path, $body);
```

## 6. Versioning

The protocol is currently at revision **1**. Additive fields may be introduced
without a revision bump; both sides must ignore unknown JSON fields.
