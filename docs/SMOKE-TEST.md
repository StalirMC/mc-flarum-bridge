# Real-server smoke test

**English** · [简体中文](SMOKE-TEST.zh-CN.md)

`tools/smoke-server.mjs` starts a real Minecraft server with a built McBridge jar and
asserts that the plugin actually enables. It is the only check in this repository that
executes the jar inside a server; every other one stops at "it compiles" or "the jar
contains the right entries".

Run it through the `smoke` job in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml),
which loads the exact jar the `java` job just validated.

## What it proves

- the server accepts the jar: no `Could not load 'plugins/...'`, no
  `UnsupportedClassVersionError`, no `NoClassDefFoundError`/`NoSuchMethodError` on
  `cn.stalir.*`
- the plugin reaches `Enabling McBridge v<version>`, and the version it enables is the
  one in the jar's file name
- the server finishes starting with the plugin present (`Done (`)
- the server stops cleanly after `stop` on its console and exits with code 0

## What it does not prove

It does not connect the two sides. It does not start Flarum, does not create a binding,
does not deliver an announcement and does not file a report. "The plugin enables on a real
server" is a strictly weaker claim than "the bridge works", and the delivery paths still
need the checklist in [VERIFICATION.md](VERIFICATION.md) section 4.

The NeoForge mod is not covered either: NeoForge needs its own installer step, so the mod
still only reaches "compiles + the jar is asserted to contain the right descriptor and
entry point".

## Running it locally

```bash
# Paper (the primary target, matching the compile-time API)
node tools/smoke-server.mjs --jar mc-plugin/build/libs/McBridge-0.0.21.jar \
  --project paper --version 1.21.1

# Folia
node tools/smoke-server.mjs --jar mc-plugin/build/libs/McBridge-0.0.21.jar \
  --project folia --version 1.21.8
```

Options: `--workdir DIR` (defaults to a directory under the OS temp dir), `--timeout
SECONDS` (default 300). `JAVA_CMD` overrides the `java` binary, which matters when the
`java` on your `PATH` is not the JDK 21 the server needs.

On failure the script prints the last 40 lines of the server log; CI also uploads that log
as the `smoke-log-<project>` artifact.

## Version pins, and why they are what they are

| Project | Pinned version | Why |
|---------|----------------|-----|
| Paper | 1.21.1 (`--version` it explicitly) | The same version the plugin is compiled against (`paperApiVersion` in `mc-plugin/gradle.properties`) |
| Folia | 1.21.8 | **Folia publishes no 1.21.1 build.** Its 1.21.x line starts at 1.21.4 (ALPHA) and 1.21.8 is the STABLE one |

Two facts about the download API are worth knowing before editing this script:

- **The v2 API is gone.** `api.papermc.io/v2/...` answers **HTTP 410 Gone**; the API is
  now `fill.papermc.io/v3`, and it **requires a `User-Agent` header** (a request without
  one is refused). A pinned old endpoint or a missing header is the first thing to check
  when this job starts failing while the code is fine.
- **Newer Folia lines need a newer JDK.** The 1.21.11 and 26.x versions report a minimum
  of Java 25, while 1.21.8 reports Java 21. Moving the pin forward means moving the CI
  JDK forward too.

## Recorded runs

Both were executed on the maintainer's machine against the `0.0.21` jar, using JDK 21
(`.tools/jdk21`), and both passed:

| Project | Build | Result |
|---------|-------|--------|
| Paper | 1.21.1 build 133 (STABLE) | `[McBridge] Enabling McBridge v0.0.21`, `Done (31.542s)!`, `stop` → `Stopping server`, exit 0 |
| Folia | 1.21.8 build 6 (STABLE) | plugin enabled, server finished starting, `stop` → exit 0 |

What the pinned runs do **not** confirm is anything past "enabled": the run uses the
shipped default configuration, so the plugin deliberately reports
`security.secret` as missing and pauses the features that need it. That is expected and is
not a failure - it is the same code path a freshly installed server takes.
