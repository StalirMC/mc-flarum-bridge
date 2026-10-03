# Changelog

Newest first. The version numbers here are the ones declared in
`mc-plugin/gradle.properties`, `Version.java` and `flarum-extension/composer.json`; a release
tag is always `v` plus that version. See [CONTRIBUTING.md](CONTRIBUTING.md) for the version
discipline and [docs/RELEASING.md](docs/RELEASING.md) for the release procedure.

> English only. Unlike the READMEs and the documents under `docs/`, this file has no
> `*.zh-CN.md` counterpart: a translated copy of a running log would drift out of date, so
> the English text is the only version. ([`protocol/README.md`](protocol/README.md) is the
> other deliberate exception.)

## [Unreleased]

### Added

- A **real-server smoke test**: `tools/smoke-server.mjs` downloads a Paper or Folia server,
  starts it with the built jar, and asserts the plugin enables, that the version matches the
  jar, and that the server stops cleanly. Wired into CI as the `smoke` job, running on Paper
  1.21.1 and Folia 1.21.8 - see [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md).
- `SHA256SUMS` is now published with every release, so a downloaded jar can be verified.
- `SECURITY.md` (how to report a vulnerability privately, what the trust boundary is, and a
  hardening checklist for operators) and `CONTRIBUTING.md` (the gates, the version
  discipline, the invariants CI enforces).
- Chinese counterparts: `SECURITY.zh-CN.md`, `CONTRIBUTING.zh-CN.md`,
  `docs/SMOKE-TEST.zh-CN.md`.

### Changed

- CI no longer skips `docs/API.md`. It is a markdown file, but `tools/verify.mjs` asserts it
  against the registered routes, so a documentation-only commit changing it is still checked.
- `tools/verify.mjs` now requires the `smoke` job to exist, so the real-server check cannot
  be dropped silently (386 checks).
- The READMEs no longer claim "only Paper has been loaded": Paper and Folia both load and
  enable on real servers.

## [0.0.21] - 2026-09-26

### Fixed

- **Chat transcripts were never captured on a server whose chat-format plugin cancels
  `AsyncChatEvent`.** The listener ran at `MONITOR` priority with `ignoreCancelled = true`,
  so on exactly those servers it saw nothing and every report went out without context. It
  now runs at `LOWEST` and records cancelled events too.
- Diagnostics for that case: the running version appears in the startup line, each report
  logs how much context it carried, and `/mcbridge stats` reports the chat buffer size, so
  "feature enabled but nothing captured" is visible instead of silent.

## [0.0.20] - 2026-09-26

### Added

- **Multi-channel announcements**: `game.announce-display` accepts any combination of
  `chat`, `actionbar`, `title` and `bossbar`. An unknown value is logged and skipped rather
  than silently ignored, and chat stays the fallback.
- **The report loop closes**: a report is finished either by moving its discussion into the
  forum's resolved/rejected tag or with `php flarum mc-bridge:report <id> --status=...`. The
  reporter is told in game exactly once, whoever (or whatever) closed it.

### Documentation

- Corrected the release notes: the release workflow publishes two artifacts, not one
  universal jar.

## [0.0.19] - 2026-09-25

### Changed

- **Breaking:** **Velocity support was removed.** The plugin jar now serves Paper and Folia
  only, and the build fails if any Velocity entry point or descriptor survives into the jar.
- **Added a NeoForge mod** for Minecraft 1.21.1 (`mc-plugin/neoforge/`), server side only.
  Because Minecraft 1.21.1 does not ship Adventure, the shared core renders its own message
  type and each platform converts it.

## [0.0.18] - 2026-09-25

### Fixed

- The report guard no longer silences an administrator's announcements.

## [0.0.17] - 2026-09-25

### Fixed

- A report that times out on the client side is no longer reported to the player as failed.

## [0.0.16] - 2026-09-25

### Added

- The report discussion's title, tags and author are configurable.

## [0.0.15] - 2026-09-25

### Added

- Player reports from the game become a **tagged forum discussion**, optionally carrying the
  reported player's recent public chat.

## [0.0.14] - 2026-09-25

### Fixed

- Two classes were used without a `use` statement.

### Documentation

- Added the missing `minimum-stability` troubleshooting section.

## [0.0.13] - 2026-09-25

### Fixed

- The incremental migration no longer fatals `php flarum migrate` (a nested closure read
  `$schema` without capturing it).

## [0.0.12] - 2026-09-19

### Added

- In-game news, player reports and activity polls; instant binding feedback; a broadcast
  audit trail.
- Mock-forum test coverage for reports, activity polls and voting.

### Changed

- Polls became an integration with the forum's `fof/polls` instead of an own implementation,
  and then the poll feature itself was removed.
- CI stopped running the full suite for documentation-only pushes.

## [0.0.11] - 2026-09-19

### Changed

- **Breaking:** server status reporting and gameplay event reporting were removed.

## [0.0.10] - 2026-09-19

### Fixed

- Badge attribute translations are wrapped in `extractText`, so no stray comma is drawn.

### Changed

- The author is `StalirMC`; a stale generated descriptor was dropped.

## [0.0.9] - 2026-09-18

### Fixed

- The badge follows the core badge convention, so the player name is not drawn twice.

## [0.0.8] - 2026-09-18

### Added

- The author badge shows the grass block and the player name, without an `MC:` prefix.

## [0.0.7] - 2026-09-18

### Changed

- Remote commands and the status page were dropped; the admin page header was fixed.

## [0.0.6] - 2026-09-18

### Changed

- The Composer packages were renamed to the `StalirMC` vendor.

## [0.0.5] - 2026-09-18

### Added

- Grass-block author badges and a status card in the theme sidebar.

## [0.0.4] - 2026-09-18

### Fixed

- An actor check that exists on Flarum 2.0.0-rc.8 (the audited release).

## [0.0.3] - 2026-09-18

### Added

- Profile binding display, author badges, a join prompt and a status page.

## [0.0.2] - 2026-09-18

### Fixed

- Startup warnings were silenced and the binding status route (`GET /api/mc-bridge/link`),
  which had been written but never registered, was wired up - this is why the forum kept
  showing "not linked" after a successful link.

## [0.0.1] - 2026-09-18

### Added

- The first working bidirectional bridge: a Flarum extension with a signed machine API and
  storage, plus the Minecraft plugin jar.
- Authentication for machine requests: HMAC-SHA256 over
  `{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}` with a ±5 minute window and single-use
  nonces.
- Multi-language support, defaulting to Simplified Chinese.
- A binding flow with a settings-page section and a build-free link page, and a static
  consistency checker plus a protocol conformance test over a mock forum.
