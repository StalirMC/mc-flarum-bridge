# MC Bridge — Flarum extension

**English** · [简体中文](README.zh-CN.md)

Turn Flarum into the control plane for a Minecraft server: it receives server status
and game events, and delivers forum announcements, broadcasts and (optionally)
commands to the game.

- Target Flarum: **2.x** (PHP 8.1+)
- Package name: `stalirmc/mc-bridge`
- Authentication: HMAC-SHA256 + timestamp + single-use nonce (see [`../protocol/README.md`](../protocol/README.md))

## Installation

> ⚠️ **Flarum 2.x has no `extensions/` directory.** It only discovers extensions from
> Composer's `vendor/composer/installed.json`, so an extension **must be installed
> through Composer**. See [`../docs/README.md`](../docs/README.md) section 1 for details
> (including the source evidence and three ways to do it).

**Option A — install from the admin panel (recommended)**: Extension Manager → Repositories → add the `vcs` repository
`https://github.com/StalirMC/mc-flarum-bridge` → install
`stalirmc/mc-flarum-bridge:dev-main` → enable "MC Bridge".

**Option B — SSH / Composer**:

```bash
cd /path/to/flarum
composer config repositories.mc-bridge vcs https://github.com/StalirMC/mc-flarum-bridge
composer require stalirmc/mc-flarum-bridge:dev-main
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # check the exact ID with php flarum extension:list
php flarum cache:clear
```

**Option C — without GitHub**: upload **this directory** (`flarum-extension/`) to
`<flarum>/packages/mc-bridge/`, add a `path` repository, then install `stalirmc/mc-bridge:dev-main`.

Once installed:

```bash
php flarum mc-bridge:secret                    # generate the shared secret the extension uses
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn   # end-to-end self-test
```

The complete procedure is in [`../docs/README.md`](../docs/README.md).

## Why the repository root has a composer.json

Because it uses Flarum 2.x's `extra.flarum-subextensions` to declare this directory as an extension, so that
**the whole monorepo can be installed as a single Composer package**:

```json
{
  "autoload": { "psr-4": { "Stalir\\McBridge\\": "flarum-extension/src/" } },
  "extra": { "flarum-subextensions": ["flarum-extension"] }
}
```

`ExtensionManager::subExtensionConfsFromJson()` reads that field. The autoload must be declared in the
root `composer.json` — Composer does not process a subpackage's own `autoload`, and Flarum will not register a namespace
for the extension either.

## Structure

```
extend.php                      route / event / console command registration
migrations/                     migrations creating 5 tables
src/Service/BridgeCrypto.php    HMAC signature and path normalisation
src/Service/BridgeMessages.php  locale resolution and translation wrapper (Chinese by default)
locale/                         locale files: zh-Hans (default), en
src/Api/Controller/             8 controllers (1 abstract base class + 7 endpoint controllers, 10 routes in total)
extend.php                      route / CSRF exemption / event / locale / console command registration
src/Model/                      Eloquent models
src/Listener/QueueAnnouncement.php   new post → outbox queue
src/Console/SecretCommand.php   php flarum mc-bridge:secret
src/Console/ConfigCommand.php   php flarum mc-bridge:config --tags=1,3
src/Console/SelfTestCommand.php php flarum mc-bridge:selftest
```

## Endpoints

Machine interface (HMAC): `heartbeat`, `events`, `outbox`, `bind/start`, `bind/status`, `broadcast`.
Forum interface (session): `status` (public), `link` (POST/DELETE).

For fields and timing see [`../docs/API.md`](../docs/API.md).

## Localisation

The locale files are in `locale/` and ship with the extension:

| File | Language |
|------|------|
| `locale/zh-Hans.yml` | Simplified Chinese (default, 110 keys) |
| `locale/en.yml` | English |

**They must be registered explicitly in `extend.php`**; Flarum does not scan an extension's locale directory automatically:

```php
new Extend\Locales(__DIR__.'/locale'),
```

Switching locale (the locale of console commands and API errors):

```bash
php flarum mc-bridge:config --locale=en      # or --locale=zh-Hans
php flarum mc-bridge:config --show           # show the current locale
```

The default value of `mc-bridge.locale` is `zh-Hans`, **independent of** the forum's own
`default_locale` — so even if the forum defaults to English, the extension's output is still Chinese by default.

Adding a locale: copy `locale/zh-Hans.yml` to `locale/ja.yml`, translate it, and then
switch with `--locale=ja`. Missing keys fall back to Flarum's fallback (`en`), so it is advisable to keep
the key sets of all locales identical (the verification script checks this).

## Settings

| Key | Default | Description |
|----|------|------|
| `mc-bridge.secret` | empty | shared secret, written by the console command |
| `mc-bridge.locale` | `zh-Hans` | output locale, see "Localisation" above |
| `mc-bridge.announcement_tag_ids` | empty | comma-separated tag IDs; empty means sync all new discussions |
| `mc-bridge.sync_replies` | `0` | when set to `1`, replies are pushed to the game as well |
| `mc-bridge.max_announcement_age_days` | `30` | reserved: outbox cleanup window |

Use `php flarum mc-bridge:config` to view and change these settings, with no need to write tinker code by hand.

## Database tables

`mc_servers`, `mc_events`, `mc_outbox`, `mc_bindings`, `mc_bind_codes`.

## Permissions and security

- All machine endpoints require a valid signature; when no secret is configured they return `503` instead of letting the request through.
- A nonce is valid only once (cached for 600 seconds), and timestamps are allowed a ±300 second skew.
- Binding codes are single-use, expire after 10 minutes, and their character set omits easily confused characters.
- The `broadcast` endpoint requires admin identity for session callers.
- The public `status` endpoint exposes only aggregate data and online player names, never the secret.

## Development

```bash
# syntax check (requires PHP on the machine)
find . -name '*.php' -not -path './vendor/*' -exec php -l {} \;
```

This repository also provides a static consistency check script that does not depend on PHP:

```bash
node ../tools/verify.mjs
```
