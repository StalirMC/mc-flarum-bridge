# McBridge — Minecraft 侧（Paper / Folia 插件 + NeoForge 模组）

把 Minecraft 服务端接入 Flarum：接收论坛公告/广播，并提供游戏内
`/bind` 账号绑定与 `/report` 举报。

- 目标：**Paper 1.21.x**、**Folia 1.21.x**、**NeoForge 21.1.x for Minecraft 1.21.1**
- Java：共享核心字节码目标 **17**，`paper` 与 `neoforge` 模块目标 **21**
  （paper-api 1.21.1 与 Minecraft 1.21.1 都要求 21）；构建工具链 JDK 21
- 依赖：Paper API、Adventure、PlaceholderAPI（均 `compileOnly`，只给 paper 模块）
  与 **Gson**；NeoForge 侧由 ModDevGradle 提供 Minecraft 与 NeoForge
- **不需要** Shadow/relocate：jar 里不含任何第三方代码

## 两个发行包

构建产出两个互相独立的 jar，它们编译同一份共享核心源码：

| 工程 | source set | 内容 | 产物 |
|------|-----------|------|------|
| 根工程 | `main`（`src/main/java`） | 平台无关核心：协议、配置、语言文件、公告编排。**零平台引用** | — |
| 根工程 | `paper`（`src/paper/java`） | Paper 与 Folia 入口 | `build/libs/McBridge-<版本>.jar` |
| `neoforge` 子工程 | `main`（`neoforge/src/main/java` + 根工程的 `src/main/java`） | NeoForge 入口 | `neoforge/build/libs/McBridge-neoforge-<版本>.jar` |

NeoForge 之所以是独立工程，是因为 ModDevGradle 必须**拥有 Minecraft 依赖**——
把它放进子工程，才能保证 Minecraft / NeoForge 永远不出现在共享核心的编译类路径上。
两个 jar 各自包含一份核心 class，互不依赖。

Paper 插件 jar 根放 `plugin.yml`；NeoForge 模组 jar 放 `META-INF/neoforge.mods.toml`。
构建时两个工程各自的 `verifyJar` 会校验：

- 描述符、入口类、`config.yml`、`lang/*.yml` 都在
- `plugin.yml` 的 `main:` 指向 paper 模块，且 `folia-supported: true`
- 描述符里的版本与项目版本一致
- **共享层的 class 常量池里不出现 `org/bukkit/`、`io/papermc/`、`net/kyori/`、
  `net/minecraft/`、`net/neoforged/`**
- 插件 jar 里**不再**出现任何 Velocity 产物；模组 jar 里不出现 `plugin.yml`

倒数第二条是重点：共享核心一旦引用平台类，另一平台加载时就会 `NoClassDefFoundError`。
Adventure 也在此列——**Paper 自带 Adventure，Minecraft 1.21.1 不带**，所以核心
渲染自己的 `cn.stalir.mcbridge.Message`，由各平台在投递时转成自己的组件类型。

### Folia

Folia 的调度 API（`io.papermc.paper.threadedregions.scheduler`）就在 **paper-api** 里，
所以不需要额外的 `folia-api` 依赖，也不需要反射：

- 运行时用 `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` 判断
- regionised 时只用 `Bukkit.getAsyncScheduler()` / `Bukkit.getGlobalRegionScheduler()`
  （此时 `Bukkit.getScheduler()` 会抛 `UnsupportedOperationException`）
- 普通 Paper 上仍走经典 `BukkitScheduler`
- 玩家消息在 Folia 上通过 `Player#getScheduler()` 投递到该玩家所属的 region 线程

## 构建

本仓库不包含 Gradle wrapper 的二进制（`gradle-wrapper.jar`），直接用系统 Gradle 8.10+
（若想改用 wrapper，先跑一次 `gradle wrapper --gradle-version 8.10`）：

