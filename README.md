# MC  Flarum Bridge

[![CI](https://github.com/StalirMC/mc-flarum-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/StalirMC/mc-flarum-bridge/actions/workflows/ci.yml)
[![Packagist](https://img.shields.io/packagist/v/stalirmc/mc-flarum-bridge.svg)](https://packagist.org/packages/stalirmc/mc-flarum-bridge)
[![Downloads](https://img.shields.io/packagist/dt/stalirmc/mc-flarum-bridge.svg)](https://packagist.org/packages/stalirmc/mc-flarum-bridge)

一套把 Minecraft 服务器与 Flarum 论坛双向打通的开源方案。

---

> ## ⚠️ 本项目仍在施工阶段
>
> **可用性尚未验证，请勿直接用于生产环境。** 接口、配置项与数据表结构都可能随时变动。
>
> **已经确认的部分**
> - CI 全绿：`gradle build` 真机编译通过、`php -l` 全部通过、384 项静态一致性检查 + 45 项协议一致性测试通过
> - **本地真实构建也通过**（JDK 21 + Gradle 8.10）：Paper 插件与 NeoForge 模组两个工程都编译成功、
>   `verifyJar` 内容断言通过、**共享核心的 68 项运行时自测在真实 JVM 上全通过**（YAML 解析 / 语言回退 / 配置校验 / 正则白名单 / 展示通道解析 / 聊天缓冲边界）
> - **插件已在真实 Paper 服务端成功启用**：数据目录 `plugins\McBridge\`、`config.yml` 与
>   `lang/*.yml` 的解压路径都由服务端日志证实
> - 在真实论坛（Flarum 2.0.0-rc.8）上完成过：安装、`migrate`、启用扩展、自检命令运行
> - 两个发行包的**构建产物与内容断言**已实测：Paper 插件 jar（`plugin.yml` + Paper 入口类，
>   共享层零平台引用、jar 内无第三方代码）与 NeoForge 模组 jar（`META-INF/neoforge.mods.toml` + `@Mod` 入口类）
> - **NeoForge 侧用真实工具链编译通过**：ModDevGradle 拉取 Minecraft 1.21.1 与 NeoForge 21.1.100，
>   完成 NeoForm 反编译 → 打补丁 → 重编译，模组源码编译成功并产出可安装的模组 jar
> - 已逐项打通并修复：CSRF 豁免（`Extend\Csrf`）、HMAC 签名链路、请求体读取、批量赋值、
>   论坛端启动崩溃（设置页属于懒加载 chunk，必须按模块路径 `extend`）、
>   后台端启动崩溃（2.x 用 `app.registry` 取代了 `app.extensionData`）、
>   每次启动的无意义警告、**绑定后论坛仍显示「未绑定」（GET 路由漏注册）、
>   版本号提升后打进 jar 的仍是旧版本**（`expand` 未声明为 task input）——
>   详见 [VERIFICATION.md](docs/VERIFICATION.md) 2.5 / 2.6 / 2.8 / 2.9
>
> **尚未验证的部分**
> - **只有 Paper 被真实加载过**：Folia 与 NeoForge 仍只到「编译 + 静态断言」为止，从未在真实服务端启动
> - **公告推送、广播的端到端效果未验证**（插件能启用，但没有观察到数据真正
>   出现在论坛上）；账号绑定此前因缺陷 2 不可见，0.0.2 修复后需重新实测
> - 前端设置页区块（`js/dist`）**尚未在浏览器里确认可用**：导致启动崩溃的根因已定位并修复，
>   但仍需刷新论坛页面实测绑定码输入框是否正常显示
> - 长期稳定性、并发与多服务器场景均未验证
>
> 欢迎试用并反馈问题，但请自行评估风险。

---

> **仓库**：https://github.com/StalirMC/mc-flarum-bridge
> **最新 CI**：[三个 job（静态+协议 / PHP lint / Gradle 编译）全部通过](https://github.com/StalirMC/mc-flarum-bridge/actions)

| 目录 | 组件 | 技术栈 |
|------|------|--------|
| `flarum-extension/` | Flarum 扩展：安全 REST API、数据存储、公告推送队列 | PHP 8.1+ / Flarum 2.x |
| `mc-plugin/` | Minecraft 侧（**两个发行包**）：公告拉取、广播、账号绑定、举报 | Java 17 核心字节码 / Paper 1.21.x · Folia（`plugins/`）；NeoForge 21.1.x + Minecraft 1.21.1（`mods/`） |
| `protocol/` | 双方共享的线上协议契约与 JSON Schema | Markdown / JSON Schema |
| `docs/` | [部署指南](docs/README.md)、[API 参考](docs/API.md)、[验证报告](docs/VERIFICATION.md)、[发布流程](docs/RELEASING.md)（维护者） | Markdown |
| `tools/` | 静态一致性校验、模拟论坛、协议一致性测试 | Node.js |

## 功能

- **公告推送** — 论坛中指定标签（或全部）的新讨论自动入队，插件轮询后在游戏内广播。
  **展示形式可多选**（`game.announce-display`）：`chat` 聊天行、`actionbar` 动作栏、
  `title` 大标题（正文变副标题）、`bossbar` 顶部血条，可以同时开多个，
  例如 `"chat,bossbar"`。标题与血条的停留时长各自可配。
- **论坛 → 游戏广播** — 管理员从论坛推送一条消息，游戏内全员显示。
- **游戏内查公告** — 玩家在游戏内输入 `/mcbridge news` 查看论坛最新公告（无需管理员权限）。
- **账号绑定** — 游戏内 `/bind` 拿一次性绑定码，论坛端消费后建立 `论坛账号 ↔ MC UUID` 双向唯一映射。
- **论坛侧展示绑定** — 个人资料页显示已绑定的 MC 账号；绑定的账号会得到一个**草方块徽章**
  （挂 `User.badges()`，所以帖子作者、用户卡片、资料页三处同时出现，并由主题统一样式化；
  玩家名在悬浮提示里）。**仅登录用户可见**，游客拿到的资源里根本不带这些字段。
- **进服绑定引导** — 未绑定的玩家进服时会被提示一次怎么绑定（`game.prompt-unbound`，默认开启）；
  论坛不可达时保持沉默，不会变成每次进服都刷屏。
- **游戏内举报** — 玩家在游戏内输入 `/report <玩家> <原因>` 举报其他玩家。举报除了在 `mc_reports`
  留档，还会**在论坛自动发成一条讨论**并挂上举报标签（**可挂多个**，例如「举报 + 待处理」），
  管理员打开标签即可处理，不必查数据库。
  标题、标签、发布账号都可以自定义，且**两侧都能配**：游戏侧 `config.yml` 优先，论坛侧设置兜底。
  标题模板支持 **PlaceholderAPI**（`%player_name%` 等，以举报人身份展开），
  因为标题是在游戏内渲染好再发给论坛的。
  **举报讨论永远不会被广播回游戏**。
- **举报带上下文** — 提交举报时会附带**被举报玩家本人**最近的公开聊天记录
  （`report.chat-context-lines`，默认 10 条，0 关闭），管理员看得见当事人到底说了什么，
  而不只是举报人的转述。只采集公开聊天：**私聊与命令从不进入缓冲区**，缓冲区按玩家与条数双向封顶，
  且这份记录只在**该玩家被举报时**发给论坛，绝不会被广播到游戏里。
- **举报进度可查** — 玩家用 `/report status` 查看自己提交过的举报及其状态
  （处理中 / 已处理 / 已驳回）。接口按举报人 UUID + 服务器过滤，只能看到自己的。
- **处理结果回执** — 管理员把举报讨论移进「已处理」/「已驳回」标签
  （`--report-resolved-tags` / `--report-rejected-tags`），或执行
  `php flarum mc-bridge:report <编号> --status=resolved`，举报人就会**在游戏内收到通知**。
  两条路径共用同一套逻辑，所以**重复打标签或重复执行命令只会通知一次**。
- **多语言** — 论坛侧（`locale/`）与游戏侧（`lang/`）各自独立，**默认均为简体中文**；缺键自动回退中文，不会暴露原始键名。

## 安全设计

- 全部机器接口使用 **HMAC-SHA256** 签名：`{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}`。
- 时间戳允许 ±300 秒偏差，nonce 单次有效（缓存 600 秒），签名比较使用常量时间比较。
- 签名路径从 `/api/mc-bridge` 起算，因此 Flarum 装在子目录也无需额外配置。
- 论坛未配置密钥时机器接口返回 `503` 而不是放行。
- 举报进度接口按**举报人 UUID + 服务器**过滤：玩家只能读到自己提交的举报，看不到别人举报过谁。
- 聊天上下文只采集公开聊天（私聊与命令从不进入缓冲区），按玩家与条数封顶，且仅在举报时随举报发送。
- 绑定码一次性、10 分钟过期、字符集剔除易混淆字符。

## 快速开始


### 1.论坛侧

声明为扩展，因此整个仓库可作为单个包安装，且已上架 Packagist：

https://packagist.org/packages/stalirmc/mc-flarum-bridge
```bash
composer require stalirmc/mc-flarum-bridge
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear
php flarum assets:publish            # 扩展前端 bundle 变了就要跑这一步
php flarum mc-bridge:secret          # 生成共享密钥，填到插件配置里
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn   # 论坛侧全链路自检
```

### 2.游戏侧（两个发行包，按服务端类型选一个）

[https://github.com/StalirMC/mc-flarum-bridge/releases](https://github.com/StalirMC/mc-flarum-bridge/releases)

自己下


## 关于构建
```bash
cd mc-plugin && gradle build
```
Paper/Folia：复制 build/libs/McBridge-<版本>.jar 到 server/plugins/
             编辑 plugins/McBridge/config.yml
NeoForge   ：复制 neoforge/build/libs/McBridge-neoforge-<版本>.jar 到 server/mods/
             编辑 config/mc-bridge/config.yml
填入论坛地址与密钥，然后 /mcbridge reload

首次构建 NeoForge 模组要下载 Minecraft 1.21.1 并跑一遍 NeoForm
（反编译 → 打补丁 → 重编译），约 5-10 分钟且需要网络；之后有缓存。
只想要 Paper 插件时可以只跑：gradle :build


详见 [`docs/README.md`](docs/README.md)，端点细节见 [`docs/API.md`](docs/API.md)。

## 两个发行包

`mc-plugin` 是一个多工程构建，产出**两个 jar**，共享同一份核心源码：

```
mc-plugin/build/libs/McBridge-<版本>.jar              ← 放进 Paper / Folia 的 plugins/
├── plugin.yml                 ← Paper / Folia 读它，folia-supported: true
├── config.yml, lang/*.yml
└── cn/stalir/mcbridge/
    ├── (共享核心)              ← 只依赖 JDK + Gson，零平台引用
    └── paper/                 ← PaperPlatform 同时走 Folia 与 Bukkit 两套调度

mc-plugin/neoforge/build/libs/McBridge-neoforge-<版本>.jar   ← 放进 NeoForge 的 mods/
├── META-INF/neoforge.mods.toml ← NeoForge 读它（side = SERVER）
├── config.yml, lang/*.yml
└── cn/stalir/mcbridge/
    ├── (共享核心)              ← 与上面逐字节相同
    └── neoforge/              ← @Mod 入口、NeoForgePlatform、Brigadier 命令
```

两个 jar 各自独立、互不依赖，各平台只加载自己描述符里的入口类。
共享核心（公告渲染、全部命令文案）完全一致，因此三平台的协议行为逐字节相同。

**共享核心不依赖 Adventure**：Paper 自带 Adventure，而 Minecraft 1.21.1（因此 NeoForge）
不带它。核心渲染自己的 `cn.stalir.mcbridge.Message`，由各平台在投递时转成自己的
组件类型（Paper → Adventure，NeoForge → `net.minecraft.network.chat.Component`）。

## 协议

```
┌──────────────┐   HMAC-SHA256   ┌──────────────────────┐
│  MC 服务端    │ ──────────────► │  Flarum Extension    │
│ Paper/Folia  │  outbox         │  /api/mc-bridge/*    │
│  NeoForge    │  events         │                      │
│              │  bind/start     │  mc_servers          │
│              │  bind/status    │  mc_events           │
│              │ ◄────────────── │  mc_outbox           │
│              │  outbox         │  mc_bindings         │
└──────────────┘                 └──────────────────────┘
```

完整规范（含 Bash / PHP 签名示例）：[`protocol/README.md`](protocol/README.md)。

## 验证

本仓库自带一套不依赖 PHP/JDK 的校验工具：

```bash
cd mc-flarum-bridge

# 静态一致性校验：PSR-4 布局、类引用可解析、Java 包结构、
# plugin.yml 与代码一致、config.yml 键与消息键齐全、
# PHP 与 Java 的 HMAC 规范字符串一致、迁移表与模型对应、路由均有文档
node tools/verify.mjs

# 协议一致性测试：拉起一个模拟 Flarum，验证签名/重放/时间戳/
# outbox 消费语义/绑定码字符集
node tools/protocol-test.mjs
```

有工具链时可另外执行：

```bash
find flarum-extension -name '*.php' -exec php -l {} \;   # PHP 语法
cd mc-plugin && gradle build                                # Java 编译（Paper 插件 + NeoForge 模组）
```

> 本机若没有 PHP / JDK，把仓库推到 GitHub 即可：`.github/workflows/ci.yml`
> 会在带 PHP 8.3 + JDK 21 的环境里自动跑 `php -l`、真实 `gradle build` 与上述
> 两套测试，并上传编译好的插件 jar 与模组 jar。

## 版本支持

| 平台 | 支持情况 | 说明 |
|------|----------|------|
| **Paper** 1.21.x | ✅ 完整功能 | 主目标平台，已在真实服务端启用 |
| **Folia** 1.21.x | ✅ 编译 + 断言 | 用 Folia 的 `AsyncScheduler` / `GlobalRegionScheduler` 调度，`plugin.yml` 声明 `folia-supported: true`；尚未在真实 Folia 上启动过 |
| **NeoForge** 21.1.x（Minecraft 1.21.1） | ✅ 编译 + 断言 | 独立模组 jar，`@Mod("mc_bridge")` 入口、Brigadier 命令、`META-INF/neoforge.mods.toml`；仅服务端需要（`side = SERVER`），客户端不必安装 |

- Minecraft：Paper / Folia 1.21.x（`-PpaperApiVersion=` 可覆盖）、NeoForge 21.1.x for 1.21.1
- Flarum：2.x（PHP 8.1+）
- Java：共享核心字节码目标为 **17**（Paper/Folia 跑在 Java 21 上照常加载）；
  `paper` 与 `neoforge` 模块因各自 API 要求而目标 21；构建工具链为 JDK 21

发版由 tag 驱动：`git tag v0.0.6 && git push origin v0.0.6` 会触发
[`release.yml`](.github/workflows/release.yml)，先校验 tag 与 `gradle.properties`
中的版本一致，再构建并把**插件 jar 与模组 jar 两个文件**一起上传到 GitHub Release。
完整步骤（含 Packagist 同步与改名注意事项）见 [发布流程](docs/RELEASING.md)（维护者）。
