# Release Process (maintainers)

**English** · [简体中文](RELEASING.zh-CN.md)

This document is for **release managers**. From [`README.md`](README.md) (the deployment guide),
installers only need `composer require stalirmc/mc-flarum-bridge`; they do not need to know how
Packagist syncs.

## 1. Complete steps for a single release

```bash
# 1) Bump the version number (all three places must agree; verify.mjs checks this)
#    mc-plugin/gradle.properties        version=X.Y.Z
#    mc-plugin/src/main/java/cn/stalir/mcbridge/Version.java   VERSION = "X.Y.Z"
#    flarum-extension/composer.json     "version": "X.Y.Z"

# 2) Local verification (both projects compile + 77 in-JVM self-test checks + verifyJar + 386 static consistency checks + 46 protocol conformance tests + a real server loads the jar)
cd mc-plugin && gradle build && cd ..
node tools/verify.mjs
node tools/protocol-test.mjs
node tools/smoke-server.mjs --jar mc-plugin/build/libs/McBridge-X.Y.Z.jar --project paper --version 1.21.1

# 3) Commit and tag — the tag must equal v plus the version in gradle.properties
git add -A && git commit -m "chore: release X.Y.Z"
git tag -a vX.Y.Z -m "McBridge X.Y.Z"
git push origin main && git push origin vX.Y.Z
```

Pushing the tag triggers `.github/workflows/release.yml`, which will:

1. Verify that the tag matches the version in `gradle.properties` (a mismatch fails immediately,
   so the wrong version is never released)
2. Run `gradle build` under JDK 21 (including the content assertions of `verifyJar` for each of the
   two projects: the plugin jar and the mod jar)
3. Upload `McBridge-X.Y.Z.jar`, `McBridge-neoforge-X.Y.Z.jar` and `SHA256SUMS`, which is what an
   installer runs `sha256sum -c` against

CI (`.github/workflows/ci.yml`) also runs the static consistency checks, the protocol conformance
tests, PHP lint and the `smoke` job - which loads the built jar on a real Paper 1.21.1 and Folia
1.21.8 server - on `main`.

## 2. The three sources of the version number

| Location | Who uses it |
|------|--------|
| `version` in `mc-plugin/gradle.properties` | Gradle injects it into `plugin.yml` and it determines the jar name |
| `VERSION` in `Version.java` | HTTP User-Agent (a compile-time constant; the versions in `plugin.yml` and in the mod descriptor are injected by Gradle) |
| the git tag `vX.Y.Z` | triggers the release and determines the Release title |
| `version` in `flarum-extension/composer.json` | **the version number shown on the Flarum admin page** (see the note below) |

Section 17 of `tools/verify.mjs` checks that these places agree, and `release.yml` checks that they
agree with the tag.

> **Why the sub-package also needs a `version`**: for `flarum-subextensions`, Flarum reads the
> **sub-extension's own** `composer.json` (`ExtensionManager::extensionFromJson` →
> `Arr::get($package, 'version', '0.0')`). When this field is missing, the admin page shows a
> hard-coded **`0.0`** that has nothing to do with the real version. This file is not published to
> Packagist on its own, so writing `version` here has no side effects, but **it must be changed
> together when the version is bumped** (verify.mjs blocks a mismatch).
>
> `authors[].homepage` in the same file determines the link on the author name in the admin page:
> the order in which it takes values is `homepage` → `email` → **empty string**, and an empty
> string is resolved by the browser to the current page, so clicking "StalirMC" only returns to
> `/admin`. Every author therefore needs a `homepage` or an `email` (verify.mjs checks this too).

> Note: `expand(version: …)` of `processPaperResources` is already declared as a task input;
> otherwise Gradle still judges the task UP-TO-DATE after a version bump and bakes the old version
> number into the jar (this bit us during the 0.0.2 release).

## 3. Packagist

Package URL: <https://packagist.org/packages/stalirmc/mc-flarum-bridge>

**After a new tag is pushed, Packagist has to be triggered once before it fetches.** Measured (see
[`VERIFICATION.md`](VERIFICATION.md) 2.13): the repository has no webhook configured, and several
minutes after the commit was pushed Packagist was still on the old commit with an unchanged fetch
time, so **by default it does not sync automatically**.

Two approaches, pick either one:

- **Manual**: go to the page above and click **Update** once (the least effort; once that is done
  you can `composer require` the new version)
- **Automatic**: use the URL Packagist gives you on its page and configure a webhook in the GitHub
  repository under Settings → Webhooks

> A step "call the Packagist update API after publishing" (triggered by reading two secrets) was
> once added to `release.yml` and **has been removed at the maintainers' request**: the release
> process stays simple, and syncing is left to that one click on the Packagist page. If you want to
> save this step, configure a webhook on the Packagist side; this repository does not need to
> change again.

## 4. Package name and extension ID (think twice before renaming)

Flarum **derives the extension ID from the sub-package name**: `stalirmc/mc-bridge` →
`stalirmc-mc-bridge`. It appears in the translation domain, in the root key of `locale/*.yml`, in
the front-end initializer and in all translation keys, so a package rename must change them
together.

The cost of a rename (done once in 0.0.6):

| Impact | Explanation |
|------|------|
| Forums that already have it installed must reinstall | `composer remove <old package>` → `composer require <new package>` → `php flarum extension:enable <new ID>` |
| The extension's enabled state is lost | Flarum records the enabled list by extension ID |
| Data and settings are **preserved** | Table names and setting keys have nothing to do with the package name; the migration has a `hasTable` guard, so re-running is safe |
| The old package name can no longer be installed | Only the new name exists on Packagist, and VCS reads the repository's current `composer.json` |

## 5. Pre-release checklist

- [ ] The extension front end changed → remind installers (and your own test site) to run `php flarum assets:publish`, otherwise browsers still load the old scripts
- [ ] PHP under `flarum-extension/` changed → CI's PHP lint passes
- [ ] Java under `mc-plugin/` changed → `verifyJar` passes (two descriptors, two entry-point classes, zero platform references in the shared layer)
- [ ] A Flarum API call was added or changed → verify against **the release that is deployed**, not against a local framework copy
      (0.0.3 used an `isRegistered()` that does not exist in rc.8 and turned the whole forum into a 500, see VERIFICATION.md 2.11)
- [ ] A field or translation key read by the front end was added or changed → section 18 of `verify.mjs` compares the PHP declarations with the locale
- [ ] An endpoint called by the front end was added or changed → `verify.mjs` compares the methods + paths registered in `extend.php`
- [ ] CI is green, including the `smoke` job → it loads the built jar on real Paper and Folia, so a jar no server can load does not reach a release
- [ ] The release page lists both jars plus `SHA256SUMS` (the workflow generates the file; check it is attached)
