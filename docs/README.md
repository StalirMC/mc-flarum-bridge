# Deployment Guide

**English** · [简体中文](README.zh-CN.md)

> ⚠️ **This project is still under construction; its usability is not verified. Do not use it in a production environment directly.**
> See the note at the beginning of the root [README](../README.zh-CN.md).

This document describes the complete steps to get MC ↔ Flarum interworking running from scratch.

## 0. Prerequisites

| Component | Requirement |
|------|------|
| Flarum | 2.x (PHP 8.1+), already running normally |
| Minecraft server | Paper 1.21.x · Folia 1.21.x (the same plugin jar); NeoForge 21.1.x for 1.21.1 (mod jar) |
| Java | Shared core bytecode target 17; both Paper/Folia and NeoForge require Java 21 at runtime |
| Network | The MC server can reach the forum's `https://<forum>/api/mc-bridge/*` |

> If the forum is behind a CDN such as Cloudflare, make sure that
> "Under Attack" mode or WAF rules are not blocking `/api/mc-bridge/*`;
> otherwise the plugin will receive 403/1010.

## 1. Flarum side: install the extension

### ⚠️ First, be clear about this: Flarum 2.x has no `extensions/` directory

**Flarum 2.x only discovers extensions from Composer's `vendor/composer/installed.json`**; the source evidence is:

```php
// framework/core/src/Extension/ExtensionManager.php
$manifest = $this->paths->vendor.'/composer/installed.json';
$installed = json_decode($this->filesystem->get($manifest), true);
...
if (Arr::get($package, 'type') === 'flarum-extension' && str_contains($name, '/')) {
    $composerJsonConfs[$packagePath] = $package;
}
```