```bash
cd mc-plugin
gradle build              # 两个工程都构建
gradle :build             # 只要 Paper/Folia 插件

# 目标 Paper 版本不同时（会覆盖 gradle.properties 中的默认值）
gradle build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

需要 **JDK 21**（`java.toolchain` 会自动寻找；找不到时请设置 `JAVA_HOME`）。
插件侧首次构建会从 PaperMC 与 ExtendedClip 仓库拉取依赖；模组侧首次构建时
ModDevGradle 会下载 Minecraft 1.21.1 与 NeoForge 21.1.100 并跑一遍 NeoForm，
**约 5-10 分钟**、需要网络，之后走缓存。

产物（`build` 依赖各自的 `verifyJar`，内容不达标会直接失败）：

```
build/libs/McBridge-<版本>.jar
neoforge/build/libs/McBridge-neoforge-<版本>.jar
```

## 安装

| 平台 | 放到 | 配置与语言目录 |
|------|------|----------------|
| Paper / Folia | `plugins/` | `plugins/McBridge/` |
| NeoForge | `mods/`（仅服务端，`side = SERVER`） | `config/mc-bridge/` |

1. 把 jar 放进对应目录。
2. 启动一次生成 `config.yml`。
3. 填入论坛地址与共享密钥（用 `php flarum mc-bridge:secret` 生成）。
4. `/mcbridge reload`。

```yaml
language: zh_CN                            # 输出语言，默认简体中文

forum:
  url: "https://forum.kxkl2024.cn"
  api-prefix: "/api/mc-bridge"
server:
  key: "survival"
  name: "Stalir 生存服"
security:
  secret: "<至少 32 字符的共享密钥>"

game:
  # 公告展示形式，可多选：chat / actionbar / title / bossbar
  # 例："chat,bossbar" 同时发聊天行与顶部血条。
  # 只有 chat 通道会完整显示正文与链接；title 用正文做副标题。
  announce-display: "chat"
  title-seconds: 5              # 大标题停留时间
  bossbar-seconds: 10           # 血条停留时间
  prompt-unbound: true          # 未绑定玩家进服时提示一次怎么绑定（论坛不可达时保持沉默）

report:
  # /report 在论坛创建的讨论的标题模板。
  #   {target} {reporter} {reason} {server}  由插件填充
  #   %...%                                  PlaceholderAPI（装了才有；没装则原样保留）
  # 留空 = 让论坛按自己的模板渲染。
  title-format: "[举报] {target}（由 {reporter} 提交）"
  # 举报讨论归入哪些论坛标签，逗号分隔，每项可填 slug（如 reports）或标签 ID，
  # 例如 tags: "reports,pending"。注意 flarum/tags 对主/次标签数量有限制，
  # 超出时发布账号需要 bypassTagCounts 权限。留空 = 用论坛侧设置。
  tags: ""
  # 举报讨论以哪个论坛账号发布，可填用户名或用户 ID。留空 = 用论坛侧设置。
  actor: ""
  # 随举报附上被举报玩家本人最近 N 条公开聊天（0 = 关闭）。
  # 只采集公开聊天：私聊与命令从不进入缓冲区；每名玩家与总玩家数都有上限。
  chat-context-lines: 10
```

## 平台能力对照

| 功能 | Paper / Folia | NeoForge |
|------|---------------|----------|
| 论坛公告广播（chat / actionbar / title / bossbar 可多选） | ✅ | ✅ |
| `/bind`、`/mcbridge` | ✅ | ✅ |
| `/report` 举报到论坛（含聊天上下文） | ✅ 标题支持 PlaceholderAPI | ✅（无 PlaceholderAPI 等价物，标题模板原样传递） |
| `/report status` 查看举报进度 | ✅ | ✅ |
| 举报处理结果回执 | ✅ | ✅ |
| 权限 | Bukkit 权限节点 + OP | 只用 OP 等级 2（`news` 除外） |

所有请求都由共享核心生成，三个平台的报文逐字节一致，因此论坛侧无需区分平台；
平台名（`paper` / `folia` / `neoforge`）会出现在 `/mcbridge stats` 与启动日志里。

## 语言

玩家可见的消息与插件日志**不在 config.yml 里**，而在 `<插件目录>/lang/<语言>.yml`。随 jar 附带：

| 文件 | 语言 |
|------|------|
| `lang/zh_CN.yml` | 简体中文（默认） |
| `lang/en.yml` | English |

首次启动会把它们解压到插件目录，可直接编辑（已有文件不会被覆盖）。

```yaml
# config.yml
language: zh_CN     # 改成 en 即切换为英文
```

**新增语言**：把 `lang/zh_CN.yml` 复制为 `lang/ja_JP.yml` 并翻译，
再把 `language` 设为 `ja_JP`。某个键若在新语言里缺失，会**自动回退到中文**，
不会显示成原始键名。

占位符用花括号，例如 `{code}`、`{reason}`；颜色代码用 `&`。

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/bind` | `mcbridge.bind`（默认所有玩家） | 申请绑定码并在聊天栏显示 |
| `/mcbridge status` | `mcbridge.admin` | 查看论坛记录的服务器状态 |
| `/mcbridge outbox` | `mcbridge.admin` | 查看待投递消息（只读，不消费） |
| `/mcbridge broadcast <内容>` | `mcbridge.admin` | 提交一条广播到论坛队列 |
| `/mcbridge stats` | `mcbridge.admin` | 本地统计（队列、失败次数、运行平台等） |
| `/mcbridge reload` | `mcbridge.admin` | 重新加载配置并重启任务 |

