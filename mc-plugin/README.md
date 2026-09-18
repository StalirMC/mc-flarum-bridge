# McBridge — 通用插件（Paper / Folia / Velocity）

把 Minecraft 服务端接入 Flarum：上报状态与事件，接收论坛公告/广播，并提供游戏内
`/bind` 账号绑定。

- 目标：**Paper 1.21.x**、**Folia 1.21.x**、**Velocity 3.x**
- Java：字节码目标 **17**（构建工具链 JDK 21）。Paper/Folia 跑在 Java 21 上照常加载，
  Velocity 若还在 Java 17 也能用
- 依赖：Paper API 与 Velocity API（均为 `compileOnly`）、两端都自带的 **Gson** 与 **Adventure**
- **不需要** Shadow/relocate：jar 里不含任何第三方代码

## 一个 jar，三个平台

三个 source set 编成一个 jar：

| source set | 目录 | 内容 |
|-----------|------|------|
| `main` | `src/main/java` | 平台无关核心：协议、配置、语言文件、心跳/事件/公告编排。**零平台引用** |
| `paper` | `src/paper/java` | Paper 与 Folia 入口（`paper.yml` 形式的 `plugin.yml`） |
| `velocity` | `src/velocity/java` | Velocity 入口（`velocity-plugin.json` 由注解处理器生成） |

jar 根同时存在 `plugin.yml` 与 `velocity-plugin.json`，各平台只加载自己描述符里
写明的入口类，因此同一个文件可以直接丢进三种服务端。构建时 `verifyJar` 会校验：

- 两份描述符、两个入口类、`config.yml`、`lang/*.yml` 都在
- `plugin.yml` 的 `main:` 指向 paper 模块，且 `folia-supported: true`
- 两份描述符里的版本都与项目版本一致
- **共享层的 class 常量池里不出现 `org/bukkit/`、`com/velocitypowered/`、`io/papermc/`**

最后一条是重点：共享核心一旦引用平台类，另一平台加载时就会 `NoClassDefFoundError`。

### Folia

Folia 的调度 API（`io.papermc.paper.threadedregions.scheduler`）就在 **paper-api** 里，
所以不需要额外的 `folia-api` 依赖，也不需要反射：

- 运行时用 `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")` 判断
- regionised 时只用 `Bukkit.getAsyncScheduler()` / `Bukkit.getGlobalRegionScheduler()`
  （此时 `Bukkit.getScheduler()` 会抛 `UnsupportedOperationException`）
- 普通 Paper 上仍走经典 `BukkitScheduler`
- 玩家消息在 Folia 上通过 `Player#getScheduler()` 投递到该玩家所属的 region 线程

## 构建

本仓库不包含 Gradle wrapper 的二进制（`gradle-wrapper.jar`），需先生成一次：

```bash
cd mc-plugin

# 方式 A：已安装 Gradle（8.x）
gradle wrapper --gradle-version 8.10    # 生成 gradlew / gradlew.bat / wrapper jar
./gradlew build                         # Windows: gradlew.bat build

# 方式 B：直接用系统 Gradle，不生成 wrapper
gradle build

# 目标 Paper 版本不同时（会覆盖 gradle.properties 中的默认值）
./gradlew build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

需要 **JDK 21**（`java.toolchain` 会自动寻找；找不到时请设置 `JAVA_HOME`）。
首次构建会从 PaperMC 仓库拉取 `velocity-api`，需要网络。

产物：`build/libs/McBridge-0.0.1.jar`（`build` 依赖 `verifyJar`，内容不达标会直接失败）

## 安装

| 平台 | 放到 | 配置与语言目录 |
|------|------|----------------|
| Paper / Folia | `plugins/` | `plugins/McBridge/` |
| Velocity | `plugins/` | `plugins/mc-bridge/` |

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
```

## 平台能力对照

| 功能 | Paper / Folia | Velocity |
|------|---------------|----------|
| 心跳（在线数、版本、MOTD、在线名单） | ✅ | ✅（代理维度） |
| TPS / MSPT | ✅ | — 代理没有 tick 循环，字段省略 |
| 进服 / 退服事件 | ✅ | ✅ |
| 死亡 / 成就事件 | ✅ | — 代理看不到 |
| 论坛公告广播 | ✅ | ✅ |
| 从论坛执行远程指令 | ✅（默认关闭 + 正则白名单） | ❌ 代理没有游戏控制台，会被拒绝并记日志 |
| `/bind`、`/mcbridge` | ✅ | ✅ |

心跳的 JSON 字段在三个平台上完全一致（由共享核心生成），因此论坛侧无需区分平台；
平台名会出现在 `/mcbridge stats` 与启动日志里。

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

Velocity 端权限同样使用 `mcbridge.bind` / `mcbridge.admin`（由代理的权限插件授予）。

## 运行机制

| 任务 | 默认间隔 | 作用 |
|------|---------|------|
| 心跳 | 30s | `POST /heartbeat` 上报 TPS/MSPT/在线玩家 |
| 事件刷新 | 10s | 批量 `POST /events` |
| Outbox 轮询 | 20s | `GET /outbox` 并投递公告/广播 |

- 所有网络调用都在**异步线程**执行；消息展示与指令执行回到主线程（Folia 为 global region）。
- 事件先入本地有界队列（默认 200 条，超出丢弃最旧的），论坛不可用时不会阻塞
  服务器，也不会无限占用内存。
- 心跳失败日志每 10 次才打印一条，避免刷屏。
- 关闭服务器时会尽力发送一次 `online=false` 心跳与 `stop` 事件。

## 安全

- 每个请求都用 HMAC-SHA256 签名，包含时间戳与一次性 nonce。
- `game.allow-remote-commands` **默认关闭**；开启后只有匹配
  `game.remote-command-whitelist` 正则的指令会被执行。
- 打开远程指令等于把游戏控制台交给任何持有 secret 的人，请谨慎。
- Velocity 不支持远程指令，相关消息一律拒绝。

## 源码结构

```
src/main/java/cn/stalir/mcbridge/           共享核心（零平台引用）
├── BridgeCore.java        心跳/事件/公告/远程指令编排 + 全部命令文案渲染
├── Platform.java          平台 SPI（调度、服务器状态、输出）
├── BridgeConfig.java      配置读取与校验
├── Yaml.java              极简 YAML 读取器（避免在通用 jar 里塞第三方库）
├── Signature.java         HMAC-SHA256 签名
├── HttpBridgeClient.java  HTTP 客户端
├── EventQueue.java        有界事件队列
├── Messages.java          消息渲染（Adventure）
└── Version.java           版本常量（Velocity 注解与 User-Agent 共用）

src/paper/java/cn/stalir/mcbridge/paper/    Paper + Folia
├── McBridgePlugin.java    入口（薄封装）
├── PaperPlatform.java     Folia / Bukkit 两套调度 + 服务器状态
├── PlayerListener.java    进服/退服/死亡/成就
├── BindCommand.java       /bind
└── BridgeCommand.java     /mcbridge

src/velocity/java/cn/stalir/mcbridge/velocity/   Velocity
├── McBridgeVelocityPlugin.java   入口（@Plugin）
├── VelocityPlatform.java         代理调度与状态
├── VelocityListener.java         进服/退服
├── VelocityBindCommand.java      /bind
└── VelocityBridgeCommand.java    /mcbridge
```

## 开发

本机没有 JDK 时仍可做静态一致性校验（含三平台结构与版本一致性检查）：

```bash
node ../tools/verify.mjs
```
