# Contributing

**English** · [简体中文](CONTRIBUTING.zh-CN.md)

Two runnable artifacts and one Flarum extension live here, and the checks that guard them
are not the usual "it compiled" ones. This file lists what to run and which invariants are
easy to break by accident.

## Requirements

| Need | Version | For |
|------|---------|-----|
| JDK | **21** | Building `mc-plugin/` (`paper-api` 1.21.1 is compiled for 21, so 17 cannot resolve it) |
| Node.js | 22 or newer | `tools/` - the static, protocol and smoke suites |
| Gradle | 8.10 | `cd mc-plugin && gradle build` |
| PHP | 8.3 + Composer | Linting the extension, `composer validate` |
| A JVM's worth of RAM | ~1.5 GB | Only the smoke test, which starts a real server |

A PHP runtime is **not** needed to work on the Java or Node side, and a Minecraft server is
not needed to work on the PHP side. CI has all of it.

## The gates

Run these before opening a pull request. All of them run in CI on every push, and the exact
same commands work locally.

| Command | What it proves | Count |
|---------|----------------|-------|
| `node tools/verify.mjs` | Structure, contracts and references across both sides: descriptors, config keys, message keys, routes vs. documentation, migration contract, API surface of the audited Flarum release, CI self-check | 386 checks |
| `node tools/protocol-test.mjs` | That the wire protocol is implementable: a mock forum drives the documented endpoints and the Java/PHP behaviour is compared against it | 46 checks |
| `cd mc-plugin && gradle build` | Real compilation plus the in-JVM self test and both jar assertions (`selfTest`, `verifyJar`) | 77 self-test checks + 2 jar assertions |
| `node tools/smoke-server.mjs --jar <jar> --project paper --version 1.21.1` | That a **real server loads and enables** the jar - see [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md) | Paper and Folia runs |
| `find flarum-extension -name '*.php' -exec php -l {} \;` | No `Parse error` anywhere | all PHP files |
| `cd flarum-extension && composer validate --no-check-lock --no-check-publish` | The manifest is installable | - |

`mc-plugin/neoforge/` is built as part of `gradle build`; it is a separate Gradle project
because ModDevGradle has to own the Minecraft dependency.

## Version discipline

One version number lives in three files and they have to agree, or CI fails:

- `mc-plugin/gradle.properties` → `version=`
- `mc-plugin/src/main/java/cn/stalir/mcbridge/Version.java` → `VERSION`
- `flarum-extension/composer.json` → `version`

The release workflow additionally rejects a tag that is not `v` + that version. To release:
bump all three, commit, then push the tag (see [docs/RELEASING.md](docs/RELEASING.md)).

## Invariants that are easy to break

These are enforced, and the failure message does not always name the cause:

- **The shared core stays platform-neutral.** Nothing under `cn/stalir/mcbridge` (outside
  the `paper`/`neoforge` packages) may reference `org.bukkit`, `io.papermc`, `net.kyori`,
  `net.minecraft` or `net.neoforged`. The class files are scanned, so an import that is
  only used in javadoc does not count, but a real one fails the build. Adventure is Paper's,
  and Minecraft 1.21.1 does not ship it - hence the project's own `Message` type.
- **Every registered route must appear in `docs/API.md`.** `verify.mjs` fails on a route
  that is missing there, and warns when `protocol/README.md` does not mention it.
- **`plugin.yml` and the code must agree.** A command or permission used in Java but not
  declared in `plugin.yml` fails; one declared but never checked warns.
- **Config and language files must stay in parity.** Every key `BridgeConfig` reads must
  exist in `config.yml`, and every message key used by the plugin must exist in
  `lang/zh_CN.yml` and `lang/en.yml`.
- **Migrations follow the Flarum 2.x contract**: each file returns an `['up' => ..., 'down'
  => ...]` array whose closures accept an `Illuminate\Database\Schema\Builder`. A nested
  closure that reads `$schema` without capturing it fataled `php flarum migrate` once
  already; `verify.mjs` and the CI reflection check both look for it.
- **Extensions are registered through `app.registry`**, not `app.extensionData` (2.x
  replaced it), and the settings page is a lazily loaded chunk that has to be extended by
  module path.
- **Do not hand-edit `flarum-extension/js/dist`.** It is built output; rebuild it with
  `npm run build` in `flarum-extension/js`.

## Documentation

- An unsuffixed file is **English**; the matching `*.zh-CN.md` is **Simplified Chinese**.
  This covers the READMEs and everything under `docs/`.
- Two files are English only, because a translated copy would drift: `protocol/README.md`
  and `CHANGELOG.md`.
- Changing behaviour means updating **both** language versions, and the two must keep the
  same headings, the same code fences and the same table rows so they can be diffed against
  each other.
- Runtime strings the software prints in Chinese (log lines, shipped default values) are
  quoted verbatim in the English documents, with a gloss in parentheses. They are data, not
  prose - do not "translate them away".

## Commits and pull requests

- Commit subjects use conventional prefixes, as the history does: `feat:`, `fix:`,
  `docs:`, `ci:`, `chore:`, `refactor:`, `test:`.
- Keep a change reviewable, and say which gate you ran. CI runs the rest.
- A CI failure is a real signal here: the suites were written to catch things a compiler
  cannot, and they have caught genuine defects (a route that was never registered, a jar
  that shipped the old version after a bump, a chat listener that captured nothing).
- Do not include a working `security.secret`, player personal data or chat transcripts in a
  commit, an issue or a screenshot.