NeoForge 侧没有 Bukkit 权限节点：`/mcbridge news` 所有人可用，其余子命令需要 OP
（权限等级 2），`/bind` 与 `/report` 所有人可用。

## 运行机制

| 任务 | 默认间隔 | 作用 |
|------|---------|------|
| Outbox 轮询 | 20s | `GET /outbox` 并投递公告/广播（`sync.outbox-poll-interval-seconds`） |

- 所有网络调用都在**异步线程**执行；消息展示与指令执行回到主线程，NeoForge 上则是服务端线程。
  两者都不会阻塞服务器 tick。

## 安全

- 每个请求都用 HMAC-SHA256 签名，包含时间戳与一次性 nonce。
- 插件**不会执行**来自论坛的任何指令：论坛只负责把公告/广播推给游戏，游戏侧只做展示。

## 源码结构

```
src/main/java/cn/stalir/mcbridge/           共享核心（零平台引用，JDK + Gson）
├── BridgeCore.java        公告编排 + 全部命令文案渲染 + 举报流程
├── Platform.java          平台 SPI（调度、数据目录、输出与展示形式）
├── Message.java           平台无关消息（legacy & 码；由各平台转成自己的组件）
├── DisplayChannel.java    公告展示通道（chat / actionbar / title / bossbar）
├── ChatLog.java           最近公开聊天的有界环形缓冲（举报上下文用）
├── BridgeConfig.java      配置读取与校验
├── BridgeException.java   协议层异常（含 HTTP 状态码）
├── Yaml.java              极简 YAML 读取器（避免在 jar 里塞第三方库）
├── Signature.java         HMAC-SHA256 签名
├── HttpBridgeClient.java  HTTP 客户端
├── Log.java               日志 SPI
├── Messages.java          语言文件加载与 {占位符} 渲染 → Message
└── Version.java           版本常量（HTTP User-Agent 共用）

src/paper/java/cn/stalir/mcbridge/paper/    Paper + Folia
├── McBridgePlugin.java    入口（薄封装）
├── PaperPlatform.java     Folia / Bukkit 两套调度 + 玩家投递（含 title/bossbar）
├── AdventureMessages.java Message → Adventure Component（唯一的转换点）
├── PlayerListener.java    进服时提示未绑定
├── ChatListener.java      记录公开聊天（AsyncChatEvent）
├── PlaceholderApiHook.java 可选 PAPI 展开（全 jar 唯一提到 PAPI 的类）
├── BindCommand.java       /bind
├── ReportCommand.java     /report、/report status
└── BridgeCommand.java     /mcbridge

neoforge/src/main/java/cn/stalir/mcbridge/neoforge/   NeoForge 21.1.x
├── McBridgeMod.java            入口（@Mod("mc_bridge")，事件总线注册，含聊天监听）
├── NeoForgePlatform.java       MinecraftServer#execute 调度 + 玩家投递（含 title/bossbar）
├── NeoForgeMessages.java       Message → net.minecraft.network.chat.Component
├── NeoForgeLog.java            SLF4J 适配
├── BindCommand.java            /bind（Brigadier）
├── ReportCommand.java          /report、/report status（Brigadier）
└── BridgeCommand.java          /mcbridge（Brigadier）
```

## 开发

本机没有 JDK 时仍可做静态一致性校验（含三平台结构与版本一致性检查）：

```bash
node ../tools/verify.mjs
```
