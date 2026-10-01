# MC Bridge API Reference

**English** · [简体中文](API.zh-CN.md)

Base URL: `https://<forum>`; all endpoints live under the `/api/mc-bridge` prefix.

Authentication is described in [`../protocol/README.md`](../protocol/README.md). Endpoints marked **HMAC** require
the three request headers `X-MC-Timestamp` / `X-MC-Nonce` / `X-MC-Signature`; endpoints marked **session**
require a Flarum login cookie.

The `error` text in all responses is rendered from the language pack (Simplified Chinese by default) and can be
switched to English with `php flarum mc-bridge:config --locale=en`. The error **structure** does not change;
only the wording changes with the language.

## CSRF behaviour (important)

Flarum enforces CSRF validation for the whole `api` middleware stack, while the server has neither a session nor a CSRF token.
The extension exempts the machine endpoints **by route name** through the official **`Extend\Csrf`** extender:

```php
(new Extend\Csrf())
    ->exemptRoute('mc-bridge.outbox')
    ->exemptRoute('mc-bridge.announcements')
    // ...
```

The conventions that follow from this:

| Endpoint | CSRF token required |
|------|--------------------|
| Machine endpoints (`outbox` / `announcements` / `bind/start` / `bind/status` / `broadcast`) | ❌ Not required, authenticated by HMAC |
| `POST /mc-bridge/broadcast` (with signature headers) | ❌ Not required |
| `POST /mc-bridge/broadcast` (admin session) | ✅ **Required**: `X-CSRF-Token`, validated separately inside the controller |
| `POST` / `DELETE /mc-bridge/link` (session) | ✅ Required, **not exempted**, validated by the framework |