In other words, **there is no "drop a folder into some directory and it is installed" mechanism** — an extension
must be installed through Composer so that it appears in `installed.json`. (That `extensions/` directory in the
Flarum repository is merely the official monorepo's own source layout, not a runtime convention.)

This repository is a monorepo (the extension is in `flarum-extension/`, the plugin in `mc-plugin/`). The root
`composer.json` uses Flarum 2.x's **`extra.flarum-subextensions`** mechanism:

```json
{
  "autoload": { "psr-4": { "Stalir\\McBridge\\": "flarum-extension/src/" } },
  "extra": { "flarum-subextensions": ["flarum-extension"] }
}
```

`ExtensionManager::subExtensionConfsFromJson()` reads this field and recognises the `composer.json` in the
subdirectory as an extension. Therefore **the whole repository can be installed as a single Composer package**,
and the extension ID is the `stalirmc-mc-bridge` declared in the subdirectory.

> Note that autoload must be written in the root `composer.json`: Composer does not process a sub-package's own
> `autoload`, and Flarum will not register a namespace on the extension's behalf.

---

### Option A: install from the admin panel (recommended, no SSH needed)

The extension **is already published on Packagist**: <https://packagist.org/packages/stalirmc/mc-flarum-bridge>

1. Admin panel → **Extension Manager** → **Install a new extension** → enter:
   ```
   stalirmc/mc-flarum-bridge
   ```
   Packagist is Composer's default source, so you do **not** need to add a `vcs` repository manually.
2. Once installed, go to the extension list and **enable** "MC Bridge"
3. Generate the shared secret (see 1.3); if the admin panel has no terminal, use Option B or run it on the server

If you want to follow the latest development version rather than the stable release, enter `stalirmc/mc-flarum-bridge:dev-main`.

> Only when Packagist has not yet synced a given commit, or when you need to install a not-yet-released version,
> do you need to fall back to the `vcs` approach:
> Admin panel → Repositories → Add repository (type `vcs`, URL `https://github.com/StalirMC/mc-flarum-bridge`).

### ⚠️ Migrating from the old package name `stalir/*`

The package name has been changed to the organisation name (Composer requires all lowercase, hence `stalirmc/*`),
and **the extension ID has likewise changed from `stalir-mc-bridge` to `stalirmc-mc-bridge`**. For a forum where
it is already installed, replacing it once is enough:

```bash
cd /path/to/flarum
composer remove stalir/mc-flarum-bridge      # for an Option C install it is stalir/mc-bridge
composer require stalirmc/mc-flarum-bridge
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge
php flarum cache:clear
php flarum assets:publish
```

**Both data and configuration are preserved**, because the table names and setting keys are unchanged:

| Item | Preserved | Reason |
|------|----------|------|
| 5 database tables (server/event/announcement/link/binding code) | ✅ Preserved | The migration has a `hasTable` guard, so re-running it neither recreates them nor errors |
| Shared secret (`mc-bridge.secret`), language, announcement tag, sync switch | ✅ Preserved | The setting keys are unrelated to the package name |
| The extension's enabled state | ⚠️ Must be re-enabled | Flarum records the enabled list by extension ID; once the ID changes it is a new extension |

The migration is **mandatory**: the old package name was never published on Packagist, and the repository's
`composer.json` has now been renamed, so `stalir/mc-flarum-bridge` can no longer be installed. If the forum
reports 「扩展不存在」 ("extension does not exist") after `composer remove`, run
`composer update stalirmc/mc-flarum-bridge` once to let Composer resolve it again.

### Option B: SSH / Composer

```bash
cd /path/to/flarum

composer require stalirmc/mc-flarum-bridge

php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # use php flarum extension:list to check the exact ID
php flarum cache:clear
php flarum assets:publish
```

Adding `:dev-main` installs the development version. **You must run `assets:publish` after updating the extension**
(the frontend bundle changed; if you do not run it, the browser still gets the old script — see 1.2.1).

### Option C: install directly from local files, without going through GitHub

Upload the **entire `flarum-extension/` directory** (not the repository root) to the server, for example to
`<flarum>/packages/mc-bridge/`, then add a **path repository**:

```json
{
  "repositories": [
    { "type": "path", "url": "packages/mc-bridge", "options": { "symlink": false } }
  ]
}
```

```bash
composer require stalirmc/mc-bridge:dev-main
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge
php flarum cache:clear
```

> Option C points at `flarum-extension/` itself (its own `composer.json` is already
> `type: flarum-extension`), so the package name is **`stalirmc/mc-bridge`**, different from
> `stalirmc/mc-flarum-bridge` in Options A/B. This route does **not** require `flarum-subextensions`.

### 1.2.1 Update to the latest code (frontend assets must be republished)

The repository is still under construction, so after pulling new code each time you **must republish the frontend
assets**, otherwise the browser still gets the old `dist/forum.js` (the symptom being: the code changed but the
behaviour did not):

```bash
cd <flarum>
composer update stalirmc/mc-flarum-bridge      # for an Option C install it is stalirmc/mc-bridge
php flarum cache:clear
php flarum assets:publish                    # the extension's frontend JS is copied into public/assets
```

> Clicking "Clear cache" in the admin panel also runs `assets:publish` — `ClearCacheController`
> calls `AssetsPublishCommand` internally, so the two approaches are equivalent.
> Also, the browser may still cache the old script; a `Ctrl+F5` hard refresh is recommended.

### 1.3 Generate the shared secret

```bash
php flarum mc-bridge:secret
```

The command prints a 64-digit hexadecimal secret. **This is the secret the plugin must be given**; keep it safe.

At any time you can view the current secret with the following command:

```bash
php flarum mc-bridge:secret --show
```

### 1.4 Optional: restrict which discussions are synced to the game

By default all new discussions are pushed to the game. If you only want to sync the "announcement" type of tag,
first look up the tag ID:

```bash
php flarum tinker --execute="echo \Flarum\Tags\Tag::pluck('id','name');"
```

Then set it with the dedicated command (no need to enter tinker):

```bash
php flarum mc-bridge:config --tags=1,3     # sync only tags 1 and 3
php flarum mc-bridge:config --tags=        # clear the filter, resume syncing everything
php flarum mc-bridge:config --sync-replies=1   # push replies as well
php flarum mc-bridge:config --show         # view the current settings
```

When `sync-replies` is `1`, **every reply** to a matching discussion is also pushed to the game; the default is
`0`, pushing only when a new post is created, to avoid flooding.

### 1.5 Language (optional)

Both sides support multiple languages, and **both default to Simplified Chinese**; they do not affect each other:

```bash
# forum side: console command output + API error messages
php flarum mc-bridge:config --locale=en        # switch to English
php flarum mc-bridge:config --locale=zh-Hans   # switch back to Chinese (the default)
php flarum mc-bridge:config --show             # view the current language
```

On the game side, change `plugins/McBridge/config.yml`:

```yaml
language: zh_CN     # or en
```

Then `/mcbridge reload`.

**Adding a new language**:

| Location | How to |
|------|------|
| Forum side | Copy `locale/zh-Hans.yml` to `locale/<new language>.yml` and translate it; `Extend\Locales` in `extend.php` automatically registers the whole directory |
| Game side | Copy `lang/zh_CN.yml` to `lang/<new language>.yml`, translate it, and point `language` at it |

Both sides **fall back to Chinese** when a key is missing, and never display the raw key name to players. A
validation script checks whether the key sets of each language are identical.

> ⚠️ After changing the language, the forum side needs one cache clear: `php flarum cache:clear`

## 2. Minecraft side: build and install

> **Two artifacts.** The Paper plugin jar has `plugin.yml` at its root (read by Paper/Folia); the NeoForge mod
> jar has `META-INF/neoforge.mods.toml` (read by NeoForge). Both are compiled from the same shared core source,
> each independent and not depending on the other. During the build, each one's `verifyJar` checks that the
> descriptor, the entry class and the bundled resources are all present, and that the shared layer contains no
> platform class references. **Velocity is no longer supported.**

### 2.1 Build

The repository does not include the Gradle wrapper binary; use a system Gradle 8.10+:

```bash
cd mc-plugin
gradle build              # build both projects
gradle :build             # only the Paper/Folia plugin (skips the time-consuming NeoForge toolchain)
```

**JDK 21** is required (toolchain); the shared core's bytecode target is Java 17, while the Paper and NeoForge
modules are 21.

On the first build of the NeoForge mod, ModDevGradle downloads Minecraft 1.21.1 and NeoForge 21.1.100 and runs
NeoForm once (decompile → patch → recompile 5364 source files), which takes **about 5-10 minutes** and needs
network access; afterwards the Gradle cache is reused, and an incremental build takes only a dozen seconds or so.

Artifacts:

```
build/libs/McBridge-<version>.jar
neoforge/build/libs/McBridge-neoforge-<version>.jar
```

If your server is not 1.21.1, you can override the Paper API version:

```bash
gradle build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

### 2.2 Install

| Platform | Location | Configuration directory |
|------|----------|----------|
| Paper / Folia | `<server>/plugins/` | `plugins/McBridge/` |
| NeoForge | `<server>/mods/` | `config/mc-bridge/` |

```bash
# Paper / Folia
cp build/libs/McBridge-<version>.jar <server>/plugins/

# NeoForge (a different file)
cp neoforge/build/libs/McBridge-neoforge-<version>.jar <server>/mods/
```

Folia needs no extra steps: `plugin.yml` already declares `folia-supported: true`, and the plugin automatically
detects a regionised server and switches to Folia's `AsyncScheduler` / `GlobalRegionScheduler`.

The NeoForge mod declares `side = "SERVER"`, so it **only needs to be installed on the server**; the client does
not need it. Its commands use permission levels instead of Bukkit permission nodes: `/mcbridge news` is
available to everyone, and the remaining subcommands require OP (level 2).

Start the server once to generate the configuration, or copy the repository's `src/main/resources/config.yml`
straight to `plugins/McBridge/config.yml` (for NeoForge, `config/mc-bridge/config.yml`).

> **Installing on several backends at once?** That works, but give them **different `server.key` values**
> (for example `survival` and `creative`), otherwise they will overwrite each other's single server record on
> the forum.

### 2.3 Fill in the configuration

```yaml
forum:
  url: "https://forum.kxkl2024.cn"     # do not end with /
  api-prefix: "/api/mc-bridge"

server:
  key: "survival"                       # different for each server when there are several
  name: "Stalir 生存服"

security:
  secret: "<the secret generated in step 1.3>"

game:
  # announcement display channel. Multiple allowed, comma-separated: chat / actionbar / title / bossbar
  # e.g. "chat,bossbar" sends both a chat line and a top boss bar.
  announce-display: "chat"
  title-seconds: 5                      # seconds the title stays
  bossbar-seconds: 10                   # seconds the boss bar stays
  prompt-unbound: true                  # prompt an unbound player once on join about how to link
```

> If Flarum is installed in a subdirectory (such as `https://example.com/forum`), just write the full `url`;
> the signature algorithm automatically ignores subdirectory differences.

### 2.4 Apply

```
/mcbridge reload
```

Seeing `McBridge enabled on paper as server 'survival' -> ...` (on NeoForge it is
`on neoforge`) means the configuration has been read and the plugin has registered itself with the forum. The
first line of `/mcbridge stats` shows the current running platform.

## 3. Verifying interoperability

**Run the forum-side self-test first** — it covers the secret, signatures, table structure, queries and routes in one pass:

```bash
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn
```

If everything shows `OK`, the forum side is fully ready (the command sends one real signed loopback request and deletes the temporary server record created by the probe afterwards).

### 3.1 Announcement push

Create a new discussion on the forum (or post under the restricted tag); within a few seconds the game should show:

```
[论坛] <discussion title>
<body excerpt>
/d/123
```

("[Forum] <discussion title>" / "<body excerpt>" / the discussion link)

### 3.1.1 Checking announcements in game

Players can type `/mcbridge news` (or `/mcb news`) in game to view the latest 5 announcements from the forum. This command **requires no administrator permission** and is available to all players.

```
-------- 论坛公告 -------- (最近 3 条)
服务器维护通知
今晚 23:00 进行例行维护，预计持续 30 分钟。
周末活动预告
本周六下午 2 点举行建筑大赛，欢迎参加！
```

("-------- Forum announcements -------- (latest 3)" / "Server maintenance notice" / "Tonight at 23:00 there will be routine maintenance, expected to last 30 minutes." / "Weekend event preview" / "A building contest is being held this Saturday at 2 pm — everyone is welcome!")

("-------- Forum announcements -------- (latest 3)" / "Server maintenance notice" / "Routine maintenance tonight at 23:00, expected to last 30 minutes." / "Weekend event preview" / "A building contest will be held this Saturday at 2 p.m. — welcome to join!")

### 3.2 Account linking

**Player side (normal flow)**

1. Run `/bind` in game; an 8-character binding code appears in the chat bar (valid for 10 minutes).
2. Open the forum → **click your avatar in the top-right corner → Settings** → scroll down to the 「Minecraft 账号」 ("Minecraft account") section and submit the binding code there. Once linked, that section shows the linked game nickname, and an MC badge also appears next to the profile page and the post author name.
3. Running `/bind` again reports that you are already linked and displays the forum username.
4. To unlink: click 「解除绑定」 ("Unlink") in the same section.

> Players who join for the first time without a linked account are prompted once about this flow (`game.prompt-unbound`, enabled by default).

> **Unique mapping**: one forum account can be linked to exactly **one** Minecraft account (`user_id` unique).
> In a multi-server setup (survival server + creative server) that link is **shared across servers** — once a player
> links on any one server, every server recognises it as the same forum account. If a player wants to link a different
> Minecraft account on another server, they must unlink the current one first.

**API side (automation scripts or self-built forms)**

This is a **session** endpoint; besides cookies it also needs `X-CSRF-Token` (see
[the CSRF section of `API.md`](API.md#csrf-behaviour-important)):

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/link \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"code":"ABCD2345"}'
```

Unlinking is likewise an API call: `DELETE /api/mc-bridge/link` (requires a signed-in session).

> The extension also keeps a build-free linking page at `/mc-bridge/link` (visit it directly after signing in),
> handy for sending the link to players who cannot find the settings page; the normal flow is the
> "avatar → Settings" path above.

### 3.3 Reporting in game

Players can type `/report <player> <reason>` in game to report other players:

```
/report Steve 他在出生点恶意破坏
```

(the reason argument is free text, e.g. "he is deliberately griefing at the spawn point")

Reporting requires the `mcbridge.report` permission (by default all players have it). After a successful submission the player receives a confirmation message:

```
已举报玩家 Steve，论坛管理员会尽快处理。
```

("Reported player Steve; the forum administrators will handle it as soon as possible.")

**Administrators handle reports in the forum, not in the database.** One report produces two things:

1. A row in the `mc_reports` table (status `pending`, kept for the record);
2. **A discussion in the forum**, carrying the report tag (**multiple tags are allowed**), titled e.g. `[举报] Steve（由 Alex 提交）`
   ("[Report] Steve (submitted by Alex)"), with a body stating the reported player, the reporter, the server, the time, the record number and the report reason.

So administrators only need to open the report tag to see every pending report; no database queries are needed.

The title, the tags and the posting account can all be customised, and **both sides can configure them**: values from the game side `config.yml` take priority, and when left empty the forum-side settings are used as fallback.

**Game side** (`plugins/McBridge/config.yml`, run `/mcbridge reload` after changes):

```yaml
report:
  # {target} {reporter} {reason} {server} are filled in by the plugin; %...% is left to PlaceholderAPI
  title-format: "[举报] {target}（由 {reporter} 提交）"   # "[Report] {target} (submitted by {reporter})"
  # Comma-separated; each item is a slug or a tag ID
  tags: "reports,pending"
  actor: ""            # Username or user ID
  # Attach the reported player's own most recent N public chat messages (0 = off, default 10)
  chat-context-lines: 10
```

> **About the chat context**: only **public chat** is collected; private messages (`/msg`) and commands (`/...`) never enter the buffer;
> the buffer keeps at most `report.chat-context-lines` entries per player and tracks at most 500 players,
> and this record is sent to the forum **only when the player is reported** — it is never broadcast back into the game.
> The forum side renders it as a code block in the discussion body, readable only by administrators who can see the report tag.
>
> Collection uses Paper's `AsyncChatEvent` (LOWEST priority, **does not skip cancelled events**) and
> NeoForge's `ServerChatEvent`. Not skipping cancellations is intentional: many chat-format plugins cancel the original
> event and then broadcast their own copy, so recording only "non-cancelled" events would record nothing at all on such servers.
>
> **How to diagnose a missing chat context** — the three situations now each have a clear signal:
>
> | Symptom | Meaning |
> |------|------|
> | The body says 「未附带聊天记录：游戏服务器上的开关是开着的……」 ("no chat transcript attached: the switch on the game server is on…") | The switch is on, but the player genuinely had not spoken in a public channel before being reported |
> | That section is **completely absent** from the body | The switch is off (`chat-context-lines: 0`), or the game-side plugin is still an old version |
> | `/mcbridge stats` shows `0 名玩家 / 0 条` ("0 players / 0 entries") for 「聊天缓冲」 ("chat buffer") | Collection is not working at all (old plugin version, or nobody on that server has spoken yet) |
> | `McBridge 已启用（平台 … ，版本 …）` ("McBridge enabled (platform …, version …)") in the startup log | Directly confirms which version the server actually loaded |
> | Each report writes one line to the server log: 「已附带 N 条聊天上下文」 ("attached N chat context entries") or the reason it was not attached | The log tells you exactly what was included that time |

> **PlaceholderAPI**: when PlaceholderAPI and the corresponding expansion are installed, `%...%` in `title-format`
> is expanded **as the reporter** (e.g. `%player_name%`); when not installed it is kept as-is.
> The title is rendered **in game** and then sent to the forum with the report — the forum side cannot access game placeholders.
> `/report` exists on both artifacts: Paper/Folia uses Bukkit permission nodes, NeoForge uses OP levels.

**Forum side** (`php flarum mc-bridge:config ...`):

| Setting | Default behaviour | How to change |
|------|---------|---------|
| Report tags | Reads the setting first; otherwise looks up by slug `reports` / name `举报` ("report"); when `flarum/tags` is installed but no such tag exists, **automatically creates a secondary tag** | `--report-tags=4,14` |
| Posting account | The **earliest administrator** (ordinary members may not have permission to post under the report tag; using the reporter's own account would also make the reporter's identity public) | `--report-actor=3` |
| Title template | `[举报] {target}（由 {reporter} 提交）` ("[Report] {target} (submitted by {reporter})"), used only when the game side has not sent a title | `--report-title="[举报] {target}"` |
| Restore automatic | Pass empty values to return to automatic detection | `--report-tags= --report-actor= --report-title=` |

The account and tags resolved on the first report are **written back to the settings**, so `php flarum mc-bridge:config --show`
displays the values actually in effect — this step is also necessary: announcement sync relies on it to identify report
discussions (see below).

> **A note on multiple tags**: `flarum/tags` limits how many primary / secondary tags a discussion can carry
> (admin panel → Tags → Settings). Creation fails when the limit is exceeded, and the failure reason is written to the Flarum log;
> either attach one fewer tag or give the posting account the `bypassTagCounts` permission.

> **Reports are never broadcast into the game.** Report discussions contain the reporter's identity, so when syncing
> announcements the plugin skips discussions carrying **any** report tag, as well as discussions started by the report
> posting account — these two are independent fallbacks, and either one is enough.
>
> Discussion creation is **best-effort**: the report is already stored, and if the forum side errors out (tag limit
> exceeded, nobody has permission to post, etc.), the player's `/report` still returns success with `discussion_id` as `null`.

### 3.4 Report progress and report outcomes

Players can check the reports they have submitted at any time:

```
/report status
```

The output looks like:

```
-------- 我的举报 -------- (最近 3 条)
#42 → Steve · 已处理 · 2026-09-25 13:01
#41 → Herobrine · 处理中 · 2026-09-24 20:15
#39 → Steve · 已驳回 · 2026-09-23 09:02
```

("-------- My reports -------- (latest 3)" / resolved / pending / rejected)

("-------- My reports -------- (latest 3)" / "#42 → Steve · resolved · 2026-09-25 13:01" / "#41 → Herobrine · pending · 2026-09-24 20:15" / "#39 → Steve · rejected · 2026-09-23 09:02")

The API filters by **reporter UUID + server**, so players can only read the reports they submitted themselves and cannot see whom others have reported.

**How administrators mark the outcome** — either of two ways:

| Method | Action | When to use |
|------|------|------|
| Changing tags (automatic) | Move the report discussion into the 「已处理」/「已驳回」 ("resolved" / "rejected") tags | Day-to-day handling; matches the natural action inside the forum UI |
| Console command | `php flarum mc-bridge:report 42 --status=resolved` | Batch or scripted use; `--list` lists recent reports, `--note="..."` adds a note, `--silent` only changes the status without notifying |

The automatic path requires telling the extension which two tags represent these two outcomes (**disabled by default**, because tag names and IDs differ per forum):

```bash
php flarum mc-bridge:report --list                              # First see which reports exist
php flarum mc-bridge:config --report-resolved-tags=15 --report-rejected-tags=16
php flarum mc-bridge:config --show                              # Verify
```

Both methods make **the reporter receive an in-game notification**:

```
[MCBridge] 你提交的举报已被处理：Steve
           管理员备注：已警告该玩家
```

("[MCBridge] Your report has been handled: Steve" / "Administrator note: the player has been warned")

("[MCBridge] The report you submitted has been handled: Steve" / "Administrator note: the player has been warned")

Internally both share the same `Service\ReportOutcome`, so **tagging repeatedly or running the command repeatedly notifies only
once** (no message is sent when the status has not changed), and reopening a report back to `pending` does not notify either.
Notifications use targeted delivery via `mc_outbox`: `target_uuid` is the reporter, and delivery goes only to the server that
submitted this report.

> The automatic tag path listens to `Flarum\Tags\Event\DiscussionWasTagged`. That event is dispatched by the JSON:API
> pipeline **after saving**, so the listener reads the **new** tags — the framework's own
> `CreatePostWhenTagsAreChanged` relies on the same timing.

## 4. Pushing from Flarum to the game

### 4.1 Broadcast

In the admin panel, use a signed-in session that is an administrator (session endpoints require `X-CSRF-Token`):

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/broadcast \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"title":"维护通知","body":"今晚 23:00 重启","type":"broadcast"}'
```

Alternatively you can use a signed machine call (no cookies / CSRF needed); for the signing method see
section 5 of [`../protocol/README.md`](../protocol/README.md).

The game shows the message to everyone after the next outbox poll (20 seconds by default).

## 5. Multi-server

Each server uses a different `server.key` (the same secret is fine). When a message's
`server_key` is `null` it is delivered to all servers; when `server_key` is specified, only to that one.

## 6. Troubleshooting

| Symptom | Diagnostic direction |
|------|---------|
| `composer require` reports *is fixed to … (lock file version) by a partial update but that version is rejected by your minimum-stability* | Unrelated to the extension: the forum lock file pins **beta versions** such as `fof/*`, `ianm/*`, and `minimum-stability` does not allow them, so a partial update of **any** new package is rejected. See the dedicated section below |
| `503 The MC Bridge secret is not configured` | The forum side has not run `mc-bridge:secret` yet |
| `401 Signature verification failed` | The secrets on the two ends differ, or `api-prefix` has been changed |
| `401 Request timestamp is outside the allowed window` | The server clock is out of sync; configure NTP |
| `401 Duplicate nonce detected` | Usually means the request was replayed or sent twice by a proxy |
| `403` + Cloudflare page | Disable the WAF/challenge for that path |
| No announcements in game | Use `/mcbridge outbox` to inspect the queue; confirm that `announcement_tag_ids` includes that tag |
| The game occasionally shows 「论坛没有及时回应，举报**可能**已经提交」 ("the forum did not respond in time; the report **may** have been submitted") | The client timed out but the server has most likely finished processing. The plugin has already retried once under the same `report_uid`, and a duplicate submission does not create a second record, so just follow the prompt and **do not report again**. If it happens frequently, check whether the forum's PHP-FPM is saturated / whether a CDN is slowing down `/api/mc-bridge/*` |

### 6.1 `composer update` rejected by minimum-stability

The full error looks like:

```
Package x/y is fixed to 1.2.3-beta.1 (lock file version) by a partial update
but that version is rejected by your minimum-stability.
Make sure you list it as an argument for the update command. Use the option
--with-all-dependencies (-W) ...
```

**Unrelated to MC Bridge**: the forum lock file pins **beta versions** such as `fof/*`, `ianm/*`,
while the `minimum-stability` of the root `composer.json` is the default `stable`, so any "partial update"
gets stuck on these pinned beta packages when dependencies are re-resolved.

Just do what the error message says — **put the name of the package you want to update into the command** and add `-W`:

```bash
composer update stalirmc/mc-flarum-bridge -W
```

`-W` (`--with-all-dependencies`) lets Composer upgrade or downgrade those pinned dependencies along the way, which is exactly the capability it was missing.

If it still does not work, temporarily relax stability, update, then change it back:

```bash
composer config minimum-stability beta
composer config prefer-stable true
composer update stalirmc/mc-flarum-bridge -W
composer config minimum-stability stable
php flarum cache:clear
```

> The package named in the error is not necessarily MC Bridge: what is rejected is **that beta package in the lock file**.
> Just list **the package you actually want to update** as an argument and add `-W`, as the message suggests.
