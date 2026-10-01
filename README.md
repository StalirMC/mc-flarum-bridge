# MC Flarum Bridge

[![CI](https://github.com/StalirMC/mc-flarum-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/StalirMC/mc-flarum-bridge/actions/workflows/ci.yml)
[![Packagist](https://img.shields.io/packagist/v/stalirmc/mc-flarum-bridge.svg)](https://packagist.org/packages/stalirmc/mc-flarum-bridge)
[![Downloads](https://img.shields.io/packagist/dt/stalirmc/mc-flarum-bridge.svg)](https://packagist.org/packages/stalirmc/mc-flarum-bridge)

**English** · [简体中文](README.zh-CN.md)

An open-source bridge that connects a Minecraft server and a [Flarum](https://flarum.org) forum in both directions.

---

> ## ⚠️ This project is still under construction
>
> **Its usability is not yet proven; please do not run it in production.** Interfaces, configuration keys and database schemas may change at any time.
>
> **What has been confirmed**
> - CI is green: `gradle build` compiles for real, every `php -l` passes, and 384 static consistency checks plus 46 protocol conformance checks pass
> - **A real local build passes too** (JDK 21 + Gradle 8.10): both the Paper plugin and the NeoForge mod project compile, the `verifyJar` content assertions pass, and the shared core's **77 runtime self-test checks pass on a real JVM** (YAML parsing / language fallback / configuration validation / regex allow-list / display-channel parsing / chat buffer bounds / defaults for an older config.yml)
> - **The plugin has been enabled successfully on a real Paper server**: the data folder `plugins\McBridge\` and the extraction paths of `config.yml` and `lang/*.yml` are all evidenced by the server log
> - On a real forum (Flarum 2.0.0-rc.8): installation, `migrate`, enabling the extension, and running the self-test command have all been done
> - The **build output and content assertions of both artifacts** have been exercised: the Paper plugin jar (`plugin.yml` plus the Paper entry points, with zero platform references in the shared layer and no third-party code in the jar) and the NeoForge mod jar (`META-INF/neoforge.mods.toml` plus a `@Mod` entry point)
> - **The NeoForge side compiles with the real toolchain**: ModDevGradle fetches Minecraft 1.21.1 and NeoForge 21.1.100, runs the full NeoForm decompile → patch → recompile pipeline, and the mod sources compile into an installable mod jar
> - Fixed one by one along the way: the CSRF exemption (`Extend\Csrf`), the HMAC signing chain, request-body reading, mass assignment, a forum-side boot crash (the settings page is a lazily loaded chunk and has to be extended by module path), an admin-side boot crash (2.x replaced `app.extensionData` with `app.registry`), a pointless warning on every boot, **"the forum still shows 'not linked' after linking"** (a missing GET route), and **"the jar still shipped the old version after a version bump"** (`expand` was not declared as a task input) — see [VERIFICATION.md](docs/VERIFICATION.md) sections 2.5 / 2.6 / 2.8 / 2.9
>
> **What has not been verified**
> - **Only Paper has actually been loaded**: Folia and NeoForge remain at "compiles + static assertions" and have never started on a real server
> - **Announcement push and broadcast are not verified end to end** (the plugin enables, but no data has been observed actually arriving on the forum); account linking was invisible before because of defect 2 and needs re-testing after the 0.0.2 fix
> - The front-end settings block (`js/dist`) **has not been confirmed working in a browser**: the root cause of the boot crash is located and fixed, but the binding-code input still needs to be checked on a live forum page
> - Long-term stability, concurrency and multi-server setups are all unverified
>
> You are welcome to try it and report problems, but please assess the risk yourself.

---

> **Repository**: https://github.com/StalirMC/mc-flarum-bridge
> **Latest CI**: [all three jobs (static + protocol / PHP lint / Gradle build) pass](https://github.com/StalirMC/mc-flarum-bridge/actions)

| Directory | Component | Stack |
|-----------|-----------|-------|
| `flarum-extension/` | Flarum extension: secured REST API, data storage, the announcement delivery queue | PHP 8.1+ / Flarum 2.x |
| `mc-plugin/` | Minecraft side (**two artifacts**): announcement polling, broadcasting, account linking, reporting | Java 17 core bytecode / Paper 1.21.x · Folia (`plugins/`); NeoForge 21.1.x + Minecraft 1.21.1 (`mods/`) |
| `protocol/` | The wire contract and JSON Schema shared by both sides | Markdown / JSON Schema |
| `docs/` | [Deployment guide](docs/README.md), [API reference](docs/API.md), [Verification report](docs/VERIFICATION.md), [Release process](docs/RELEASING.md) (maintainers) | Markdown |
| `tools/` | Static consistency checker, mock forum, protocol conformance test | Node.js |

> **Documentation language**: an unsuffixed file is English and the matching `*.zh-CN.md` is Simplified Chinese — this applies to every document in `docs/` as well as to this README and the per-component READMEs. The one exception is [`protocol/README.md`](protocol/README.md), which is English only.

## Features

- **Announcement delivery** — new discussions carrying a chosen tag (or all of them) are queued automatically, and the plugin broadcasts them in game once it polls.
  **The presentation is multi-select** (`game.announce-display`): `chat` for a chat line, `actionbar` for the line above the hotbar, `title` for a large centred title (the body becomes its subtitle) and `bossbar` for a bar across the top of the screen. Several can be active at once, for example `"chat,bossbar"`. How long a title and a bar stay on screen is configurable.
- **Forum → game broadcast** — an administrator pushes one message from the forum and every player sees it in game.
- **Read announcements in game** — `/mcbridge news` shows the latest forum announcements (no administrator permission needed).
- **Account linking** — `/bind` in game returns a one-time code; once the forum consumes it, a unique two-way mapping between the forum account and the Minecraft UUID exists.
- **The binding shown on the forum** — the profile page displays the linked Minecraft account, and a linked account gets a **grass block badge** (hung on `User.badges()`, so it appears next to post authors, on user cards and on the profile page at the same time, styled by the theme; the player name is in the tooltip). **Visible to signed-in users only** — a guest's payload does not carry these fields at all.
- **A prompt for unlinked players on join** — a player who has not linked an account is told once how to do it (`game.prompt-unbound`, on by default); when the forum is unreachable it stays silent instead of spamming on every join.
- **Reporting in game** — `/report <player> <reason>` reports another player. Besides recording it in `mc_reports`, the report **automatically becomes a discussion on the forum** filed under the report tags (**any number of them**, for example "report + pending"), so a moderator just opens the tag instead of querying a database.
  The title, the tags and the publishing account are all customisable and **configurable from either side**: the game's `config.yml` wins, the forum settings are the fallback. The title template supports **PlaceholderAPI** (`%player_name%` and friends, expanded as the reporter), because the title is rendered in game before it is sent to the forum. **A report discussion is never broadcast back into the game.**
- **Reports carry context** — a report can attach the reported player's **own** recent public chat (`report.chat-context-lines`, 10 by default, 0 to disable), so a moderator sees what the player actually said rather than only the reporter's summary. Only public chat is captured: **private messages and commands never enter the buffer**, the buffer is capped both per player and in the number of players tracked, and the transcript is sent to the forum only when **that player is reported** — never broadcast into the game.
- **Report progress in game** — `/report status` lists the reports a player has filed and their state (pending / handled / rejected). The endpoint filters by reporter UUID plus server, so a player only ever sees their own.
- **An outcome for every report** — when a moderator moves a report discussion into the "handled" / "rejected" tag (`--report-resolved-tags` / `--report-rejected-tags`), or runs `php flarum mc-bridge:report <id> --status=resolved`, the reporter **is notified in game**. Both paths share one implementation, so **re-tagging or re-running the command notifies exactly once**.
- **Localisation** — the forum side (`locale/`) and the game side (`lang/`) are independent and **both default to Simplified Chinese**; a missing key falls back to Chinese rather than exposing a raw key name.

## Security design

- Every machine endpoint is signed with **HMAC-SHA256** over `{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}`.
- Timestamps are allowed a ±300 second skew, a nonce is single-use (cached for 600 seconds), and signatures are compared in constant time.
- The signed path starts at `/api/mc-bridge`, so a Flarum installed in a sub-directory needs no extra configuration.
- When no secret is configured, machine endpoints answer `503` instead of letting requests through.
- The report-progress endpoint filters by **reporter UUID plus server**: a player can only read their own reports and cannot see who reported whom.
- Chat context captures public chat only (private messages and commands never enter the buffer), is capped per player and overall, and travels only with a report.
- A binding code is single-use, expires after 10 minutes, and its alphabet omits easily confused characters.

## Quick start


### 1. Forum side

The extension is declared as a `flarum-subextension`, so the whole repository installs as a single package, and it is on Packagist:

https://packagist.org/packages/stalirmc/mc-flarum-bridge
```bash
composer require stalirmc/mc-flarum-bridge
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # use php flarum extension:list to confirm the exact ID
php flarum cache:clear
php flarum assets:publish            # needed whenever the extension's front-end bundle changes
php flarum mc-bridge:secret          # generate the shared secret and paste it into the plugin config
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn   # full end-to-end self test from the forum side
```

### 2. Game side (two artifacts — pick the one your server can load)

[https://github.com/StalirMC/mc-flarum-bridge/releases](https://github.com/StalirMC/mc-flarum-bridge/releases)

Download the one you need from that page.


## Building
```bash
cd mc-plugin && gradle build
```
Paper/Folia: copy build/libs/McBridge-<version>.jar into server/plugins/
              then edit plugins/McBridge/config.yml
NeoForge   : copy neoforge/build/libs/McBridge-neoforge-<version>.jar into server/mods/
              then edit config/mc-bridge/config.yml
Fill in the forum URL and the shared secret, then run /mcbridge reload

The first NeoForge build downloads Minecraft 1.21.1 and runs the NeoForm pipeline
(decompile → patch → recompile). It takes roughly 5-10 minutes and needs network access;
afterwards it is cached. To build only the Paper plugin: gradle :build


See [`docs/README.md`](docs/README.md) for deployment and [`docs/API.md`](docs/API.md) for the endpoints.

## The two artifacts

`mc-plugin` is a multi-project build that produces **two jars** from one shared core:

```
mc-plugin/build/libs/McBridge-<version>.jar              <- goes into Paper / Folia's plugins/
├── plugin.yml                 <- read by Paper / Folia, folia-supported: true
├── config.yml, lang/*.yml
└── cn/stalir/mcbridge/
    ├── (shared core)          <- depends on the JDK and Gson only, zero platform references
    └── paper/                 <- PaperPlatform carries both the Folia and the Bukkit scheduler

mc-plugin/neoforge/build/libs/McBridge-neoforge-<version>.jar   <- goes into NeoForge's mods/
├── META-INF/neoforge.mods.toml <- read by NeoForge (side = SERVER)
├── config.yml, lang/*.yml
└── cn/stalir/mcbridge/
    ├── (shared core)          <- byte-for-byte the same as above
    └── neoforge/              <- the @Mod entry point, NeoForgePlatform, the Brigadier commands
```

The two jars are independent and do not depend on each other; each platform loads only the entry point named in its own descriptor.
The shared core (announcement rendering, every command's wording) is identical, so the protocol behaviour is byte-for-byte the same across platforms.

**The shared core does not depend on Adventure**: Paper ships Adventure, while Minecraft 1.21.1 (and therefore NeoForge) does not.
The core renders its own `cn.stalir.mcbridge.Message` and each platform converts it to its own component type at delivery time (Paper → Adventure, NeoForge → `net.minecraft.network.chat.Component`).

## Protocol

```
┌──────────────────┐   HMAC-SHA256   ┌────────────────────────┐
│  Minecraft server│ ──────────────► │  Flarum extension      │
│  Paper / Folia   │  outbox (GET)   │  /api/mc-bridge/*      │
│  NeoForge        │  bind/start     │                        │
│                  │  bind/status    │  mc_outbox             │
│                  │  report         │  mc_bindings           │
│                  │  broadcast      │  mc_bind_codes         │
│                  │ ◄────────────── │  mc_reports            │
│                  │  outbox         │  mc_servers            │
└──────────────────┘                 └────────────────────────┘
```

The full specification (including Bash and PHP signing examples) is in [`protocol/README.md`](protocol/README.md).

## Verification

This repository ships a set of checkers that need neither PHP nor a JDK:

```bash
cd mc-flarum-bridge

# Static consistency: PSR-4 layout, resolvable class references, Java package structure,
# plugin.yml agreeing with the code, every config.yml key and message key present, the HMAC
# canonical string identical in PHP and Java, migrations matching the models, every route documented
node tools/verify.mjs

# Protocol conformance: starts a mock Flarum and checks signing / replay / timestamps /
# outbox consumption semantics / the binding-code alphabet
node tools/protocol-test.mjs
```

With a toolchain available you can additionally run:

```bash
find flarum-extension -name '*.php' -exec php -l {} \;   # PHP syntax
cd mc-plugin && gradle build                                # Java build (Paper plugin + NeoForge mod)
```

> If your machine has no PHP or JDK, just push the repository to GitHub: `.github/workflows/ci.yml`
> runs `php -l`, a real `gradle build` and both test suites above in an environment with PHP 8.3
> and JDK 21, and uploads the built plugin jar and mod jar.

## Version support

| Platform | Status | Notes |
|----------|--------|-------|
| **Paper** 1.21.x | ✅ Full functionality | The primary target, already enabled on a real server |
| **Folia** 1.21.x | ✅ Compiles + asserted | Scheduled through Folia's `AsyncScheduler` / `GlobalRegionScheduler`, with `folia-supported: true` in `plugin.yml`; it has not yet been started on real Folia |
| **NeoForge** 21.1.x (Minecraft 1.21.1) | ✅ Compiles + asserted | A separate mod jar with a `@Mod("mc_bridge")` entry point, Brigadier commands and `META-INF/neoforge.mods.toml`; the server side only (`side = SERVER`), clients do not need it |

- Minecraft: Paper / Folia 1.21.x (override with `-PpaperApiVersion=`), NeoForge 21.1.x for 1.21.1
- Flarum: 2.x (PHP 8.1+)
- Java: the shared core targets **17** bytecode (it still loads on the Java 21 that Paper/Folia run);
  the `paper` and `neoforge` modules target 21 because their APIs require it; the build toolchain is JDK 21

Releases are tag-driven: `git tag v0.0.6 && git push origin v0.0.6` triggers
[`release.yml`](.github/workflows/release.yml), which first checks that the tag matches the version in
`gradle.properties` and then builds and uploads **both the plugin jar and the mod jar** to a GitHub Release.
The complete process (including Packagist sync and renaming caveats) is in the [release guide](docs/RELEASING.md) (maintainers).