When calling a session endpoint with curl, take the value from the `XSRF-TOKEN` cookie and put it into the `X-CSRF-Token` header:

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/link \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"code":"K7MPQ2XY"}'
```

> **Security boundary**: `link` / `unlink` act on the signed-in user's account and are **deliberately kept off the exemption list**.
> `broadcast` is exempted for machine calls, so the controller validates
> `X-CSRF-Token` separately for the **session path**, and cross-site forms still cannot get through.

## Admin panel

There is currently **no** admin UI. The `mc-bridge.*` settings are managed through console commands:

```bash
php flarum mc-bridge:secret                # generate/view the secret
php flarum mc-bridge:config --tags=1,3     # announcement tag filter
php flarum mc-bridge:selftest --url=...    # full-chain self-test
```

The strings in `locale/en.yml` are already reserved for a future admin settings page.

---

## GET /api/mc-bridge/outbox

**Authentication: HMAC** — pull messages awaiting delivery (announcements / broadcasts / commands).

Query parameters:

| Parameter | Default | Description |
|------|------|------|
| `server_key` | — | Required (the `X-MC-Server` header also works) |
| `limit` | 20 | 1–100 |
| `peek` | false | `true` reads without consuming |

Response `200`:

```json
{
  "ok": true,
  "server_key": "survival",
  "peek": false,
  "messages": [
    {
      "id": 12,
      "type": "announcement",
      "title": "服务器更名公告",
      "body": "随着模组服二周目的开启，我们决定更名…",
      "url": "/d/50",
      "payload": { "discussion_id": 50, "post_id": 210, "author": "kxkl2024", "is_op": true },
      "created_at": "2026-01-01T11:59:00+00:00"
    }
  ]
}
```

Unless `peek=true`, the returned messages are marked as delivered and are not returned again next time.

> `/api/mc-bridge/announcements` is an alias of the same endpoint.

---

## POST /api/mc-bridge/bind/start

**Authentication: HMAC** — issue a single-use binding code for a player (called by the in-game `/bind` command).

Request:

```json
{
  "server_key": "survival",
  "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "player_name": "Alice"
}
```

Response `201`:

```json
{
  "ok": true,
  "already_bound": false,
  "code": "K7MPQ2XY",
  "expires_at": "2026-01-01T12:10:00+00:00",
  "expires_in_seconds": 600,
  "link_url": "/settings"
}
```

When already bound, it returns `200`:

```json
{
  "ok": true,
  "already_bound": true,
  "binding": {
    "user_id": 5,
    "username": "Alice",
    "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "player_name": "Alice",
    "server_key": "survival",
    "linked_at": "2025-12-01T09:00:00+00:00"
  }
}
```

Calling this repeatedly for the same player invalidates the previous unused code. The binding code character set is
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (the easily confused `0 O 1 I` are removed), length 8.

---

## GET /api/mc-bridge/bind/status

**Authentication: HMAC** — query the binding status of a UUID.

| Parameter | Description |
|------|------|
| `server_key` | Required |
| `uuid` | Player UUID (with or without hyphens) |

Response `200`:

```json
{
  "ok": true,
  "bound": true,
  "binding": { "user_id": 5, "username": "Alice", "player_uuid": "…", "player_name": "Alice", "server_key": "survival", "linked_at": "…" }
}
```

When not bound:

```json
{ "ok": true, "bound": false, "pending_code": "K7MPQ2XY", "expires_at": "2026-01-01T12:10:00+00:00" }
```

---

## POST /api/mc-bridge/broadcast

**Authentication: HMAC or admin session** — deliver one message to one server or to all servers.

Request:

```json
{
  "server_key": "survival",
  "type": "broadcast",
  "title": "维护通知",
  "body": "今晚 23:00 重启",
  "url": "/d/66",
  "payload": {}
}
```

- When `server_key` is omitted (or `null`), the message is delivered to **all** servers.
- `type` can be `broadcast` / `announcement`.
- Provide at least one of `title` and `body`.
- The plugin only **displays** these messages; it never executes any command.

Response `201`:

```json
{ "ok": true, "message": { "id": 13, "type": "broadcast", "title": "维护通知", "body": "今晚 23:00 重启", "url": "/d/66", "payload": {}, "created_at": "…" } }
```

Not an administrator and no signature → `403`.

---

## POST /api/mc-bridge/report

**Authentication: HMAC** — submit a player report from the game server and **turn it into a discussion with a report tag in the forum**,
so administrators can handle it directly in the forum UI.

Request:

```json
{
  "server_key": "survival",
  "reporter_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "reporter_name": "Alex",
  "target_name": "Steve",
  "reason": "他在出生点恶意破坏"
}
```

- `reporter_uuid` must be a valid UUID.
- `target_name` and `reason` are required.
- `reason` is at most 1000 characters.

**Optional fields** (the `report:` section of the game-side `config.yml` is sent with the request; all of them may be omitted):

| Field | Type | Description |
|------|------|------|
| `report_uid` | string | Optional. The idempotency identifier of this report (UUID). Submitting the same uid repeatedly stores it only once, see below |
| `title` | string | The discussion title. Rendered by the plugin **in game**, so it may contain PlaceholderAPI output. When omitted, the forum renders it from its own template |
| `tags` | string[] | Which tags the discussion is filed under; each entry is a slug or a tag ID (at most 10 entries). When omitted, the forum setting is used |
| `actor` | string | Which forum account to post as, given as a username or user ID. When omitted, the forum setting is used |
| `context` | string | The **reported player's own** most recent public chat transcript (the game side controls the number of lines with `report.chat-context-lines`, 0 disables it). At most 12000 characters |
| `context_enabled` | boolean | Whether the game-side chat transcript switch is on. Used to distinguish "the switch is off" from "the switch is on but this player never said a single word" — when this field is missing (older plugin versions) the discussion body says nothing about it, i.e. the old behaviour |

The last three are all **hints**: entries that cannot be resolved are written to the Flarum log and skipped, and they **do not** make this report fail —
the report itself is already stored, and a player should not see a 500 because of a typo in the forum-side configuration.

**`report_uid` and retries**: a client timeout **does not mean** the server side has not finished processing — in reality it is more common that the server finished and the reply was lost on the way. The plugin therefore retries once with the same uid only when it received **no response at all**;
the forum claims that uid in its cache for 600 seconds, and a retry returns the first result directly:

```json
{ "ok": true, "report_id": 42, "discussion_id": 128, "duplicate": true }
```

`duplicate` is only extra information for the caller; `ok` being `true` means the report has been stored.
That way one timeout does not turn into two records or two discussions.

Conversely, as soon as **any** HTTP status code is received (even a 500), the server has answered and the result is settled,
so the plugin does not retry — a retry would only repeat the same error.

Response `201`:

```json
{ "ok": true, "report_id": 42, "discussion_id": 128 }
```

A report does two things:

1. **Stores it** in the `mc_reports` table with the initial status `pending` (record keeping and auditing).
2. **Posts a discussion** under the forum's report tag (several may be attached), with a title such as `[举报] Steve（由 Alex 提交）` ("[Report] Steve (submitted by Alex)"),
   and a body listing the reported player, the reporter, the server, the time, the record number and the report reason.

The discussion is created through Flarum's own JSON:API pipeline (the same path a user takes when posting in the forum),
so the first post, the tag associations, the reply count and the author's read state are all handled by the framework; the extension does not hand-write those rows.

| Behaviour | Description |
|------|------|
| Posting account | The `actor` in the request takes priority; otherwise the setting; otherwise the **earliest administrator** (the `--report-actor=<user ID>` option, user ID) |
| Report tags | The `tags` in the request take priority; otherwise the setting; otherwise a lookup by slug `reports` / name `举报` ("Reports"); if neither exists and `flarum/tags` is installed, a secondary tag is created automatically (`--report-tags=4,14`) |
| Title | The `title` in the request takes priority; otherwise it is rendered from the template in the settings (`--report-title="..."`) |
| Self-configuration | The account and tags that actually take effect are **written back to the settings**, so `--show` shows the real values |
| Failure handling | Discussion creation is **best effort**: the report is already stored, and a forum-side error does not turn the player's `/report` into a 500. Failures are written to the Flarum log, and `discussion_id` in the response is `null` |
| No broadcast | Report discussions are never pushed to the game (see below) |

> **Never goes into the game**: `QueueAnnouncement` skips report discussions — as long as they carry **any** report tag,
> or their author is the report posting account. Otherwise the report content (including the reporter's identity) would be broadcast to all online players.
>
> Note that `flarum/tags` limits how many primary/secondary tags one discussion can carry; when that limit is exceeded, that creation fails,
> and the reason for the failure is written to the Flarum log.

`discussion_id` is `null` when creation on the forum side fails; the caller may ignore it.

**Report outcome**: once the report's discussion has been created, its ID is stored in `mc_reports.discussion_id`. When an administrator
marks the report as resolved/rejected in either of the following ways, the forum inserts a targeted message into `mc_outbox`
(`type` is `report_resolved` or `report_rejected`, `target_uuid` is the reporter), and the game side notifies the reporter in game
on its next poll:

| Method | Description |
|------|------|
| Tagging the report discussion with the "已处理"/"已驳回" ("resolved"/"rejected") tags | Listens for `Flarum\Tags\Event\DiscussionWasTagged`. The tag IDs are specified by `--report-resolved-tags` / `--report-rejected-tags`, disabled by default |
| `php flarum mc-bridge:report <report id> --status=resolved` (record number) | Command fallback, usable for batch processing or automation; `--note="..."` attaches a note, while `--silent` does not notify |

Both paths go through the same `Service\ReportOutcome`, so "tagging twice" or "running the command twice" **notifies only once**: no message is sent when the status has not changed. The message itself carries only facts (record number, reported player, status, note),
and the actual wording is rendered by the game server from its own language file.

---

## GET /api/mc-bridge/reports

**Authentication: HMAC** — returns the reports **that a given player submitted themselves** together with their processing status, for the in-game `/report status` command.

Query parameters:

| Parameter | Required | Description |
|------|------|------|
| `server_key` | Yes | The `X-MC-Server` header also works |
| `reporter_uuid` | Yes | The reporter's UUID; only reports submitted by this UUID are returned |
| `limit` | No | Number of entries to return, default 5, maximum 20 |

```bash
curl -H "X-MC-Timestamp: ..." -H "X-MC-Nonce: ..." -H "X-MC-Signature: ..." \
  "https://forum.example/api/mc-bridge/reports?server_key=survival&reporter_uuid=069a79f4-...&limit=5"
