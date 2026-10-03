# Verification Report

**English** · [简体中文](VERIFICATION.zh-CN.md)

This document faithfully records the verification status of the MC ↔ Flarum Bridge: **what has been verified, by what method,
and why some verification must be carried out on your own server.**

## 1. Local environment limitations

A toolchain probe was run on the machine where this project was written, with the following results:

| Tool | Status |
|------|------|
| Node.js | ✅ v24.21.0 |
| PHP | ❌ Not installed (absent from PATH and from the common installation directories) |
| JDK (java / javac) | ❌ Not installed |
| Gradle | ❌ Not installed |
| Composer | ❌ Not installed |

Therefore `php -l`, `./gradlew build` and `composer require` **cannot be executed in this environment**.
This is an environment limitation, not a code problem. Every invariant that can be verified without
PHP/JDK has been verified automatically, and for the rest copy-pasteable runtime verification steps are
given (Section 4).

## 2. Verification completed

### 2.1 Static consistency checks —— all 210 items pass

```bash
cd mc-flarum-bridge
node tools/verify.mjs
```

Measured output: `checks run: 210 / errors: 0 / warnings: 0 / ALL CHECKS PASSED`

The 15 categories of invariants covered:

| # | Check | Why it matters |
|---|--------|-----------|
| 1 | All JSON files parse | 3 protocol schemas + composer.json are syntactically correct |
| 2 | PHP PSR-4 layout (namespace + class name vs path) | one wrong character means a fatal "class not found" error |
| 3 | Every `use Stalir\McBridge\...` resolves to a real file | catches typos and names missed during renames |
| 4 | Every `::class` reference in `extend.php` exists (core classes marked external) | route/command/listener registration will not fail silently |
| 5 | Java package declarations match directories, type names match file names | the first class of problems `javac` reports outright |
| 6 | Every in-project import in the Java sources resolves | catches cross-package reference errors |
| 7 | `plugin.yml` main class, commands and permissions match the code | a wrong main class means the plugin simply does not load |
| 8 | The **19** config paths read by `BridgeConfig` are all defined in `config.yml` | silent failure caused by config reads returning null |
| 9 | **Plugin localisation**: the `language` in `config.yml` points at an existing `lang/*.yml`, `config.yml` no longer carries messages, `Messages.DEFAULT_LANGUAGE`/`FALLBACK_LANGUAGE` match the bundled languages, all **53** keys used in code exist in the default language, the key sets of the individual languages are exactly the same, and there are no dead keys that are declared yet never referenced | players never see the literal `bind-failed`, and no raw key names are displayed because a translation is missing a key |
| 10 | **The HMAC canonical string order is identical in PHP and Java** (`timestamp→nonce→method→path→body`) | the two sides disagreeing on the algorithm = 401 on every request |
| 11 | The 5 tables created by the migrations correspond one-to-one to the `$table` of the 5 models | querying a table that does not exist |
| 12 | All 10 registered routes are documented in `docs/API.md` | documentation drifting away from the implementation |
| 13 | **Flarum 2.x framework contracts**: migrations must return `['up'=>fn(Builder $schema), ...]`, models must explicitly enable `$timestamps`, machine routes must be exempted with `Extend\Csrf` and **session routes must not be exempted**, console commands must extend `AbstractBridgeCommand` and implement `fire()`, **the locale directory must be registered with `Extend\Locales`**, placeholders must use ICU `{name}` syntax, `BridgeMessages.DEFAULT_LOCALE` must match the bundled languages, and the key sets of the individual languages must be identical | these are real defects found during review and live testing, now frozen into automated regression protection |
| 14 | **CI workflow self-check**: `working-directory` paths exist, the referenced `tools/*.mjs` exist, the four jobs are declared, artifact paths match Gradle's default output | avoids going red on the very first push because of a mistyped path |
| 15 | **Java compilation hazards**: the JDK/third-party simple names in use must already be imported (comments stripped first), and scheduler calls must not pass method references directly without an explicit `(Runnable)` cast | these two classes of problem were the real cause of the first CI compilation failure, and can now be intercepted without a compiler |

Item 10 is the most critical contract of this bridge: the two sides implement the same signature
algorithm independently, in PHP and in Java; the script extracts each side's field order and asserts
that they are exactly identical, while also confirming that PHP uses
`hash_hmac('sha256')` + `hash_equals()` (constant-time comparison) and Java uses
`Mac("HmacSHA256")` + `HexFormat`.

### 2.2 Protocol conformance tests —— all 32 items pass

```bash
node tools/protocol-test.mjs
```

Measured output: `checks: 32 / passed: 32 / failed: 0 / ALL PROTOCOL CHECKS PASSED` (stable across 3 consecutive runs)

It spins up a zero-dependency mock Flarum (`tools/mock-flarum.mjs`), uses it as the server, and uses
an independently implemented JS signer (equivalent to `Signature.java`) as the client, covering:

1. Canonical string semantics (5 segments joined by `\n`, **no trailing newline**, uppercase method, empty body)
2. HMAC correctness (**the official RFC 4231 vectors** + cross-validation against an independent ipad/opad construction)
3. Path normalisation (query string stripped, Flarum subdirectory stripped)
4. A correct signature is accepted (heartbeat / events / outbox with a query string)
5. Wrong secret → 401; missing signature header → 401
6. Tampered body → 401; tampered signature → 401
7. Timestamp window (400s in the past, far future) → 401
8. nonce replay → 401
9. Public endpoints need no signature, and the response contains no secret
10. The `peek` semantics of the outbox (peek does not consume / the second consume is empty / the aliases agree)
11. Binding code format and character set (8 characters, no `0/O/1/I`), and `already_bound` is returned when already linked
12. Subdirectory installation and **25-way concurrent** signed requests (all with distinct nonces)

> This test is independent of the PHP/Java implementations; what it verifies is that **the protocol
> specification itself can be implemented correctly**, and it is the strongest offline evidence that
> the two sides' algorithms really do interoperate.

### 2.3 Independent code review (2 blockers found and fixed)

Two independent subagents performed adversarial reviews of the two sides. **The Flarum-side review compared
line by line against the real source of the `flarum/framework` 2.x branch** (and confirmed that branch's
`Application::VERSION` really is `2.0.0-rc.8`); the Paper-side review checked item by item against the
official javadoc. The review found **2 blockers that would have made the functionality completely
unusable**, both of which have been fixed:

#### Flarum side

| Severity | Problem | Resolution |
|------|------|------|
| **BLOCKER** | The migrations used `$this->schema`. Flarum 2.x's `Migration` has been refactored into a purely static factory class with **no schema property**; the 2.x contract is to return `['up' => fn(Builder $schema), ...]`. The original form would inevitably throw `Call to a member function hasTable() on null` during `php flarum migrate`, and **not one of the 5 tables could be created** | changed to return an array of closures ✅ |
| **BLOCKER** | Flarum enforces CSRF validation for the entire `api` middleware stack, exempting only the `token`/`registration-token` routes. The server has neither a session nor a CSRF token, so **all POST endpoints would be stopped with 400**, and the HMAC authentication code would never even be reached | The first attempt inserted a pass-through middleware with `Extend\Middleware('api')->insertBefore(CheckCsrfToken::class, ...)` — **measured ineffective** (`flarum.api.handler` is a singleton, the pipeline is built only once, and extenders registered afterwards are silently ignored); the live loopback request still returned `400 csrf_token_mismatch`. In the end we switched to the official `Extend\Csrf()->exemptRoute()` to exempt machine endpoints by route name; `link`/`unlink` are deliberately not exempted, while `broadcast` is exempted because it must support machine calls, and the session path has its `X-CSRF-Token` validated separately inside the controller ✅ |
| HIGH | `Flarum\Database\AbstractModel` defaults to `$timestamps = false` (the opposite of Laravel), which made `created_at`/`linked_at` permanently `null` and rendered `mc_events`' composite index ineffective | all 5 models now explicitly set `$timestamps = true` ✅ |
| HIGH | The console command extended Symfony's native `Command`, so Flarum would not call `setLaravel()`, leaving `$this->laravel` and the `info()/error()` helpers unavailable | switched to `Flarum\Console\AbstractCommand` + `fire(): int` ✅ |
| MEDIUM | The `broadcast` authentication branch would silently degrade to 403, so during troubleshooting it was misdiagnosed as a permission problem | any bridge authentication header forces the HMAC path, and failure returns 401 directly ✅ |
| MEDIUM | The unauthenticated 401 from `/link` leaked Flarum's JSON:API error envelope, inconsistent with this extension's unified format | catch `NotAuthenticatedException` and return the unified `{"error":...}` ✅ |
| MEDIUM | There is no transaction/row lock between the outbox SELECT→UPDATE, so concurrent polling can deliver twice | transaction + `lockForUpdate()` + idempotent UPDATE ✅ |
| MEDIUM | No admin settings UI, and `locale/en.yml` is a dead key | the documentation now clearly states that console commands are used instead, and the locale is marked as reserved ✅ |
| LOW | `player_uuid` being both `unique()` and `index()` is redundant; the composer constraint `php ^8.1` is too broad; the README counts and the enable command were wrong | all corrected ✅ |

#### Paper side

Review conclusion: **12 Java files with zero compilation errors**, imports complete, no type name
conflicts, override signatures correct, and every referenced API empirically present on Paper 1.21.1;
Gson 2.10.1 and Adventure are compile dependencies of
`paper-api`, so **no shading is needed**.

| Severity | Problem | Resolution |
|------|------|------|
| **BLOCKER** | `gradle.properties` is a pure key/value file with no Groovy evaluation, yet I had written `providers.gradleProperty(...)` into it, which turned the paper-api coordinates into an invalid string, broke dependency resolution, and made all 12 files report `package org.bukkit does not exist` | changed to the pure data `paperApiVersion=1.21.1-R0.1-SNAPSHOT` (the `-P` override still works) ✅ |
| **BLOCKER** | `settings.gradle` was missing, so Gradle 7+ refuses to execute that build at all | added ✅ |
| HIGH | There was no Gradle wrapper while the documentation told users to run `./gradlew` | the documentation now gives the `gradle wrapper` generation step ✅ |
| HIGH | Async tasks touched the Bukkit API directly (`getOnlinePlayers`/`getMotd`/`getMaxPlayers`), which Paper explicitly forbids | changed to **snapshot collection on the main thread + async HTTP only** ✅ |
| MEDIUM | Every `/mcbridge reload` leaked one `HttpClient` (thread + connection pool) | added `close()`, releasing the old instance on reload ✅ |
| MEDIUM | `onDisable` sent two requests synchronously, blocking shutdown for up to about 20 seconds when the forum was unreachable | the shutdown path now uses a 3-second timeout ✅ |
| MEDIUM | Fields read and written across threads had no `volatile` | added ✅ |
| MEDIUM | `BindCommand` called `getAsBoolean()` without checking the type | added an `isJsonPrimitive()` check ✅ |
| LOW | `getMotd()` is deprecated, `player.yml` usage omits `stats`, and `ignoreCancelled` was used on a non-cancellable event | corrected/annotated ✅ |

**Self-correction in the protocol simulation test**: while implementing the mock, the subagent found 6 defects on its
own, the most serious being an extra `\n` at the end of the canonical string (which would make **all**
signatures disagree with the PHP/Java side); it was ultimately pinned down by cross-validation against the
official RFC 4231 vectors and an independent ipad/opad implementation.

All the contract-type issues above have been written into Section 13 of `tools/verify.mjs` as **automated
regression protection**, and will not be changed back.

### 2.4 A real CI run —— all three jobs pass ✅

Repository: **https://github.com/StalirMC/mc-flarum-bridge**

| Run | Commit | Result |
|------|------|------|
| #1 | `84b5954` | ❌ failure (exposed 4 real defects, see below) |
| #2 | `b5b7f5d` | ✅ **all pass** —— [run #2](https://github.com/StalirMC/mc-flarum-bridge/actions/runs/34698839560) |

The three jobs of run #2:

| Job | Content | Result |
|-----|------|------|
| Static consistency + protocol conformance | `verify.mjs` (185 items) + `protocol-test.mjs` (32 items), Linux/Node 22 | ✅ |
| PHP lint + extension manifest | `php -l` on all files, `composer validate`, manifest check, **actually requiring the migration files and reflectively validating the up closure signature** | ✅ |
| Build the plugin with Gradle | Temurin JDK 21 + Gradle 8.10 → `gradle build` | ✅ |

Artifact: **the `McBridge-plugin` jar (34.6 KB) was successfully built and uploaded as a workflow artifact**,
with a run time of 45 seconds.

#### Problems caught by the first CI run (undiscoverable on this machine)

This is exactly the point of putting the repository on CI — there is no PHP/JDK locally, and `javac` produced 4 real defects:

| File | Error | Root cause |
|------|------|------|
| `HttpBridgeClient.java:54,62,131,142` | `cannot find symbol` | when adding the `Duration` timeout parameter, **`import java.time.Duration;` was omitted** |
| `HttpBridgeClient.java:120` | `signedBuilder cannot be applied to given types` | after adding a timeout parameter to `signedBuilder`, the `get()` call site still used the old 4-argument form |
| `McBridgePlugin.java:158,355` | `reference to runTaskTimerAsynchronously / runTaskAsynchronously is ambiguous` | the Bukkit scheduler has overloads taking both `Runnable` and `Consumer<BukkitTask>`, and **implicitly typed method references do not participate in applicability determination**, causing ambiguity; an explicit `(Runnable)` cast is required |
| CI's PHP manifest check | `autoload psr-4 mapping ... must point at src/` | in the inline `php -r` the PSR-4 key was written as `"Stalir\\\\McBridge\\\\"`; the YAML block scalar is passed to the shell verbatim, so PHP received two real backslashes and could never match composer.json's single backslash |

All are fixed (commit `b5b7f5d`), and **both classes of Java hazard were written into Section 15 of `tools/verify.mjs`**
(missing-import detection + scheduler method-reference ambiguity detection), so regressions are intercepted without a compiler.

> Appendix: correcting one earlier inaccurate record —— the `already_bound` type check in `BindCommand` was reported
> as "fixed" in the previous round, but was in fact not changed; it is genuinely fixed this time (commit `b5b7f5d`).

### 2.5 Frontend bundle initialisation crash —— the lazy-loaded chunk trap (fixed)

**Symptom**: after installing the extension, the forum frontend reported `stalirmc-mc-bridge failed to initialize /
TypeError: Cannot read properties of undefined (reading 'prototype')`, and the binding code input field could not be
seen at all under "Personal Settings".

**Investigation** (evidence gathered item by item against the Flarum 2.x source in `.tools/flarum-framework`):

| Evidence | Location | Conclusion |
|------|------|------|
| `settings: { path: '/settings', component: () => import('./components/SettingsPage') }` | `framework/core/js/src/forum/routes.ts:39` | the settings page is a **lazily loaded chunk behind a dynamic `import()`**, and is not in the main bundle |
| `flarum.reg.add("core","forum/components/SettingsPage",…)` in the core `dist/forum.js` matches **0** times (that artifact registers 217 modules in total) | core build artifact | a static `import SettingsPage from 'flarum/forum/components/SettingsPage'` is `undefined` at the very moment the bundle is evaluated |
| The string branch of `extend(object: T \| string, …)`: `flarum.reg.onLoad(namespace, id, module => extend(module.prototype, …))` | `framework/core/js/src/common/extend.ts:37-43` | when a **module path string** is passed, `extend` takes `.prototype` itself and waits for the module to become ready |
| The `namespaceAndIdFromPath` regex | `common/ExportRegistry.ts:260` | `'flarum/forum/components/SettingsPage'` → `namespace='core'`, `id='forum/components/SettingsPage'` |
| `onLoad()`: fires **immediately** if already registered, otherwise queues and triggers when `add()` happens | `common/ExportRegistry.ts:109-117` | both orderings are covered; once the chunk finishes loading, the module's own `reg.add` triggers it |
| `expose-loader` is a dependency of `framework/core/js`, and the core `.tsx` only does `import type Mithril` | `framework/core/js/package.json:39` | the global `m` is the official convention, so using `m(...)` directly in a component is correct |

**Fix** (`flarum-extension/js/forum.js`): the settings page is no longer statically imported; instead it is registered by module path:

```js
extend('flarum/forum/components/SettingsPage', 'settingsItems', function (items) {
  items.add('mc-bridge', m(McBridgeSection), 12);
});
```

The artifact now contains `(0,r.extend)("flarum/forum/components/SettingsPage","settingsItems",…)`,
and `SettingsPage.prototype` no longer appears (build artifact 3.3 KB).

**Also confirmed along the way**: the `common/Component`, `common/components/FieldSet`,
`Button` and `LoadingIndicator` used by `McBridgeSection` are **all registered in the main bundle**, so a static
import is safe; only lazily loaded core page components must not be statically imported.

**Regression protection**: Section 16 of `tools/verify.mjs` (8 items) — the entry file must live in the `js`
root directory, statically importing lazily loaded core modules is forbidden (SettingsPage / PostsPage / NotificationsPage /
PostStream / PostStreamScrubber / DiscussionsUserPage / UserSecurityPage), the settings page must be
`extend`ed by module path, the artifact must not contain `SettingsPage.prototype`, and the 9 keys used by the
frontend's `this.t()` must exist in **all** locale files.

### 2.6 Admin panel initialisation crash —— 2.x removed `app.extensionData` (fixed)

**Symptom**: after opening `admin#/extension/stalirmc-mc-bridge`, the console reports
`stalirmc-mc-bridge failed to initialize / TypeError: Cannot read properties of
undefined (reading 'for')`, at `admin.js:8` (that is, `app.extensionData.for(...)`).

**Investigation**:

| Evidence | Location | Conclusion |
|------|------|------|
| `app.extensionData` has **0 matches** in the core source | the whole `framework/core/js/src` tree | 2.x has removed that API entirely |
| The class documentation example is written as `app.registry.for('flarum-tags')` | `admin/utils/AdminRegistry.ts:50` | the new entry point is `app.registry` (`AdminRegistry`) |
| `registry = new AdminRegistry();` is a class property | `admin/AdminApplication.tsx:117` | it already exists when the initialiser runs (not a lazy-loaded chunk, so there is no 2.5-style ordering problem) |
| `app.registry.getSettings(this.extension.id)` | `admin/components/ExtensionPage.tsx:179` | this is exactly where the extension page reads the settings items back |

**Fix** (`flarum-extension/js/admin.js`): `app.extensionData.for(id)` → `app.registry.for(id)`,
with the artifact becoming `n().registry.for(s).registerSetting({…})`.

Along the way the remaining 1.x idioms in the frontend were aligned as well (all written into Section 16 of `tools/verify.mjs`):

| Item | 1.x idiom | Correct 2.x idiom | Basis |
|---|---|---|---|
| Settings section title | `m(FieldSet, …, m('legend', …))` | `m(FieldSet, { label, description })` | `common/components/FieldSet.tsx:19-24`: it renders its own `<label class="FieldSet-label">`, and the root element is a `div` rather than a `fieldset` |
| Alert message | `m('.Alert.Alert--error', m('li', …))` | `m(Alert, { type, content, dismissible: false })` | `common/components/Alert.tsx:10-27` (omitting `dismissible` renders a close button by default) |
| Settings page extension point | `extend(SettingsPage.prototype, …)` | `extend('flarum/forum/components/SettingsPage', …)` | see 2.5: that page is a lazy-loaded chunk |

**Also confirmed item by item to exist** (to avoid another round trip): `SettingsPage.settingsItems()`
(`SettingsPage.tsx:57`, rendered by `content()` via `listItems`), `app.session.csrfToken`
(the core itself sends an `X-CSRF-Token` header with the same name), `app.forum.attribute('apiUrl')` (`ForumResource.php:96`),
`Extend\Settings->default()`, `Flarum\Post\Event\Posted`, the style classes `.Form-group` /
`.FormControl` / `.FieldSet-label`, the `loading` property of `Button`, and
the "value → label" mapping shape of `SelectFieldComponentOptions.options`.

### 2.7 Unified three-platform jar (Paper + Folia + Velocity)

**Goal**: a single jar that can be installed on Paper, Folia and Velocity at the same time.

**Feasibility evidence** (gathered item by item, not guessed):

| Fact | Source | Explanation |
|------|------|------|
| One jar can carry multiple platform descriptors at the same time | the ViaVersion / ViaBackwards / ViaRewind artifacts | `plugin.yml` (Bukkit family) and `velocity-plugin.json` sit together at the jar root, and each platform only loads the entry class in its own descriptor |
| Folia's scheduling API lives in **paper-api** | `jd.papermc.io/paper/1.21.1/io/papermc/paper/threadedregions/scheduler/AsyncScheduler.html` (the title is "paper-api 1.21.1-R0.1-SNAPSHOT API") | therefore **no** extra `folia-api` dependency and no reflection are needed; the signatures of `getAsyncScheduler()`, `getGlobalRegionScheduler()`, `runAtFixedRate`, `execute` and `cancelTasks` have all been checked |
| Folia only loads plugins that declare `folia-supported: true` | the `plugin.yml` contract | double-checked by a build assertion + a CI shell assertion |
| Velocity reads `velocity-plugin.json` from the jar root | Velocity's "Did not find a valid velocity-plugin.json" error mechanism | that file is generated from `@Plugin` by the `velocity-api` annotation processor and is **not written by hand** |
| Velocity and Paper both ship Adventure and Gson | each side's own dependencies | the shared layer only marks these two as `compileOnly`, the jar contains **zero third-party code**, and no Shadow/relocate is needed |

**Structure**: three source sets → one jar.

```
src/main/java       Shared core: BridgeCore (heartbeat/events/announcements/command messages),
                    Platform (platform SPI), BridgeConfig, Yaml, Signature,
                    HttpBridgeClient, EventQueue, Messages, Version
src/paper/java      McBridgePlugin, PaperPlatform (Folia/Bukkit dual scheduler),
                    PlayerListener, BindCommand, BridgeCommand
src/velocity/java   McBridgeVelocityPlugin (@Plugin), VelocityPlatform,
                    VelocityListener, VelocityBindCommand, VelocityBridgeCommand
```

**Key design: the protocol is written only once.** Heartbeat payloads, event structures, outbox handling, announcement
rendering and every command reply are produced by `BridgeCore`; the platform modules only provide the three
things "scheduling + server state + output".
Therefore the requests sent by the three platforms are byte-for-byte identical, and the forum side does not need to
distinguish platforms (the platform name only appears in `/mcbridge stats` and the startup log).

**The shared layer must not reference platform classes**, otherwise the other platform will hit `NoClassDefFoundError`
when loading. This is enforced in three places at once:

1. Gradle `verifyJar`: scans the constant pool of the shared layer's class files; the appearance of `org/bukkit/`,
   `com/velocitypowered/` or `io/papermc/` fails the build
2. Section 17 of `tools/verify.mjs`: source-level import checks (the shared layer must not import platform packages,
   the paper module must not import Velocity, and the velocity module must not import Bukkit)
3. Section 6 of `verify.mjs`: inter-module dependency direction (`main` → may only use `main`; `paper`/`velocity`
   → may depend on `main`)

**Folia scheduling**: at runtime this is determined with `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`;
when regionised, only the Folia scheduler is used (in that case `Bukkit.getScheduler()` throws
`UnsupportedOperationException`), while on ordinary Paper it still goes through `BukkitScheduler`. On Folia, player
messages are delivered to the region thread that owns that player via `Player#getScheduler()`.

**Java version**: the toolchain is JDK 21, but `options.release = 17`. Paper/Folia 1.21 requires Java 21 to
run, and executing 17 bytecode is no problem; whereas Velocity 3.x still supports Java 17, so targeting 21 would
shut some proxy owners out. Section 17 intercepts Java 21-only APIs (`List#getFirst`, `Math#clamp`, etc.)
to prevent regressions.

**Build-time assertions (`verifyJar`, actually executed in CI)**:

| Assertion | Why |
|------|--------|
| Both descriptors + both entry classes + `config.yml` + `lang/*.yml` are present | the most typical failure mode of a universal jar is "it builds but one platform cannot install it" |
| `main:` in `plugin.yml` points at the paper module | stale old class names are easy to leave behind after a refactor (this actually happened) |
| `plugin.yml` declares `folia-supported: true` | otherwise Folia refuses to load it directly |
| The versions of both descriptors equal the project version | the version is easy to drift across three places (`gradle.properties`, `Version.java`, the descriptors) |
| The shared layer's constant pool has no platform references | see above |

**Release**: `.github/workflows/release.yml` is triggered by a tag; it first checks that the tag and the
`gradle.properties` version agree, then builds and uploads **the same universal jar**.

**Still not verified**: the jar **has never been loaded on real Folia or Velocity** (there is no JDK on this
machine, and CI only compiles and runs static assertions). For the first live verification, look especially at: whether the Folia startup log shows `McBridge enabled on
folia` and whether there is an `UnsupportedOperationException`; whether Velocity loads successfully and prints
`on velocity`; and whether the platform line of `/mcbridge stats` on the two sides is correct.

### 2.8 A real local build and the shared-core self-test (new in this round)

After 2.4 above, this machine was confirmed to **actually have a JDK** (`C:\Program Files\Zulu\zulu-25`),
just no Gradle. Gradle 8.10 and Temurin JDK 21 were therefore downloaded into `.tools/`
outside the repository (not committed), and for the first time there was **a genuinely local compilation loop** — no more
guessing at errors through CI round trips.

**This step immediately caught a defect that would have failed CI**:

| Symptom | Root cause | Resolution |
|------|------|------|
| `Could not resolve io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT`: *Dependency resolution is looking for a library compatible with JVM runtime version 17, but … is only compatible with JVM runtime version 21 or newer* | to let the Java 17 Velocity side load it too, I had set the **whole project** to `options.release = 17`; but `paper-api` 1.21.1 is itself Java 21 bytecode, so Gradle's variant resolution rejected it outright on that basis | changed to **per module**: `main`/`velocity`/`test` stay at 17, while `paper` is raised to 21 on its own (Paper/Folia 1.21 has to run on Java 21 anyway, so nothing is lost) |

**Local `gradle build` result** (JDK 21 + Gradle 8.10):

```
> Task :compileJava           shared core
> Task :compilePaperJava      Paper/Folia (release 21)
> Task :compileVelocityJava   Velocity (release 17)
> Task :selfTest              shared core self-test on a real JVM
checks run: 51   failures: 0   SHARED CORE SELF TEST PASSED
> Task :verifyJar
verified McBridge-0.0.1.jar: 36 entries, loadable by Paper, Folia and Velocity
BUILD SUCCESSFUL in 1m 4s
```

**The newly added runtime self-test (`gradle selfTest`, executed by `build`)** covers exactly the code paths that neither the static checks nor the
mock forum can reach — those checks never execute Java:

| Group | Coverage |
|----|----------|
| 1. `config.yml` parsing | nested maps, quoting, comment stripping, non-ASCII values, booleans/integers, block lists (two regex allow-list entries), missing-key fallback, fallback when a scalar is used as a section |
| 2. Language files | the two languages have identical key sets, `prefix`/`log.enabled`/the newly added `stats-platform` are all present, and `\"` escapes are correctly unescaped without breaking quote state |
| 3. Config validation | the factory config is unusable because of the empty secret, the problem message is localised, the `{platform}`/`{key}` interpolation of `log.enabled` leaves no residual placeholders, `apiPath`/`endpoint` concatenation |
| 4. Full config | usability, secret and server identity, interval values, `allow-remote-commands` off by default and the regex allow-list genuinely taking effect once enabled (`say hello` / `broadcast …` pass, `op someone` is rejected), and both an over-short secret and a URL missing its scheme judged unusable |

**Measured artifact** (`jar tf` + reading the descriptors and class headers directly):

```
36 entries / 64 KB, no third-party code at all
plugin.yml            version: '0.0.1'  main: cn.stalir.mcbridge.paper.McBridgePlugin  folia-supported: true
velocity-plugin.json  {"id":"mc-bridge", …, "main":"cn.stalir.mcbridge.velocity.McBridgeVelocityPlugin"}
bytecode major version   BridgeCore = 17   McBridgePlugin = 21   McBridgeVelocityPlugin = 17
```

That last line is exactly the design intent: **mixing class file versions inside one jar is legal**; each class
declares its own version, and the JVM only loads the ones it supports.

