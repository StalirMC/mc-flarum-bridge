# McBridge — Minecraft side (Paper / Folia plugin + NeoForge mod)

**English** · [简体中文](README.zh-CN.md)

Connects a Minecraft server to Flarum: it receives forum announcements/broadcasts and
provides in-game `/bind` account linking and `/report` reporting.

- Targets: **Paper 1.21.x**, **Folia 1.21.x**, **NeoForge 21.1.x for Minecraft 1.21.1**
- Java: shared core bytecode target **17**, `paper` and `neoforge` modules target **21**
  (both paper-api 1.21.1 and Minecraft 1.21.1 require 21); build toolchain JDK 21
- Dependencies: Paper API, Adventure, PlaceholderAPI (all `compileOnly`, provided to the paper module only)
  and **Gson**; on the NeoForge side ModDevGradle provides Minecraft and NeoForge
- Shadow/relocate is **not needed**: the jar contains no third-party code

## Two artifacts

The build produces two mutually independent jars; they compile the same shared core source:

| Project | source set | Contents | Output |
|------|-----------|------|------|
| Root project | `main` (`src/main/java`) | Platform-independent core: protocol, config, language files, announcement orchestration. **Zero platform references** | — |
| Root project | `paper` (`src/paper/java`) | Paper and Folia entry points | `build/libs/McBridge-<version>.jar` |
| `neoforge` subproject | `main` (`neoforge/src/main/java` + the root project's `src/main/java`) | NeoForge entry point | `neoforge/build/libs/McBridge-neoforge-<version>.jar` |

NeoForge is a separate project because ModDevGradle must **own the Minecraft dependency** —
only by putting it in a subproject can you guarantee that Minecraft / NeoForge never appear
on the shared core's compile classpath.
Each of the two jars contains its own copy of the core classes and does not depend on the other.

The Paper plugin jar has `plugin.yml` at its root; the NeoForge mod jar has `META-INF/neoforge.mods.toml`.
At build time each project's `verifyJar` checks:

- the descriptor, the entry classes, `config.yml` and `lang/*.yml` are all present
- `plugin.yml`'s `main:` points to the paper module, and `folia-supported: true`
- the version in the descriptor matches the project version
- **the shared layer's class constant pool contains no `org/bukkit/`, `io/papermc/`, `net/kyori/`,
  `net/minecraft/`, `net/neoforged/`**
- the plugin jar **no longer** contains any Velocity artifact; the mod jar contains no `plugin.yml`

The second-to-last one is the point: once the shared core references a platform class, loading
it on the other platform throws `NoClassDefFoundError`. Adventure is in that category too —
**Paper ships Adventure, Minecraft 1.21.1 does not** — so the core renders its own
`cn.stalir.mcbridge.Message`, which each platform converts into its own component type at delivery time.

### Folia

Folia's scheduler API (`io.papermc.paper.threadedregions.scheduler`) is right there in **paper-api**,
so no extra `folia-api` dependency is needed, and neither is reflection:

- at runtime it decides with `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`
- when regionised it uses only `Bukkit.getAsyncScheduler()` / `Bukkit.getGlobalRegionScheduler()`
  (in that case `Bukkit.getScheduler()` throws `UnsupportedOperationException`)
- on ordinary Paper it still goes through the classic `BukkitScheduler`
- on Folia, player messages are delivered through `Player#getScheduler()` to the region thread that player belongs to

## Building

This repository does not include the Gradle wrapper binary (`gradle-wrapper.jar`); use the system
Gradle 8.10+ directly (if you would rather switch to the wrapper, first run `gradle wrapper --gradle-version 8.10`):

```bash
cd mc-plugin
gradle build              # build both projects
gradle :build             # Paper/Folia plugin only

# when targeting a different Paper version (overrides the default in gradle.properties)
gradle build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

**JDK 21** is required (`java.toolchain` finds it automatically; set `JAVA_HOME` if it cannot).
The first build on the plugin side pulls dependencies from the PaperMC and ExtendedClip
repositories; on the mod side, the first build has ModDevGradle download Minecraft 1.21.1 and
NeoForge 21.1.100 and run NeoForm once — **about 5-10 minutes**, and it needs network access;
afterwards it uses the cache.

Artifacts (`build` depends on each project's `verifyJar`, and fails outright if the contents fall short):

```
build/libs/McBridge-<version>.jar
neoforge/build/libs/McBridge-neoforge-<version>.jar
```

## Installation

| Platform | Put it in | Config and language directory |
|------|------|----------------|
| Paper / Folia | `plugins/` | `plugins/McBridge/` |
| NeoForge | `mods/` (server side only, `side = SERVER`) | `config/mc-bridge/` |

1. Put the jar in the corresponding directory.
2. Start the server once to generate `config.yml`.
3. Fill in the forum URL and the shared secret (generate it with `php flarum mc-bridge:secret`).
4. `/mcbridge reload`.

```yaml
language: zh_CN                            # output language, Simplified Chinese by default

forum:
  url: "https://forum.kxkl2024.cn"
  api-prefix: "/api/mc-bridge"
server:
  key: "survival"
  name: "Stalir 生存服"
security:
  secret: "<at least 32 characters shared secret>"

game:
  # announcement display channels, multiple allowed: chat / actionbar / title / bossbar
  # Example: "chat,bossbar" sends both a chat line and a boss bar at the top.
  # Only the chat channel shows the body text and links in full; title uses the body as the subtitle.
  announce-display: "chat"
  title-seconds: 5              # how long the title stays on screen
  bossbar-seconds: 10           # how long the boss bar stays on screen
  prompt-unbound: true          # prompt unbound players once on join about how to link (stays silent when the forum is unreachable)

report:
  # Title template for the discussion /report creates on the forum.
  #   {target} {reporter} {reason} {server}  filled in by the plugin
  #   %...%                                  PlaceholderAPI (only when installed; kept as-is when not)
  # Leave empty = let the forum render it with its own template.
  # The shipped default is "[举报] {target}（由 {reporter} 提交）" ([Report] {target} (submitted by {reporter})).
  title-format: "[举报] {target}（由 {reporter} 提交）"
  # Which forum tags report discussions are filed under, comma-separated; each entry may be a slug (such as reports) or a tag ID,
  # for example tags: "reports,pending". Note that flarum/tags limits the number of primary/secondary tags;
  # when that is exceeded, the posting account needs the bypassTagCounts permission. Leave empty = use the forum-side setting.
  tags: ""
  # Which forum account publishes report discussions; may be a username or a user ID. Leave empty = use the forum-side setting.
  actor: ""
  # Attach the reported player's own most recent N public chat messages to the report (0 = off).
  # Only public chat is collected: private messages and commands never enter the buffer; there is a cap per player and on the total number of players.
  chat-context-lines: 10
```

## Platform capability comparison

| Feature | Paper / Folia | NeoForge |
|------|---------------|----------|
| Forum announcement broadcast (chat / actionbar / title / bossbar, multiple selectable) | ✅ | ✅ |
| `/bind`, `/mcbridge` | ✅ | ✅ |
| `/report` reporting to the forum (including chat context) | ✅ titles support PlaceholderAPI | ✅ (no PlaceholderAPI equivalent; the title template is passed through as-is) |
| `/report status` to check report progress | ✅ | ✅ |
| Report outcome receipt | ✅ | ✅ |
| Permission | Bukkit permission nodes + OP | OP level 2 only (except `news`) |

All requests are generated by the shared core, and the messages of the three platforms are
byte-for-byte identical, so the forum side does not need to distinguish platforms;
the platform name (`paper` / `folia` / `neoforge`) appears in `/mcbridge stats` and in the startup log.

## Language

Player-visible messages and plugin logs are **not in config.yml**, but in `<plugin directory>/lang/<language>.yml`. Shipped with the jar:

| File | Language |
|------|------|
| `lang/zh_CN.yml` | Simplified Chinese (default) |
| `lang/en.yml` | English |

The first startup extracts them into the plugin directory, where they can be edited directly (existing files are not overwritten).

```yaml
# config.yml
language: zh_CN     # change to en to switch to English
```

**Adding a language**: copy `lang/zh_CN.yml` to `lang/ja_JP.yml` and translate it,
then set `language` to `ja_JP`. If a key is missing in the new language, it **automatically falls back to Chinese**,
and is not shown as the raw key name.

Placeholders use curly braces, for example `{code}`, `{reason}`; color codes use `&`.

## Commands

| Command | Permission | Description |
|------|------|------|
| `/bind` | `mcbridge.bind` (all players by default) | request a binding code and show it in chat |
| `/mcbridge status` | `mcbridge.admin` | view the server status recorded by the forum |
| `/mcbridge outbox` | `mcbridge.admin` | view messages pending delivery (read-only, does not consume them) |
| `/mcbridge broadcast <content>` | `mcbridge.admin` | submit a broadcast to the forum queue |
| `/mcbridge stats` | `mcbridge.admin` | local statistics (queue, failure count, running platform, etc.) |
| `/mcbridge reload` | `mcbridge.admin` | reload the config and restart the tasks |

The NeoForge side has no Bukkit permission nodes: `/mcbridge news` is available to everyone,
the remaining subcommands require OP (permission level 2), and `/bind` and `/report` are available to everyone.

## How it runs

| Task | Default interval | Purpose |
|------|---------|------|
| Outbox poll | 20s | `GET /outbox` and deliver announcements/broadcasts (`sync.outbox-poll-interval-seconds`) |

- All network calls run on an **async thread**; message display and command execution return to the main thread,
  or on NeoForge to the server thread. Neither blocks the server tick.

## Security

- Every request is signed with HMAC-SHA256, including a timestamp and a single-use nonce.
- The plugin **does not execute** any command coming from the forum: the forum is only responsible for pushing announcements/broadcasts to the game, and the game side only displays them.

## Source layout

```
src/main/java/cn/stalir/mcbridge/           shared core (zero platform references, JDK + Gson)
├── BridgeCore.java        announcement orchestration + rendering of all command copy + report flow
├── Platform.java          platform SPI (scheduler, data directory, output and display channels)
├── Message.java           platform-independent message (legacy & codes; converted by each platform into its own component)
├── DisplayChannel.java    announcement display channel (chat / actionbar / title / bossbar)
├── ChatLog.java           bounded ring buffer of recent public chat (used for report context)
├── BridgeConfig.java      config loading and validation
├── BridgeException.java   protocol-layer exception (including HTTP status code)
├── Yaml.java              minimal YAML reader (to avoid stuffing third-party libraries into the jar)
├── Signature.java         HMAC-SHA256 signature
├── HttpBridgeClient.java  HTTP client
├── Log.java               logging SPI
├── Messages.java          language file loading and {placeholder} rendering → Message
└── Version.java           version constants (shared with the HTTP User-Agent)

src/paper/java/cn/stalir/mcbridge/paper/    Paper + Folia
├── McBridgePlugin.java    entry point (thin wrapper)
├── PaperPlatform.java     both Folia / Bukkit schedulers + player delivery (including title/bossbar)
├── AdventureMessages.java Message → Adventure Component (the only conversion point)
├── PlayerListener.java    prompts unlinked players on join
├── ChatListener.java      records public chat (AsyncChatEvent)
├── PlaceholderApiHook.java optional PAPI expansion (the only class in the whole jar that mentions PAPI)
├── BindCommand.java       /bind
├── ReportCommand.java     /report, /report status
└── BridgeCommand.java     /mcbridge

neoforge/src/main/java/cn/stalir/mcbridge/neoforge/   NeoForge 21.1.x
├── McBridgeMod.java            entry point (@Mod("mc_bridge"), event bus registration, including the chat listener)
├── NeoForgePlatform.java       MinecraftServer#execute scheduling + player delivery (including title/bossbar)
├── NeoForgeMessages.java       Message → net.minecraft.network.chat.Component
├── NeoForgeLog.java            SLF4J adapter
├── BindCommand.java            /bind (Brigadier)
├── ReportCommand.java          /report, /report status (Brigadier)
└── BridgeCommand.java          /mcbridge (Brigadier)
```

## Development

Even without a JDK on the machine you can still run the static consistency checks
(including the structure and version consistency checks across the three platforms):

```bash
node ../tools/verify.mjs
```