```

Response `200` (in **reverse** order of submission time):

```json
{
  "ok": true,
  "server_key": "survival",
  "reports": [
    {
      "id": 42,
      "target_name": "Steve",
      "reason": "他在出生点恶意破坏",
      "status": "resolved",
      "created_at": "2026-09-25T13:01:42+00:00"
    }
  ]
}
```

- `status` values: `pending` (pending), `resolved` (resolved), `rejected` (rejected).
- The query filters by `reporter_uuid` and `server_key` at the same time: a player can only see the reports they submitted,
  and does not see a record with the same name from another server.
- When there are no reports, `reports` is an empty array, which is not an error.

---

## GET /api/mc-bridge/link

**Authentication: forum session (login required)** — returns the Minecraft binding of the currently signed-in account, for the forum frontend to display.

Response `200`:

```json
{ "ok": true, "bound": true, "binding": { "user_id": 5, "username": "Alice", "player_uuid": "…", "player_name": "Alice", "server_key": "survival", "linked_at": "…" } }
```

When not bound, `bound` is `false` and `binding` is `null`.

> This GET was once left unregistered: `LinkStatusController` was written but never wired to a route, the frontend got a 404 when reading the status,
> and so "the binding succeeded but the forum still shows unbound". `tools/verify.mjs` now compares every
> `/mc-bridge/*` the frontend calls against the method + path registered in `extend.php`.

---

## POST /api/mc-bridge/link

**Authentication: forum session (login required)** — consume a binding code and link the game account to the current forum account.

Request:

```json
{ "code": "K7MPQ2XY" }
```

Response `201`:

```json
{ "ok": true, "binding": { "user_id": 5, "username": "Alice", "player_uuid": "…", "player_name": "Alice", "server_key": "survival", "linked_at": "…" } }
```

Errors:

| Status | Scenario |
|------|------|
| `404` | The binding code does not exist or has already been used |
| `409` | The code has already been used / the forum account is already linked to another game account / the game account is already linked to another forum account |
| `410` | The binding code has expired |
| `422` | The format is not 8 uppercase alphanumeric characters |

---

## DELETE /api/mc-bridge/link

**Authentication: forum session (login required)** — unlink the current forum account.

Response `200`: `{ "ok": true }`; `404` when not bound.

---

## Additional user resource fields (user resource)

The forum frontend has to show the MC account on the **profile page** and **next to the author name of every post**, so the binding information is attached to Flarum's
user resource (`Stalir\McBridge\Api\UserResourceFields`) instead of making the frontend send one request per author.

| Field | Type | Description |
|------|------|------|
| `mcBridgePlayerName` | string \| null | The bound MC player name |
| `mcBridgeServerKey` | string \| null | The `server.key` of the server where the binding was made |
| `mcBridgeLinkedAt` | datetime \| null | Binding time |

**Visibility**: the `visible` callback of all three fields requires `actor->isRegistered()`, i.e. **only signed-in users** can
see them in the payload; a guest's response does not contain these fields at all (this is not the frontend hiding them).

**Cost**: the fields are queried user by user (about twenty indexed queries per page). The reason for not loading all bindings in one go is to
avoid scanning a table that grows with the number of bindings; the reason for not caching across requests is to avoid still showing an old value after an unlink.

---

## Forum pages (server-side rendered, no frontend build required)

| Path | Authentication | Purpose |
|------|------|------|
| `GET /mc-bridge/link` | Login required | Enter a binding code / unlink (`POST` to the same path, with a CSRF token) |

Both are rendered as HTML directly by PHP (`LinkPageController`, `StatusPageController`), so they do not depend on an npm build;
the “服务器状态” ("Server Status") entry in the forum sidebar is added by the frontend bundle as an ordinary `<a>` link.

---

## Database tables

| Table | Purpose |
|----|------|
| `mc_servers` | A table left over from early status reporting; the reporting feature has been removed, so the table is kept but no longer written to |
| `mc_events` | Early game event stream; the reporting feature has been removed, so the table is kept but no longer written to |
| `mc_outbox` | The queue of messages awaiting delivery to the game; `delivered_at` being NULL means not delivered |
| `mc_bindings` | Confirmed `user_id ↔ player_uuid` bindings (unique on both sides) |
| `mc_bind_codes` | Single-use binding codes, including expiry and use times |

## Console commands

| Command | Description |
|------|------|
| `php flarum mc-bridge:secret` | Generate and save a new shared secret |
| `php flarum mc-bridge:secret --show` | Print the current secret |
| `php flarum mc-bridge:secret <value>` (the value) | Write the specified secret (at least 32 characters) |
| `php flarum mc-bridge:config --show` | View the announcement tag filter, reply sync, retention days, output language, report tags, report posting account and report title template |
| `php flarum mc-bridge:config --locale=en` | Switch the output language (default `zh-Hans`, `en` optional) |
| `php flarum mc-bridge:config --tags=1,3` | Push only new discussions with tags 1 and 3 to the game (empty value = all) |
| `php flarum mc-bridge:config --sync-replies=1` | Push replies as well |
| `php flarum mc-bridge:config --report-tags=4,14` | Specify which tags report discussions are filed under, comma-separated (empty value = automatically detect slug `reports` / name `举报` ("Reports")) |
| `php flarum mc-bridge:config --report-actor=3` | Specify which account posts report discussions (empty value = the earliest administrator) |
| `php flarum mc-bridge:config --report-title="[举报] {target}"` ("[Report] {target}") | Template used when the game side sends no title (empty value = built-in default) |
| `php flarum mc-bridge:selftest` | Offline self-test: secret, HMAC, table structure, queries, binding code format |
| `php flarum mc-bridge:selftest --url=https://your.forum` | Additionally makes one real signed HTTP loopback request |