**CI**: the three jobs of the same commit `dd28da7`
([run #24](https://github.com/StalirMC/mc-flarum-bridge/actions))
(static+protocol / PHP lint / Gradle compile) all pass, consistent with the local conclusion.

**Still not verified on a live server**: the jar has again **never been loaded on real Paper / Folia / Velocity**.
All the conclusions above stop at the level of "compilation, static assertions, running the shared core on a real JVM" and cannot replace
testing on a real machine.

### 2.9 First live startup, and the three defects it exposed (fixed in 0.0.2)

The user installed 0.0.1 on a real Paper server; the log of that first startup is the first time this code truly ran on a server:

```
[17:13:49 WARN]: [McBridge] Could not save config.yml to plugins\McBridge\config.yml because config.yml already exists.
[17:13:49 WARN]: [McBridge] Could not save zh_CN.yml to plugins\McBridge\lang\zh_CN.yml because zh_CN.yml already exists.
[17:13:49 WARN]: [McBridge] Could not save en.yml to plugins\McBridge\lang\en.yml because en.yml already exists.
```

**This log is both evidence and a defect report**:

- It is evidence: the `[McBridge]` prefix, the `plugins\McBridge\` data directory and the `lang/` subdirectory all appear,
  showing that **the plugin enabled successfully on real Paper**, and that the chain `onEnable → BridgeCore.start() → Messages.load()`
  really executed, with no `NoClassDefFoundError` and no crash. The sentence in Section 2.8 that it had "never been loaded on a real server"
  is void as of now.
- It is a defect: these three warning lines come from **Bukkit itself** — `JavaPlugin#saveResource(path, false)` emits this WARN
  when the target file already exists. In other words, the file "already existing" is the **normal state on every startup**, yet it was reported as a warning.

#### Defect 1: three meaningless warnings on every startup

| | |
|---|---|
| Root cause | `PaperPlatform.saveResource()` calls `plugin.saveResource(path, false)` unconditionally; Bukkit itself emits the WARN when that file already exists and `replace=false` |
| Fix | check whether the target file exists first and return straight away if it does, never calling that Bukkit method at all (`PaperPlatform.java`) |
| Why it was worth fixing | These three lines would permanently pollute the console log and drown out the real warnings; and the intent of "do not overwrite files the user has changed" is already guaranteed by `replace=false`, with no need to express it through a warning |

#### Defect 2: the account was linked, yet the forum kept showing "not linked" (actual user feedback)

This is the issue that genuinely affected functionality in this round; the root cause was **a route that was never wired up**:

| Step | What actually happened |
|------|--------------|
| In-game `/bind` gets a code → entered on the forum | ✅ normal |
| `POST /api/mc-bridge/link` writes to the database | ✅ normal (there really is a record in `mc_bindings`) |
| Forum frontend reads status via `GET /api/mc-bridge/link` | ❌ **that route was never registered**: `LinkStatusController` was written and even `use`d, but the `->get(...)` line was missing |
| The frontend receives Flarum's 404 error document | ❌ the code reads `body.bound` → `undefined` → **renders it as "not linked"** |

The frontend's "if it cannot be read, treat falsy as false" approach turned a 404 into a seemingly normal "not linked" screen ——
the error was swallowed, so it manifested as "linking succeeds but the forum does not display it".

Three fixes:

1. `extend.php` gained `->get('/mc-bridge/link', 'mc-bridge.linkStatus', LinkStatusController::class)`
2. the frontend `refresh()` now first checks the HTTP status and `body.ok === true`, and **raises an error for any response not produced by this extension**
   (displaying the `error` returned by the forum, or otherwise the localised `load_error`), no longer degrading silently
3. a new `tools/verify.mjs` check: **every `/mc-bridge/*` called by the frontend must have a route registered for the matching HTTP method**
   (parsing method+path from the `Extend\Routes('api')` block in `extend.php` and comparing it against the `fetch`/`request` calls in the JS)

Item 3 was **validated in reverse**, confirming that it genuinely catches this class of bug rather than being a permanently green decoration:

```
(GET route temporarily removed)
FAIL frontend endpoints
     the frontend calls GET /mc-bridge/link but extend.php registers no such API route
errors: 1
(after restoring) errors: 0, ALL CHECKS PASSED
```

#### Defect 3: the version number was bumped, but the old version was still packaged into the jar

After raising the version from 0.0.1 to 0.0.2 and rebuilding, `verifyJar` failed outright, and the intermediate artifact
`build/resources/paper/plugin.yml` was still stuck at the previous round's timestamp and `0.0.1`.

| | |
|---|---|
| Root cause | `expand(version: project.version)` is a **task action**, not a task input; Gradle judged `processPaperResources` UP-TO-DATE and did not expand again, so the jar still held the old version number. CI never exposed this because every CI build is clean, whereas repeated local builds do |
| Fix | declare `inputs.property('version', project.version)` on `processPaperResources` |
| Protection | `verifyJar` already asserted that "both descriptors carry the project version", so it caught this mistake —— that assertion is worth keeping; `verify.mjs` gained another check confirming that `inputs.property('version'` is present |

#### Conclusion of this round

`tools/verify.mjs` 303 items, 33 protocol items, the local `gradle build` (including 51 shared-core self-tests)
and `verifyJar` all pass, and version 0.0.2 has been released. **But please note**: Defect 2 was hit by a user in a real environment,
not found by our checks —— which is exactly the meaning of the sentence in Section 2.8, "cannot replace testing on a real machine".

### 2.10 Four integration features (0.0.3)

After fixing the defects in 2.9, four features were implemented as named by the user. All of them were written in a way that can be covered by static checks, because this machine has no Flarum environment and the effect in the browser can only be confirmed manually.

| Feature | Implementation location | Key point |
|---------|-------------------------|-----------|
| The profile page shows the linked MC account | `Api\UserResourceFields.php` + `js/forum.js` (`UserPage.sidebarItems`) | The field hangs off the **user resource**, arriving together with the author data the posts already carry, so there is no need for an extra request per author |
| MC badge next to the author name on posts/replies | same as above + `PostUser.userViewItems` (priority 95, right after the name at 100 and before the group badge at 90) | The same field, so the profile page and the badge cannot disagree |
| Join-time binding prompt | `BridgeCore.promptBindingIfNeeded` + platform `sendToPlayer` + listeners on both sides | Queries are asynchronous, replies go on the main thread (on Folia, that player's region); when the forum is unreachable it **stays silent** and only logs at fine level |
| Forum server status page | `Api\Controller\StatusPageController` (`GET /mc-bridge/status`) + a sidebar entry | Server-rendered, no frontend build needed; it only shows the aggregated data the public status endpoint already has, so guests can see it |

A few deliberate trade-offs:

- **Visibility**: the `visible` callback of the `mcBridge*` fields requires `$context->getActor()->isRegistered()`,
  so a guest's payload does not contain these fields at all (they are not hidden by the frontend — the backend does not send them).
- **Query cost**: the fields are queried user by user (about twenty index queries per page). Not doing "load all links at once" is
  to avoid scanning a table that grows with the number of links, and not doing cross-request caching is to avoid still showing the old value after an unlink. This trade-off is written in
  the class comment of `UserResourceFields`.
- **Triggering of the join prompt**: it is **independent** of the `report-joins` switch — the prompt is shown even when join events
  are not recorded; conversely, `game.prompt-unbound: false` turns it off entirely.
- **The status page link uses a plain `<a>`**: that page is rendered by PHP, and using Mithril's Link would be intercepted by the
  frontend router and would ask for a JS component that does not exist.

#### New static checks (`tools/verify.mjs` section 18)

The new failure mode this round is "a field/key name the frontend reads does not match what PHP declares" — both sides are valid, yet the interface shows nothing.
So a cross-language contract check was added:

| Check | Approach |
|-------|----------|
| user attributes the frontend reads must be declared by PHP | Collect `.attribute('mcBridge*')` from JS and compare it with `Schema\*::make('...')` in `UserResourceFields.php` |
| translation keys the frontend asks for must exist in every locale | Collect `translator.trans('stalirmc-mc-bridge.*')` from JS and compare it with the key sets of the two locale files |

For this, the translation keys in the frontend were changed from template strings to **literals** (`` trans(`${EXTENSION_ID}.…`) `` → `trans('stalirmc-mc-bridge.…')`),
otherwise the key names are invisible to static tooling.

Reverse verification was done as well — deliberately misspelling `mcBridgePlayerName` as `mcBridgePlayerNam`:

```
FAIL frontend attributes
     the frontend reads mcBridgePlayerNam but UserResourceFields does not declare it
errors: 1
(after restoring) errors: 0, ALL CHECKS PASSED
```

#### Verification results for this round

- `tools/verify.mjs` **318 checks**, protocol 33 checks, all passed
- Local `gradle build` (JDK 21 + Gradle 8.10) passed; `verifyJar` reports 36 entries
- Frontend `npm run build` passed (forum.js 4.56 KiB)
- **Still not verified**: the profile page section, the author badge, the status page and the join prompt have **not been looked at once in a real browser/server**.
  Static checks can guarantee "the fields and key names match, the route exists, the versions agree", but they cannot guarantee "it looks right and sits in the right place".

### 2.11 The incident that took the forum down (fixed in 0.0.4)

**Symptom**: after updating to 0.0.3 the whole forum returned 500, and the exception captured in production was:

```
BadMethodCallException: Call to undefined method Flarum\User\Guest::isRegistered()
  .../vendor/stalirmc/mc-flarum-bridge/flarum-extension/src/Api/UserResourceFields.php:30
```

The log **reports both** `Flarum\User\Guest` and `Flarum\User\User`, which means the method does not exist for any actor on the deployed version.

**Root cause**: `isRegistered()` is a helper method the 2.x branch added only later, and **2.0.0-rc.8 does not have it**. When writing this code
I was comparing against the local framework checkout, which comes from the 2.x branch and is **newer than production** — so I used an
API that does not exist in production. Worse, it was written inside the `visible` callback, so **every request that serialises a user hits it**, starting with the front page.

**Fix**: switch to `(int) ($context->getActor()?->id ?? 0) > 0`. Checking against the rc.8 source: a guest is
`Flarum\User\Guest` with `public int $id = 0`, and **`Guest extends User`**, so the intuitive `instanceof User` form does not keep guests
out; a real account's id is always positive. This test holds on any version.

While at it, a related problem that had not blown up yet was fixed: `intdiv()` received a float returned by Carbon 3, and now the value is rounded first.

**Process fix**: section 19 of `tools/verify.mjs` bans `isRegistered()` and **writes the audit baseline version
`2.0.0-rc.8` into the check**, and it also verifies that the `flarum/core` constraint in `composer.json` covers that version.
The lesson encoded in the check is: **writing code against a local copy of the framework does not count as verification — the deployed release is what counts.**

### 2.12 The grass block badge and the status widget embedded in the theme (0.0.5)

The user reported two things: the MC badge next to the author name showed up as a "black block"; and they wanted the server status **embedded into the avocado theme**.
For both, API existence was first **checked with a browser on the real site**, rather than inferred from the local copy:

| Item checked | Result (on production `flarum.reg`) |
|--------------|-------------------------------------|
| `core / common/models/User`, with `badges()` on the prototype | ✅ present |
| `core / forum/components/IndexSidebar`, with `items()` on the prototype | ✅ present |
| `core / forum/components/PostUser#userViewItems`, `UserPage#sidebarItems` | ✅ present |
| `core / forum/components/SettingsPage` | lazy-loaded chunk, not reachable via `reg.get` (as expected, the string form of `extend` waits for it) |
| Extension namespaces | `stalirmc-mc-bridge`, `ramon-avocado` |

**Why the badge was a black block**: `span.Badge` had been added **outside** the `PostUser-badges` list, so the theme's
`PostBadges.less` did not reach it, leaving a dark pill with no icon and no theme styling.
It was changed to hang off **`User.badges()`**: that list is exactly the source of the content of `<ul class="PostUser-badges badges badges--packed">`,
so the badge enters the theme's styling context and **appears at the same time on the post author, the user card and the profile page sidebar**
— which is the correct place for "consistency". The icon is an **inline grass block SVG** drawn as the user requested (no image asset, no CSS build,
cannot 404), and the player name goes in `title`/`aria-label`.

**Status widget**: `IndexSidebar.items()` mounts a self-contained component (`McBridgeStatus`) that reads the public
`/api/mc-bridge/status`, self-refreshes every 60 seconds, and uses only Flarum's CSS variables for styling, so it **does not depend on any theme**.
The basis for choosing this mount point is that avocado's `AllDiscussionsPage` source renders
`<IndexSidebar />` directly (see its `js/src/forum/components/AllDiscussionsPage.tsx`), so the widget appears in
that theme's sidebar; it works just as well with another theme.

**Confirmed working in production** (this round really ran end to end):
`GET /mc-bridge/status` renders normally, with the content `survival` online, 1/20 players, TPS 20.00, MSPT 5.3 ms,
version, MOTD, the online list and recent events (join/start/stop) — **the heartbeat → event reporting → status page chain genuinely works**.

**Still not verified**: the 0.0.5 frontend changes have not been looked at in a browser (it requires `assets:publish` first),
and both the actual look of the grass block and the widget's position in the sidebar need confirmation from the user.

### 2.13 Publishing on Packagist (after 0.0.6)

The user submitted the package to <https://packagist.org/packages/stalirmc/mc-flarum-bridge>. What querying
the Packagist API actually showed:

| Item checked | Result |
|--------------|--------|
| Versions listed | `v0.0.1` … `v0.0.6` all present, `dev-main` pointing at `b94dfbe` (the rename commit) |
| Default branch / type | `main` / `library`, `extra.flarum-subextensions: ["flarum-extension"]` correct |
| **Highest stable version** | **`v0.0.6`** ← so `composer require stalirmc/mc-flarum-bridge` installs the current version |

The last row was a risk in itself: the repository contained a **leftover temporary `v1.0.0` tag** (pointing at a very early commit), and once it was pushed to the
remote, Packagist would consider 1.0.0 the highest version, so an unconstrained `composer require` would install **code from months ago**.
Checking `git ls-remote --tags origin` confirmed it was **never pushed to the remote** (Packagist only reads remote tags),
and the local tag was then deleted as well, to avoid pushing it by mistake later.

The documentation was changed accordingly to install directly from Packagist: `composer config repositories... vcs` is no longer needed,
and installing from the admin panel only requires entering `stalirmc/mc-flarum-bridge` in 「安装一个新的扩展程序」 ("Install a new extension");
the `vcs` route is kept as the fallback for "installing a version that has not been released yet". At the same time a typo in the documentation that predates the rename was fixed —
`php flarum extension:enable` takes the **extension ID** (`stalirmc-mc-bridge`), not the package name.

**Observed synchronisation behaviour**: fetching the Packagist API again a few minutes after the submission, `dev-main` was still
at `b94dfbe` and the fetch time was still the moment the package was submitted, which means **nothing is currently triggering a fetch automatically**.

Calling Packagist's update API from the release workflow (triggered with two secrets) was tried at some point and **has been removed at the maintainers' request**:
the release process stays simple, and the sync is left to that one click on the Packagist page; anyone who wants to skip that step should configure a webhook on the Packagist side.

This kind of **publisher operations information** was then moved out of the deployment guide aimed at installers and consolidated into
[`RELEASING.md`](RELEASING.md) (release process, maintainers): the deployment guide covers only what installers have to do
(`composer require` / `composer update` / `assets:publish`).

### 2.14 Removing two features and fixing two display problems in the admin panel (0.0.7)

The maintainers asked for two features to be removed, on the grounds that "remote commands are unnecessary" and "the status page does not work in the avocado theme". Both were
**deleted as whole sets**, leaving no half-finished implementation behind:

| Removed item | Locations involved |
|--------------|--------------------|
| Remote commands | `BridgeConfig` (switch, allow-list, validation, `isRemoteCommandAllowed`), `BridgeCore.handleRemoteCommand` and the outbox's `command` branch, `Platform.dispatchConsoleCommand` and its implementation on both sides, two keys in `config.yml`, the log keys and allow-list validation keys in both language files, 14 assertions in `SelfTest`, README/plugin documentation/API documentation |
| Status page + sidebar widget | `StatusPageController` (whole file), the forum route and `use` in `extend.php`, `McBridgeStatus.js` (whole file), the `IndexSidebar.items` extension in `forum.js`, the `forum.status` and `page.status` key groups in both locales, the entry point and explanation in the documentation |

**Kept** is the public read-only endpoint `GET /api/mc-bridge/status`: the in-game `/mcbridge status` still uses it,
so the status data path between the plugin and the forum is not broken — there is simply no longer a display page on the forum side.

After the change the self-test dropped from 51 to 40 checks (the remote command assertions were deleted along with the feature), `verify.mjs` dropped from 320 to 311 checks
(one allow-list key and its related assertions were removed), and everything reported 0 errors. During the removal verify.mjs raised one **unused language key**
warning (`config.problem.whitelist` was no longer referenced by any code), which was then deleted as well.

#### The two display problems in the admin panel (from the maintainers' screenshot)

The admin panel's extension page showed `版本 0.0` ("Version 0.0"), and the link on the author name "Stalir" pointed at `/admin`. Both arise when Flarum reads
**the sub-extension's own `composer.json`**; source evidence:

| Symptom | Code evidence | Fix |
|---------|---------------|-----|
| Version shows `0.0` | `ExtensionManager::extensionFromJson()` → `Arr::get($package, 'version', '0.0')`; `subExtensionConfsFromJson()` reads `flarum-extension/composer.json`, which had no `version` at the time | add `"version": "0.0.7"` to the sub-package |
| Author link goes back to `/admin` | `Extension::getLinks()` → `authors[].link = homepage ?? (email ? mailto: : '')`; an empty string is resolved by the browser to the current page | add `homepage` to every author |

Both were added to section 17 of `verify.mjs`: **the sub-package's `version` must match `gradle.properties`** (otherwise
the admin panel shows a number that does not match the released version), and **every author must carry `homepage` or `email`** (otherwise it is another
dead link that only refreshes `/admin`). `RELEASING.md` records the same thing: the version number is now consistent in **four places**, and all of them must be changed on an upgrade.

### 2.15 Removing server status reporting and game event reporting (unreleased)

The maintainers asked for the two features "server status interop" and "game event reporting" to be removed. After the removal the plugin does only four things: fetch forum
announcements and broadcast them, submit broadcasts to the forum, link accounts, and prompt unbound players on join — **it no longer pushes any data to the forum**.

| Removed item | Main locations |
|--------------|----------------|
| Server status (heartbeat) | `BridgeCore`'s heartbeat construction/sending/scheduling and the shutdown heartbeat, `statusMessage`, `HttpBridgeClient.heartbeat`/`fetchStatus`, `Platform`'s seven server status getters and its implementation on both platforms (including Paper's `getTPS`/`getAverageTickTime` reflection), `HeartbeatController`, the public `/api/mc-bridge/status`, `StatusController`, `heartbeat-interval-seconds` in `config.yml` |
| Game event reporting | `EventQueue` (whole file), `BridgeCore.enqueue*`/`flushEvents`, `EventController`, Paper's quit/death/advancement listeners, Velocity's disconnect listener, `event-flush-interval-seconds` / `max-queued-events` / `report-joins\|quits\|deaths\|advancements` in `config.yml` |

**Kept** are the two tables `mc_servers` / `mc_events` and their models: the database is not touched and no historical data is lost, they are simply no longer written to
(the database table section of `docs/API.md` already notes this). The `/mcbridge status` subcommand was removed together with the feature;
`outbox` / `broadcast` / `stats` / `reload` are kept.

Scale: Java 14 files, PHP 3 controllers, mock and protocol tests (33 → 28 checks), `verify.mjs` (315 → 284 checks),
in-JVM self-test 40 → 37 checks (among them the lower-bound assertion on the "number of language keys" was relaxed from 50 to 40 at the same time: it is a guard rail against parsing
failures, not an exact count). `gradle build`, `verify.mjs` and `protocol-test.mjs` all pass.

> There is **no release** this time: at the maintainers' request, the version number is no longer bumped for every change, and changes go only into `main`.

### 2.16 Badge display and attribute translation (0.0.8 – 0.0.10)

Three display problems, all located after the maintainers found them on the real forum; every conclusion was written into the code comments and `verify.mjs`:

1. **Duplicated badge text** (0.0.9): the avocado theme's `less/forum/PostBadges.less` uses
   `.PostUser-badges .Badge::after { content: attr(aria-label); }` to treat `aria-label` as the visible label on the pill,
   and its source comment states explicitly that this applies to **any** badge following the core convention. The previous version both put
   visible text in a child element and put 「Minecraft 账号：xxx」 ("Minecraft account: xxx") into `aria-label`, so both pieces were drawn together.
   The correct answer is the core convention itself: **`aria-label` holds the label, and child elements hold only the icon**.
2. **An extra comma in the tooltip** (0.0.10): `app.translator.trans()` returns a vnode (a multi-segment message is an array),
   and handing it directly to an HTML attribute makes `String()` join it with **commas**, so 「Minecraft 账号：{name}」 ("Minecraft account: {name}") becomes
   「Minecraft 账号：,名字」 ("Minecraft account:,name"). Flarum's own solution is `extractText()` (core uses it to generate the admin panel dropdown options),
   which concatenates with empty strings. Every translation in an attribute position goes through it: 2 in the badge, the binding code input, the FieldSet label, and 3 admin panel settings.
   `verify.mjs` gained a check: an attribute value that starts with a translation call and is not wrapped in `extractText()` fails (reverse verification was done).
3. **The admin panel shows version `0.0` and the author link jumps to `/admin`** (0.0.7): the former because Flarum reads the `version` of **the sub-extension's own**
   `composer.json` and falls back to a hard-coded `0.0` when it is missing; the latter because
   `authors[].link = homepage ?? email ?? ''`, where an empty string is resolved by the browser to the current page. Both fields were filled in and
   brought under the mandatory check in section 17 of `verify.mjs`.

0.0.10 was withdrawn and re-released once: the maintainers asked for the author name to be changed to `StalirMC`, and since Packagist had only picked up `v0.0.6` at that point,
the withdrawal was clean — the tag was deleted and recreated on the new commit, and the Release was updated in place (the assets were replaced and actually tested).

### 2.17 In-game announcement lookup and reporting (0.0.12)

Two new features, both initiated by game-side commands:

| Feature | In-game entry point | Forum side | New table |
|------|-----------|--------|------|
| Announcement lookup | `/mcbridge news` (**no permission required**) | Reuses `GET /outbox`; the plugin filters for `announcement` only and takes the first 5 | — |
| Report | `/report <player> <reason>` | `POST /api/mc-bridge/report` | `mc_reports` |

Design points:

1. **Announcement lookup reuses the existing fetch**: `outboxMessages()` is the raw list admins see (peek, no consumption), while `newsMessages()` keeps only the `announcement` type on top of it, capped at 5, for ordinary players to view.
   In `plugin.yml`, the `/mcbridge` command as a whole carries no permission node, pushing the decision down to the subcommands,
   so `BridgeCommand` can let `news` through **before** checking `mcbridge.admin` — otherwise ordinary players
   would never reach that branch at all because of the parent command's permission node. Tab completion likewise returns different lists based on the player's permissions.
2. **Reporting is write-only, never read back**: `POST /api/mc-bridge/report` requires `reporter_uuid` to be a valid UUID and
   `target_name` and `reason` to be non-empty, and stores into `mc_reports` (initial state `pending`);
   the player only receives a single confirmation line; the handling status (`reviewed` / `dismissed`) is changed by an administrator in the database.
3. **Incremental migration**: `mc_reports` and the two `target_uuid` / `actor_id` columns of `mc_outbox` were written into both the
   creation migration and `2025_06_28_000000_add_bridge_features.php`. **A forum that already has an older version installed must run
   `php flarum migrate` once before the table is created and the columns are added** — on an old forum the creation migration is in the "already executed" state and will not run again,
   which is precisely why that incremental migration exists (every step has a `hasTable` / `hasColumn` guard, so for a freshly installed forum running it is a no-op).
4. The `X-MC-Diagnostic` header proved its worth during integration: this machine lacks PHP, but when comparing the canonical string with the live forum,
   the `canonical` echoed back by the server was **byte-for-byte identical** to the one constructed locally, which confirmed that the "signature mismatch" was due to different secrets,
   not to a wrong algorithm or path.

> **The voting feature was implemented over two rounds and finally removed entirely.** The first round was a self-built poll
> (`mc_activities` / `mc_votes` two tables plus `ActivityController` / `VoteController`);
> the maintainers pointed out that what they wanted was integration with the forum's existing `fof/polls`; the second round changed to integration
> (reading `polls` / `poll_options`, borrowing the `MultipleVotesPoll` command to record votes under the linked account's name),
> after which the maintainers decided they did not want a voting feature. All traces of both rounds have been cleared: the tables, the controllers, the `/vote` command and its permissions,
> the `poll-*` / `vote-*` language keys, and the corresponding groups in the mock and protocol tests are all gone from the code.
> This record is kept in order to make one thing clear: **the two `target_uuid` / `actor_id` columns of `mc_outbox` and the
> `mc_reports` table have nothing to do with voting**, and the incremental migration must still be run.

Scale: mock and protocol tests 28 → **32 items** (4 for reports), `verify.mjs` **301 items**,
in-JVM self-test 37 items. `gradle build`, `verify.mjs` and `protocol-test.mjs` all pass.

**Live verification (forum.kxkl2024.cn)**: after exchanging a Flarum token with the `DeepSeekHarness` account, it was confirmed that
`GET /api/mc-bridge/link` returns normally, while the newly added `/api/mc-bridge/polls` at that time returned `404`
— i.e. the forum side is still an older version, and these two features need to be deployed first (see section 4).

### 2.18 Fatal closure-scope error in the incremental migration (0.0.13)

**This is a regression from 0.0.12, found by the user on a real forum**, and it is the most notable entry of this round.

| Item | Content |
|------|------|
| Symptom | In-game `/report` receives `HTTP 500 ({"errors":[{"status":"500","code":"db-error"}]})` |
| First reaction (wrong) | Misjudged as "the forum-side extension was not upgraded / the migration was not run" — in reality the extension was already the new version, and **it was precisely the new version's own migration** that crashed `php flarum migrate` |
| Real root cause | `2025_06_28_000000_add_bridge_features.php` wrote the column check **inside** the Blueprint callback: `$schema->table('mc_outbox', function (Blueprint $table) { if (! $schema->hasColumn(...)) ... })`. **A PHP closure does not inherit the outer scope**; inside the callback `$schema` is an undefined variable (`null`), hence the fatal error `Call to a member function hasColumn() on null` |
| Chain of consequences | The migration fataled mid-way every time; Flarum's `Migrator` calls `log()` only after success, so it is never marked as executed → every `migrate` crashes, `mc_reports` can never be created → `/report` is forever 500. The self-test likewise faithfully reported "missing: mc_reports" |
| Blast radius | Once `mc_outbox` exists, `$schema->table(...)` is called and the callback runs immediately, so **both freshly installed and upgraded forums crash**, not only old forums |
| Fix | The column check was moved to the outer closure and the result passed into the callback as a boolean: `function (Blueprint $table) use ($addTargetUuid, $addActorId)`; `down` is handled the same way. A side benefit is that the schema is no longer queried again while the Blueprint is being built |
| Why it was not caught | Sections 11/13 of `verify.mjs` **only read `walk(...)[0]`, i.e. the first migration file** (the creation migration); the incremental migration was never statically checked. Moreover, the old check only looked at `$this->schema` / `extends Migration` and **never entered a closure body**. All 299 items were green, yet a migration guaranteed to crash was missed |

**Guards (added this round, and verified to work)**:

1. Sections 11 and 13 now iterate over **all** migration files, no longer only the first.
2. A new **closure-scope check**: it parses each closure's (including nested ones) parameter list, `use (...)` clause and brace-balanced function body;
   if the body references `$schema` and it is neither a parameter of that closure nor captured by `use`, it is judged a runtime error guaranteed to crash.
   PHP comments are stripped before the check, so braces inside comments do not disturb the balance.
3. A new **migration drift check**: when the same table is created by multiple migrations, the column definitions must be exactly identical — otherwise a freshly installed
   forum and an upgraded forum end up with two different schemas, and only one of them was ever tested.
4. **Regression self-proof**: pasting that 0.0.12 bug back into the file verbatim makes `verify.mjs` immediately `FAILED` (1 error,
   naming `2025_06_28_000000_add_bridge_features.php`); after reverting, all 298 items are green again.
   A guard that cannot catch the bug is no guard at all.

**Lesson**: this kind of error is completely legal at compile time and invisible to the existing static checks; only a real `php flarum migrate` blows up;
and it blows up for any installation that executes that migration. **"There is no local PHP environment" made this step the user's burden for a long time**,
which is also why the `php -l` + migration-closure-reflection step in CI is worth keeping.

### 2.19 Two class references "used but not imported" (0.0.14)

**Two sides of the same blind spot as 2.18**, likewise found by the user online.

| Item | Content |
|------|------|
| Symptom | The forum's "Settings → Minecraft account" shows 「无法读取绑定状态。」 ("Unable to read link status."); the binding code obtained by in-game `/bind` does nothing when submitted on the forum |
| Log | 9 occurrences of `Class "Stalir\McBridge\Api\Controller\McOutboxMessage" not found`, at `LinkController.php:109` and `:138` |
| Root cause A | `link()` / `unlink()` in `LinkController` call `new McOutboxMessage()`, but there is **no `use Stalir\McBridge\Model\McOutboxMessage;`**. PHP resolves against the current namespace, so it looks for `Stalir\McBridge\Api\Controller\McOutboxMessage`, which of course does not exist — the link transaction throws at the last step and the whole POST/DELETE becomes a 500 |
| Root cause B | The same pattern also hid in `ConfigCommand`: it uses `BridgeMessages::isAvailable()` / `SETTING_KEY` / `resolveLocale()` yet likewise has no import — meaning `php flarum mc-bridge:config` would crash just the same, only nobody ran it that round |
| Why it was not caught | Section 3 of `verify.mjs`, "PHP imports resolve", only checks the **forward** direction: the class each `use` points at must exist. It never checks the **reverse** — whether the classes being used are imported. All 299 items were green, and neither guaranteed-to-crash call was ever looked at |
| Fix | Added the two `use` lines; additionally `LinkStatusController` now returns the extension's own error structure (`link_login_required`) when the user is not signed in, instead of Flarum's JSON:API envelope — the latter can only surface in the front end as the unhelpful line 「无法读取绑定状态」 ("Unable to read link status") |

**Guards (added this round, and verified to work)**

A new **reverse import check**: for each file under `src/` it parses the namespace and the `use` alias table (including `use X as Y`), then collects every reference form that resolves a class name against the current namespace —
`new X(`, `X::`, `extends`, `implements`, `instanceof`, `catch (X`, type declarations (`Foo $bar`, including `?Foo`) and return types (`): Foo`).
If a name is a class defined by this project but is neither imported nor in the same namespace as the current file, it is judged an error.
Only **this project's own class names** are judged, so PHP built-ins and vendor classes are never false positives; fully qualified names starting with `\` are excluded by a negative lookbehind.

Right after being added it caught the two spots in `LinkController` and `ConfigCommand`; after the fix all 301 items are green, and expanding the reference forms from 3 to 8 did not increase the failure count (no new false positives).

**Front-end diagnosability fixed along the way**: `McBridgeSection` previously displayed only the single `load_error` message when the response was abnormal.
Now it first prefers the extension's own `error` field, then Flarum JSON:API's `errors[0].detail` / `code`, and appends the HTTP status code when neither is present, and `console.warn`s the raw response —
otherwise "session expired" and "server 500" look exactly the same in the UI, which is precisely what made this troubleshooting stall.

**Lesson**: the two most expensive bugs in `verify.mjs` (the closure scope in 2.18, the missing imports in 2.19) are not cases of "the check was written wrong" but of **checks that only work in one direction** —
looking only at "can declarations be resolved", never at "can uses be resolved". A static check must either be bidirectional or it will systematically miss an entire class of errors.

Scale: `verify.mjs` **301 items**, protocol 32 items, in-JVM self-test 37 items, all pass.

### 2.20 Reports automatically post a discussion with the report tag (0.0.15)

**This was a requirement raised by the user online**: reports were only written into `mc_reports`, nothing was visible in the forum UI, and administrators had to go and query the database.

| Item | Content |
|------|------|
| Requirement | After a report, automatically post a discussion on the forum with the report tag attached |
| Before | `POST /api/mc-bridge/report` only did `McReport::save()`, with zero visibility on the forum side |
| Live facts | That forum **already has** a report tag (id 4, slug `reports`, a primary tag), plus workflow tags such as 「待处理」14 /「已完成」13 /「拒绝处理」19 ("pending" 14 / "completed" 13 / "refuse to handle" 19) — so there is no need to create a new tag; recognising the existing ones is enough |
| Implementation | New `Service\ReportDiscussion`: resolves the posting account and tags → uses `Flarum\Api\JsonApi`'s `forResource(DiscussionResource::class)->forEndpoint('create')->process([...], [], ['actor' => $actor])` to create the discussion. It goes through the framework's own JSON:API pipeline rather than hand-writing rows into the three tables `discussions` / `posts` / `discussion_tag`, so the first post, tag association, reply count and author read state are all left to the framework |
| Posting account | Defaults to the earliest administrator (can be specified with `--report-actor=<user ID>`). It **cannot** be the reporter's own account: an ordinary member may not have permission to post under that tag, and it would expose the reporter's identity |
| Tag | Settings → slug `reports` → name `举报` ("report") → (when `flarum/tags` is installed but there is not a single tag) a secondary tag is created automatically. `--report-tag=<tag ID>` can specify it |
| Self-configuration | The account and tag resolved on first use are **written back to settings**, so `--show` displays the values actually in effect |
| Best effort | A failed discussion creation is only logged; `/report` still returns 201 with `discussion_id` as `null` — the report is already in the database, and a forum-side problem should not make the player see a 500 |

**The two new guards**

1. **Reports do not enter the game**: `QueueAnnouncement` skips report discussions. Without this, a report would be treated as an ordinary new discussion and
   broadcast to all online players, which amounts to directly exposing the reporter's identity. The judgement is based on the **report tag** (`ReportDiscussion`
   writes the resolved tag ids back to settings before creating, so there is always something to match here); only when the report
   tag is **not configured at all** (in practice, `flarum/tags` is not installed) does it fall back to the "the author is the report posting account" case.
   See 2.23 — putting the author judgement unconditionally first once caused normal announcements sent by that account to be silently dropped.
2. **Setting keys must be declared**: new `verify.mjs` section 4b. Flarum does not error on undeclared `mc-bridge.*` keys;
   `get()` merely returns the default supplied by the caller — which is exactly why mistyping `mc-bridge.report_tag_id` as
   `mc-bridge.report_tagid` makes the entire feature fail silently with no hint at all. The check collects **all** keys handed to the settings
   repository (both string literals and class-constant forms such as `BridgeMessages::SETTING_KEY`),
   requiring them to have a `->default()` in `extend.php`.
   - The first implementation produced 11 false positives: the regex also treated **route names** like `mc-bridge.outbox` as setting keys. Narrowing it to
     "count only keys that appear in `settings->get` / `settings->set` calls" brought it to zero — route names and setting keys look
     exactly alike, and this is already the second time this repository has been bitten by "same name, different thing".
   - Regression self-proof: changing `report_tag_id` to `report_tagid` makes the check immediately report 1 FAIL; after reverting, all 315 items are green.

**Contract sync**: `mock-flarum.mjs` gains `discussions` and `fileReportDiscussion()`, and `/report` now returns
`discussion_id`; group 11 of the protocol tests has 4 more assertions (the discussion was created, it points back to the original report, it is under the report tag).

Scale: `verify.mjs` **315 items**, protocol 32 items, in-JVM self-test 37 items, all pass.

### 2.21 Report discussion title / tags / posting account configurable + PlaceholderAPI (0.0.16)

**A requirement the user added after 2.20 went live**: these three things should be customisable, ideally with PlaceholderAPI support;
then "tags should support multiple selection" was added, so tags changed from a single value to a list.

| Item | Content |
|------|------|
| Technical constraint | PlaceholderAPI is a Bukkit/Paper API and **is not reachable from the forum side (PHP)**. So the title must be rendered in-game and then sent to the forum with the report; the protocol gains an optional `title` field |
| Configuration location | All three go into the plugin's `config.yml` under the `report:` section: `title-format` / `tags` / `actor`, sent to the forum with the request; the forum-side settings are the fallback. **A non-empty plugin configuration takes precedence** |
| Title placeholders | Two kinds: the plugin's own `{target} {reporter} {reason} {server}`, and PlaceholderAPI's `%...%` (expanded only if installed, otherwise kept as-is) |
| Multiple tags | `tags` takes a comma-separated list, each item being a slug or a tag ID; the protocol sends an **array**, and the forum resolves each one and attaches all that resolve |
| Platform boundary | PAPI references are isolated in `paper/PlaceholderApiHook` (the only class in the whole jar that mentions PAPI). Servers without PAPI will never load it, so there is no `NoClassDefFoundError`; Velocity is not involved at all. `verifyJar`'s assertion that "the shared core must not reference platform classes" still passes |
| Dependency | `paperCompileOnly me.clip:placeholderapi` (new extendedclip repository). compileOnly: not in the jar, not required at runtime |
| Threads | `%...%` in the title is expanded on the **main thread** as the reporter (PAPI is not guaranteed to be thread-safe); `{tokens}` is filled in on the async thread together with the report |

**Protocol compatibility**: all three fields are optional. An old forum receiving the fields from a new plugin ignores them (the controller reads only the keys it knows);
a new forum encountering an old plugin (without these fields) uses its own settings. The reverse holds as well.

**Two places that must be changed together**

1. **Writing back settings**: the resolved account and tags are written back to the forum settings. This is not just to make `--show` look nice —
   the announcement guard from 2.20 relies on exactly this to recognise report discussions; if it existed only within a single request, reports would be broadcast into the game.
2. **The guard under multiple tags**: `QueueAnnouncement` now matches against a **list** of ids (`whereIn`) and skips if any one hits.

**The practical constraint of multiple tags**: `flarum/tags` limits how many primary/secondary tags a discussion can carry. Exceeding it makes creation throw,
and the exception is caught by the fallback and written to the log — this is **known and expected** behaviour: better that the report is in the database but the discussion was not created (with a log left behind)
than that the player's `/report` turns into a 500.

**Contract sync**: the mock supports the three hints `title` / `tags` / `actor` and simulates resolution (slug→id, username→id);
the protocol tests add 2 groups (hints are respected, invalid hints do not affect the report), 32 → **34 items**.

Scale: `verify.mjs` **321 items**, protocol **34 items**, in-JVM self-test **45 items** (37 → 45, new configuration-parsing assertions),
all pass.

### 2.22 Report times out but was actually submitted: idempotent retry (0.0.17)

**User feedback after 2.21 went live**: in-game it occasionally shows `无法连接论坛：Cannot reach the forum: Request timed out`
("Unable to connect to the forum: Cannot reach the forum: Request timed out"),
**yet the post did appear on the forum normally**.

| Item | Content |
|------|------|
| Symptom | `/report` reports a connection timeout, yet the discussion was already created on the forum side |
| Meaning | The client timed out, the server finished — the conclusion the client got is **wrong** |
| Real harm | It is not just ugly wording: a player who thinks it failed will **report again**, so one report becomes two records + two discussions |
| Why it only appeared now | After 2.21, `/report` is no longer just "insert one row"; it also synchronously creates a discussion (first post, tag association, events, Markdown parsing). The client budget is only `forum.request-timeout-seconds` (default **10 seconds**), and once this work is combined with infrastructure jitter (PHP-FPM queuing, CDN) it exceeds that |
| Client fix | See below: retry only when the "result is unknown", carrying an idempotency id |
| Another suspicious point | The plugin uses Java's `HttpClient`, which by default negotiates **HTTP/2** via ALPN. When the connection is closed by a proxy/CDN while a request is in flight, the request hangs until timeout — which shows up exactly as "intermittent, and the server actually processed it". It has been pinned to **HTTP/1.1**: this bridge only makes the occasional small request, so multiplexing buys nothing |

**The key to the fix: distinguishing "result unknown" from "result determined"**

`BridgeException.statusCode()` distinguishes exactly these two: `-1` means a transport-layer failure (cannot connect / timeout / unparsable response),
`>= 0` means the server has replied. Hence:

1. A server reply (even a 500) → the result is determined, **no retry** (a retry would only repeat the same error).
2. No response at all → the result is unknown; retry once with the **same `report_uid`**.
3. Neither attempt responds → the wording becomes 「&e论坛没有及时回应，举报&f可能已经提交&e。&7为避免重复，请先不要再次举报」
   ("&eThe forum did not respond in time; the report &fmay have already been submitted&e. &7To avoid duplicates, please do not report again for now") —
   a true statement, rather than a "failure" that invites duplicate reports.

**Forum-side idempotency**: `report_uid` is claimed in the Flarum cache for 600 seconds (consistent with the signature nonce window,
i.e. the time window in which a retry can arrive):

- The claim happens **after the insert and before creating the discussion**, so that a retry arriving exactly while the discussion is being created sees the claim record and returns directly,
  **without** inserting another one; otherwise the two requests would each create a discussion.
- A retry returns `{"ok":true,"report_id":…,"discussion_id":…,"duplicate":true}` — `ok` means success, and
  `duplicate` is merely extra information for the caller.
- Cache rather than a new column: no migration is introduced, and no index has to be added to `mc_reports`; the cost is that if the cache is cleared, an extreme case may insert a duplicate,
  which is a better trade than adding a column every time.

**Contract sync**: the mock uses a `reportUids` Map to simulate the same behaviour (a duplicate uid returns `duplicate` without adding a new record);
the protocol tests add 1 group (retrying with the same uid inserts only once), 34 → **35 items**.

Scale: `verify.mjs` **321 items**, protocol **35 items**, in-JVM self-test **45 items**, all pass.

### 2.23 Report guard false positive: announcements sent by an administrator are silently dropped (0.0.18)

**User feedback**: 「只有携带这些标签的讨论才会推送到游戏，这个只有评论会推送。」 ("Only discussions carrying these tags get pushed to the game; this one only pushes comments.")

**A hypothesis disproved during the investigation (this part is worth keeping)**

The first reaction was "the tags are not yet written when `Posted` fires" — after all, tags are synchronised deferred via `afterSave`.
After tracing the 2.x call chain through the framework source, this hypothesis **was disproved**:

```
Create::setUp
 ├ setValues        → the set() for the tags field runs here, registering afterSave(sync)
 ├ createAction
 │   ├ saveModel()  → $model->save() fires saved ⇒ releases afterSave ⇒ the tags are already in the database at this point
 │   └ creates the first post → the Posted event (the tags are already in the database by now)
 └ saveFields       → AbstractDatabaseResource::saveValue syncs ToMany once more (idempotent)
```

Two pieces of key source: `json-api-server`'s `SetsValue::setValue()` prefers the field's own setter
(so the tags' `set()` runs at the `setValues` stage rather than waiting for `saveFields`), and
`Flarum\Database\AbstractModel::boot()` releases `afterSaveCallbacks` on `saved`.
Therefore when `Posted` fires, the tags are **definitely** already present.

**The real bug**: `isModerationDiscussion()` puts the author judgement first and applies it unconditionally:

```php
if (in_array($authorId, $this->settingIds(ReportDiscussion::ACTOR_SETTING), true)) {
    return true;   // ← skips "all" posts sent by this account
}
```

And the default report posting account is the **earliest administrator** — usually the very account that posts announcements. So every new discussion sent by that account
was treated as a report and skipped, never reaching the game; other players' replies (the discussion already carried the tag and the author was not the report account) got through as usual
— exactly "only comments get pushed".

**The fix**: the tag judgement is the reliable signal (it was proved above to be available at `Posted`), and the author judgement is demoted to
a **fallback only when no report tag is configured** — which only happens when `flarum/tags` is not installed and there is simply no tag to match.

**Lesson**: "adding one more independent fallback is safer" is wrong in this kind of judgement: the fallback's **false-positive surface** (everything that account says)
is far larger than the edge case it was meant to guard against (a tagless forum). Worse, the comment I wrote for it explaining the ordering **was itself wrong** —
a comment based on faulty inference that also calls itself "load-bearing" ossifies the error, which is why nobody questioned it again when 2.22 fixed the timeout.

Scale: `verify.mjs` **321 items**, protocol **35 items**, in-JVM self-test **45 items**, all pass.

### 2.24 Removing Velocity, adding NeoForge 1.21.1 support (0.0.19)

> The subsections after this one — 2.7 / 2.8 and so on — record the verification results of the
> then-current "one jar, three platforms" approach and are historical; Velocity support was removed
> per this section, so the descriptions such as `velocity-plugin.json` and `VelocityPlatform` in
> those subsections **no longer reflect the current state**.

**Goal**: remove Velocity support and support NeoForge 1.21.1 instead.

**Key finding (which determined the whole approach)**: the official library list of Minecraft 1.21.1
contains **no Adventure** (only gson 2.10.1, guava 32.1.2-jre, brigadier 1.3.10), and the POM of
NeoForge 21.1.100 contains no `net.kyori` either. The original "one jar, three platforms" only held
because Paper and Velocity **both bundle** Adventure; NeoForge does not, so the shared core hits
`NoClassDefFoundError` on NeoForge as soon as it touches `net.kyori.adventure.text.Component`.

**Approach**: do not bundle Adventure into the jar (that would clash with the version Paper bundles
and would also break the "no third-party code inside the jar" invariant); instead, **make the core
stop depending on Adventure**:

| Layer | Change |
|----|------|
| `main` | added `Message` (immutable, holding the `&`-code strings the lang files already used). `Messages.legacy/render/prefixed` now return `Message`; `plain()` now strips colour codes directly; the three output methods of `Platform` now take `Message`; the 14 occurrences of `Component` in `BridgeCore` became `Message` |
| `paper` | added `AdventureMessages` (`Message` → Adventure `Component`, using `legacyAmpersand()`), the only conversion point in the paper module; the three commands now call `AdventureMessages.send(sender, …)` |
| `neoforge` | added `NeoForgeMessages` (`Message` → `net.minecraft.network.chat.Component`, `&x` → `§x`; only real colour codes are replaced, so an `&` in a URL inside an announcement body is not mangled) |

The change surface is small: only 3 files in the core, 5 `net.kyori` references, 22 mentions of `Component`.

**Why NeoForge is a separate Gradle project**: ModDevGradle must own the Minecraft dependency of the
project it lives in. Once it is placed in the `mc-plugin/neoforge/` subproject, `main` cannot see
Minecraft at all — this is the key to upgrading "the core has zero platform references" from
"guaranteed by assertions" to "guaranteed by the compile classpath". The subproject compiles the root
project's `src/main/java` + `src/main/resources` directly (the core source exists only once in the
repository), and each of the two jars contains its own copy of the core classes, with no dependency
between them.

**Verified**:

| Item | Evidence |
|------|------|
| NeoForge toolchain usable on this machine | ModDevGradle 2.0.147 pulls NeoForge 21.1.100 + Minecraft 1.21.1, and NeoForm runs through merge → rename → decompile(106s) → inject → patch → applyNeoForgePatches → transformSources → recompile (5364 source files) → compiledWithNeoForge, taking 320s in total, after which `:neoforge:compileJava` is **BUILD SUCCESSFUL** (8m04s) |
| Mod source compiles | 7 classes (`@Mod` entry point + event bus, `Platform` implementation, message conversion, SLF4J adapter, three Brigadier commands) compile successfully against a real Minecraft/NeoForge classpath |
| Mod jar contents correct | `McBridge-neoforge-0.0.18.jar` has 30 entries and `verifyJar` passes: `META-INF/neoforge.mods.toml` declares `modId="mc_bridge"`, `loaderVersion="[4,)"`, the NeoForge and Minecraft versions and `side="SERVER"`, and contains no `plugin.yml` |
| Plugin jar no longer contains Velocity | 31 entries; both `verifyJar` and CI assert that the string `velocity` does not appear |
| Command literals do not clash | among the 85 command classes in the decompiled real server source there is **no ReportCommand** — vanilla `/report` is a client-side chat report, so registering `/report` on the server does not collide with it (literals with the same name but different argument types make Brigadier throw during registration) |
| Static checks | `verify.mjs` **350 checks**: new assertions for the NeoForge module's PSR-4 / project imports / platform isolation / descriptor / build wiring, and `net/kyori`, `net/minecraft`, `net/neoforged` were also added to the shared core's forbidden prefixes |
| In-JVM self-test | all **45 checks** pass (real JVM) |
| Protocol tests | all **35 checks** pass |

**Pitfalls encountered**:

| Symptom | Cause | Fix |
|------|------|------|
| `processResources` reports `Failed to parse template script` | the comment at the top of the mod descriptor contained the literal `${...}`, and `expand()` evaluated it as a Groovy expression | the literal no longer appears in the comment; it is described in words instead |
| `verifyJar` reports that the mod descriptor is missing a version | the case of the descriptor template variable name did not match `gradle.properties` (`neoForgeVersion` vs `neoforgeVersion`), so the regex used by the check could not match it | unified as `neoforgeVersion` |
| `verify.mjs` validated Gradle-generated JSON | the existing JSON walk only looked at `node_modules`, while the NeoForge build output lands under `neoforge/build/` | the JSON walk skips both `*/build/` and `.gradle/` |
| Gradle clearly printed `BUILD SUCCESSFUL` yet was judged a failure | NeoForm wrote a known-harmless `Cannot inject duplicate file mcp/client/Start.class` to stderr and PowerShell treated it as a NativeCommandError | the criterion changed to reading gradle's own `BUILD SUCCESSFUL` rather than the process exit code |

**Still not verified**: the mod has **never been loaded on a real NeoForge server** (there is no
Minecraft server on this machine). Command registration, event bus subscription, `MinecraftServer#execute`
scheduling and the actual rendering of `Component` go no further than "compile + static assertions";
the corresponding steps have already been added to the on-server checklist in section 4.

### 2.25 Multi-channel announcement display + report closed loop (0.0.20)

**Goal**: two things — ① besides the chat line, announcements can optionally use `actionbar` / `title` / `bossbar`, and several can be enabled at the same time; ② reports gain chat context, progress queries and a report outcome receipt.

**Verified**:

| Item | Evidence |
|------|------|
| Display channel parsing | covered by the in-JVM self-test: an empty value falls back to `chat`, multiple channels, tolerance of case and spaces, duplicate channels de-duplicated, unknown channels reported while `chat` is still kept |
| Chat buffer boundaries | covered by the in-JVM self-test: only the newest N entries are kept, in oldest→newest order; matching is case- and leading/trailing-space-insensitive; two players never bleed into each other; newlines inside a message are flattened (otherwise an extra line would appear out of nowhere in the record the moderator sees); empty messages are not recorded; a single line is truncated to 256 characters; nothing is recorded when the capacity is 0; the number of tracked players is capped (after 600 are written, the earliest 100 are evicted) |
| The display implementations of all three platforms really did compile | Paper: `player.sendActionBar` / `Title.title` + `Title.Times` / `BossBar.bossBar` + `player.showBossBar`; NeoForge: `displayClientMessage(msg, true)` / `ClientboundSetTitlesAnimationPacket` + `ClientboundSetTitleTextPacket` / `ServerBossEvent`. The NeoForge side compiled against a **real Minecraft classpath** |
| Report context captures public chat only | Paper uses `AsyncChatEvent` (MONITOR + ignoreCancelled), NeoForge uses `ServerChatEvent`; neither touches private messages or commands. The core buffer is capped in both directions, by entries per player and by total players |
| Protocol contract of the new endpoints | the protocol tests gain a 12th group of 10 checks: context persisted to the database, a report without context still succeeding as usual, `GET /reports` returning only the caller's own reports (other people's target names do not appear) and in reverse order, invalid UUID 422, unsigned 401, one resolution enqueuing exactly one targeted notification (with `target_uuid` / `server_key` / `payload`), resolving twice not notifying twice, reopening back to `pending` not notifying, notifications delivered only to the server that filed the report |
| Tag timing | confirmed by reading the vendored framework source directly: `DiscussionResourceFields` uses `raise()` (queued) rather than dispatching immediately, and `HasHooks::updateAction` calls `update()` first (which contains `tags()->sync()`) and then `dispatchEventsFor()`, so a `DiscussionWasTagged` listener reads the **new** tags — the same basis the framework's own `CreatePostWhenTagsAreChanged` relies on |
| Migration | the new migration uses an incremental `$schema->table()` to add `discussion_id` / `context`, both columns nullable (existing reports are unaffected), with the column-existence check in the outer closure (avoiding the closure-scope pitfall from 0.0.12) |
| Static checks | `verify.mjs` **384 checks**. Two things were added for this change: the model↔migration comparison now also reads the incremental `$schema->table()` block (otherwise the new columns would be falsely reported as "not in the migration"), and it no longer reads the `down` closure (`dropColumn('x')` looks exactly like a column definition, so a rollback would be mistaken for an addition) |
| In-JVM self-test | **68 checks** (45 → 68, adding the "display channel" and "recent public chat" groups) |
| Protocol tests | **45 checks** (35 → 45) |

**Pitfalls encountered**:

| Symptom | Cause | Fix |
|------|------|------|
| the outbox message returned by the mock had no `target_uuid` | `outboxPayload()` omitted this field, although the real `McOutboxMessage::toApiPayload()` does have it | added. This gap itself shows that the "targeted delivery" contract had never been covered by a test until now |
| the "status query can see a resolved report" case failed | it depended on state left behind by the previous case, while the intervening "reopening does not notify" case had changed that report back to `pending` | give each case its own setup step — a test that can only pass in a fixed order is a trap |
| Language keys reported by the static check as "never referenced" | the key name was stored in a variable first and then passed to `messages.prefixed(key, ...)`, while the check only recognises literal arguments | write both key names directly into the call, which reads better too |

**Still not verified**: as before — the NeoForge mod still has not been loaded on a real server;
`actionbar` / `title` / `bossbar` on NeoForge go no further than "compile + static assertions". Removing
the BossBar on Folia goes through `EntityScheduler#runDelayed`, which likewise has never been run on real
Folia. On the forum side, "changing a tag is the receipt" depends on the event timing of `flarum/tags`;
the basis is the vendored framework source, and it has not yet been triggered end to end on a real forum.

### 2.26 Diagnosing and fixing report discussions with "no context" (0.0.21)

**Symptom** (real forum `d/81`, report #14, server `Lobby`): the discussion body has only the report
details, the report reason and the signature, with **no chat transcript at all**, so the player side
cannot tell why.

**Diagnosis process** (every step backed by verifiable evidence rather than guesswork):

| Step | Means | Conclusion |
|------|------|------|
| 1 | fetched `GET /api/discussions/81` and `GET /api/posts?filter[discussion]=81` | the body really does not even contain the "this player's recent public chat" section; the report details, reason and signature are all normal, which shows the discussion creation and rendering chain itself works |
| 2 | the report time is 2026-09-26T12:41:47Z, and v0.0.20 was released at 11:51:40Z | the report happened about 50 minutes after the release, which matches "just updated and testing the new feature" |
| 3 | probed a route **registered only by 0.0.20**: `GET /api/mc-bridge/reports` returns **401** (an unsigned machine endpoint) rather than 404 | **the forum extension is confirmed to be 0.0.20** (an older version would 404), which rules out "the extension was not updated" |
| 4 | the insert succeeded (the discussion was created; if the `context` column did not exist, SQL would error and `/report` would return 500) | the migration has run and the `mc_reports.context` column exists |
| 5 | double-checked the behaviour of `Yaml.getInt` for a missing key | it correctly returns the default value → even if the server still has an **old config.yml** (`saveResource` never overwrites an existing file), `chat-context-lines` still resolves to 10, which **rules out** this one |

Conclusion: the extension is new, the column exists and the default is correct — in other words,
**the game side never sent the context up at all**.
Two possibilities remained, and both were acted on:

**Fix one (a real bug): cancelled chat events were skipped.** The original implementation was
`@EventHandler(priority = MONITOR, ignoreCancelled = true)`. Many chat-format plugins **cancel**
`AsyncChatEvent` and then broadcast their own copy — on such a server the original implementation
**records not a single line**, and the symptom is exactly the same as "the player said nothing".
Changed to **LOWEST priority and not skipping cancelled events**: at that point the event has not yet
been cancelled by another plugin, and `message()` is still the raw text the player typed (which is
exactly what is wanted for the record).

**Fix two (diagnosability): fix the fact that "three situations look exactly the same" itself.**

| Change | Effect |
|------|------|
| the startup log gains the version number: `McBridge 已启用（平台 {platform}，版本 {version}）…` ("McBridge enabled (platform {platform}, version {version})…") | which jar the server actually loaded is visible at a glance from then on (this time a detour was needed to find it out) |
| every report prints one line: `已附带 N 条聊天上下文` ("N chat context entries attached"), or the reason nothing was attached (the switch is 0 / the switch is on but nothing was captured) | the server log directly answers "what came with this one" |
| `/mcbridge stats` gains "聊天缓冲：X 名玩家 / Y 条" ("chat buffer: X players / Y entries") | whether capture is working can be confirmed at any time, without waiting for someone to file a report |
| the report request gains `context_enabled`; on that basis the extension writes "未附带聊天记录：开关是开着的……" ("No chat transcript attached: the switch is on …") in the body | the discussion body states for itself whether **the switch is off** or **the player did not speak**; an old plugin that does not send this field keeps the old behaviour, with no regression |

`context_enabled` is a **creation-time hint** and is not persisted — it reuses the existing
`$overrides` mechanism of `ReportDiscussion::create()` (the same path as title/tags/actor), so
**no new migration is needed**.

**Verified**:

| Item | Evidence |
|------|------|
| Defaults with an old config.yml | new in-JVM self-test: **delete line by line** the four keys added this time from the shipped config.yml (simulating a server that still has the old file after an upgrade), then assert that `chat-context-lines` = 10, the display channels = `[CHAT]`, the title 5 seconds and the boss bar 10 seconds, and that the config is still usable. It first asserts that "the keys really were deleted", so that the test cannot quietly stop testing anything |
| Diagnostic output does not leave placeholders behind | the existing `log.enabled leaves no placeholder behind` assertion **really did fail** during this change (the new `{version}` was not passed in), and passed after being updated in step — the guard rail worked |
| Language keys must not hide inside expressions | the first run of `verify.mjs` reported `log.report-context-empty` / `log.report-context-off` as "never referenced": the key names were hidden inside a ternary expression, and the check only recognises literal arguments. It passed only after being changed to one write each in if/else |
| `context_enabled` does not break the request contract | new protocol tests: with the four values `true` / `false` / absent (old plugin) / the string `"1"`, a report must be 201 and be persisted in every case |
| In-JVM self-test | **77 checks** (68 → 77) |
| Protocol tests | **46 checks** (45 → 46) |
| Static checks | **384 checks**, 0 warnings |

**Still not verified**: a Minecraft server cannot be started locally, so "cancelled chat events can now
be recorded" is only guaranteed at the level of compilation and unit tests — it needs one real test on a
server with a chat-format plugin installed (added to the checklist in section 4). Whether NeoForge's
`ServerChatEvent` actually fires on the 1.21.1 signed-chat pipeline can likewise only be confirmed on a
real machine.

## 3. Things that cannot be verified on this machine (now covered by CI)

> This machine has no PHP / JDK, and these checks **have all been executed automatically and passed in
> CI, in a real environment with PHP 8.3 / JDK 21** (see 2.4). The real-server rows are executed by the
> smoke job documented in [SMOKE-TEST.md](SMOKE-TEST.md). The table below is kept as reference
> commands for "manual re-checking on any machine".

| Item | Command | Expected | CI status |
|------|------|------|---------|
| PHP syntax | `find flarum-extension -name '*.php' -exec php -l {} \;` | no `Parse error` | ✅ executed and passed in CI |
| Java compilation | `cd mc-plugin && gradle build` | `BUILD SUCCESSFUL`, both jars produced | ✅ executed and passed in CI, both jars uploaded as artifacts |
| Flarum installation | `composer require stalirmc/mc-flarum-bridge` | the extension appears in the admin panel | ⬜ needs to be run on a real forum (CI does not install Flarum) |
| Migration execution | `php flarum migrate` | 6 tables created | ⬜ needs a real database (CI only validates the migration contract by reflection) |
| **Bridge self-test** | `php flarum mc-bridge:selftest --url=https://your.forum` | all `OK` | ⬜ needs to be run on a real forum |
| Plugin load (Paper) | put the jar in place and start the server | the log shows `McBridge enabled on paper as server ...` | ✅ the CI smoke job loads the jar on Paper 1.21.1 on every push; the fully configured startup line still needs a real forum |
| Plugin load (Folia) | put the same plugin jar into Folia's `plugins/` | the log shows `on folia`, and there is no `UnsupportedOperationException` | ✅ the CI smoke job loads the jar on Folia 1.21.8 (Folia publishes no 1.21.1 build); the fully configured startup line still needs a real forum |
| Mod load (NeoForge 1.21.1) | put `McBridge-neoforge-<version>.jar` into the server's `mods/` | the server log shows `MC Bridge loaded` and `McBridge enabled on neoforge as server ...`, and the platform line of `/mcbridge stats` shows `neoforge` | ⬜ needs a real NeoForge server |
| Announcement delivery | post an announcement on the forum; `[论坛] <title>` ("[Forum] <title>") appears in game within 20 seconds | the announcement is visible to players | ⬜ needs to be run on a real server |

`mc-bridge:selftest` is designed specifically for this: on **the machine running the forum** it checks
the secret length, HMAC signing/verification/tamper detection, the canonical string format, path
normalisation (including subdirectory installs), whether the 6 tables exist, whether the query paths are
usable and the binding code character set, and when `--url` is given it makes one **real signed HTTP
loopback request**, thereby covering the whole chain of routing, middleware, signature verification and
persistence.

## 4. Runtime verification checklist (copy-paste ready)

```bash
# ---- Forum side ----
cd <flarum>
php flarum migrate
php flarum cache:clear
php flarum mc-bridge:secret                    # note down the secret that is printed
php flarum mc-bridge:config --tags=1,3         # optional: sync only the "announcement" tag
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn

# ---- Game side ----
cd mc-plugin && gradle build
# Paper/Folia: copy build/libs/McBridge-<version>.jar to server/plugins/
#              edit plugins/McBridge/config.yml and fill in forum.url and security.secret
# NeoForge   : copy neoforge/build/libs/McBridge-neoforge-<version>.jar to server/mods/
#              edit config/mc-bridge/config.yml and fill in the same two entries
# start the server, then:
#   /mcbridge stats     -> the config status should be 「正常」 ("normal"), with the running platform on the first line
#   /mcbridge news      -> should list the latest forum announcements (available to all players)
#   /bind               -> should return an 8-character binding code
#   /report <player> <reason> -> should say it was submitted, and a pending record appears in the forum admin panel
#   /report status      -> should list the one the caller just filed (status 「处理中」 ("pending"))
#   (first let the reported player say a few things in public chat, then file the report; these lines should appear in the forum discussion body)

# ---- Display channel (0.0.20) ----
# change game.announce-display in config.yml, then /mcbridge reload,
# then post an announcement from the forum and confirm one by one:
#   "actionbar" -> that line appears above the action bar
#   "title"     -> a title appears in the centre of the screen, with the body as the subtitle, and disappears after about `title-seconds` seconds
#   "bossbar"   -> a boss bar appears at the top and disappears after about `bossbar-seconds` seconds (it does not stay forever)
#   "chat,actionbar" -> both appear at the same time

# ---- Report outcome receipt (0.0.20) ----
php flarum mc-bridge:report --list                  # note the number, e.g. 42
php flarum mc-bridge:report 42 --status=resolved    # the reporter should receive a notification in game immediately
php flarum mc-bridge:report 42 --status=resolved    # run it again: it should say 「没有改动」 ("no changes"), and must not notify twice
php flarum mc-bridge:report 42 --status=pending     # reopen: there should be no notification at all
# the automatic tag path (requires tags to be configured, and the reporter to be online):
php flarum mc-bridge:config --report-resolved-tags=15
# then move discussion #<discussion_id> into tag 15 on the forum; the reporter should receive the notification within the next poll (about 20 seconds)

# ---- Chat context (0.0.21) ----
# 1) first confirm which version the server loaded and whether capture is working:
#      the log should show 「McBridge 已启用（平台 paper，版本 0.0.21）…」 ("McBridge enabled (platform paper, version 0.0.21)…")
#      have the reported player say a few things in a public channel now, then run /mcbridge stats
#      -> 「聊天缓冲」 ("chat buffer") should go from 0 to 「1 名玩家 / N 条」 ("1 player / N entries"); if it is still 0, capture is not working
# 2) if that server has a chat-format plugin that cancels AsyncChatEvent, this item **especially** needs testing (that is exactly what 0.0.21 fixed)
# 3) report that player:
#      - the discussion body should contain 「该玩家的近期公开聊天」 ("this player's recent public chat") plus a code block
#      - the server log should print 「举报 <player> 已附带 N 条聊天上下文」 ("report <player> filed with N chat context entries")
# 4) if the body says 「未附带聊天记录：游戏服务器上的开关是开着的…」 ("No chat transcript attached: the switch on the game server is on …")
#      = the switch is on, but the player really had not spoken in a public channel before being reported
# 5) if the body has no such section at all
#      = the switch is 0, or the server plugin is still an old version — the version number in the startup log is enough to tell which
# 6) cross-check: private messages (/msg) and commands (/...) **must not** appear in the record

```

Post a new discussion on the forum; `[论坛] <title>` ("[Forum] <title>") should appear in game within 20 seconds.

## 5. Strength of the verification

An honest statement of the boundaries:

- Static validation can prove that the **structure is correct, the contracts are consistent and references resolve**; it cannot replace a compiler.
- The protocol tests can prove that **the protocol specification is self-consistent and implementable**, but they cannot prove that the concrete PHP/Java implementation is free of runtime errors (for example, that an API's signature does not differ on the target version).
- **The compilation link has been filled in by CI**: the real run in section 2.4 proves that the plugin compiles into a jar under JDK 21 + Paper API and that all PHP files pass `php -l`. And this path really does pay off — its first run caught 4 real defects that could not be found on this machine.
- What is still **not** verified end to end is "runtime behaviour": CI compiles the code and now also starts a real Minecraft server (Paper 1.21.1 and Folia 1.21.8) with the built jar, but it does not start Flarum, does not connect the two sides together and does not connect to a database. Therefore:
  - for the Flarum-side endpoints/migrations/linking flow, running `mc-bridge:selftest` once is still recommended;
  - the plugin-side real heartbeat and announcement delivery need to be observed by installing it on a server.
- The final verdict still requires running section 4 once on a real server. `mc-bridge:selftest` has already compressed this forum-side step down to a single command.
