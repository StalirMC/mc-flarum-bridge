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
> - CI 全绿：`gradle build` 真机编译通过、`php -l` 全部通过、298 项静态一致性检查 + 32 项协议一致性测试通过
> - **本地真实构建也通过**（JDK 21 + Gradle 8.10）：三个模块编译成功、`verifyJar` 内容断言通过、
>   **共享核心的 37 项运行时自测在真实 JVM 上全通过**（YAML 解析 / 语言回退 / 配置校验 / 正则白名单）
> - **插件已在真实 Paper 服务端成功启用**：数据目录 `plugins\McBridge\`、`config.yml` 与
>   `lang/*.yml` 的解压路径都由服务端日志证实
> - 在真实论坛（Flarum 2.0.0-rc.8）上完成过：安装、`migrate`、启用扩展、自检命令运行
> - 三平台合一 jar 的**构建产物与内容断言**已实测：`plugin.yml` + `velocity-plugin.json`
>   两个描述符、Paper/Folia 与 Velocity 两个入口类、共享层零平台引用、jar 内无第三方代码
> - 已逐项打通并修复：CSRF 豁免（`Extend\Csrf`）、HMAC 签名链路、请求体读取、批量赋值、
>   论坛端启动崩溃（设置页属于懒加载 chunk，必须按模块路径 `extend`）、
>   后台端启动崩溃（2.x 用 `app.registry` 取代了 `app.extensionData`）、
>   每次启动的无意义警告、**绑定后论坛仍显示「未绑定」（GET 路由漏注册）、
>   版本号提升后打进 jar 的仍是旧版本**（`expand` 未声明为 task input）——
>   详见 [VERIFICATION.md](docs/VERIFICATION.md) 2.5 / 2.6 / 2.8 / 2.9
>
> **尚未验证的部分**
> - **只有 Paper 被真实加载过**：Folia 与 Velocity 仍只到「编译 + 静态断言」为止，从未实机启动
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
| `mc-plugin/` | 通用插件（**一个 jar 同时支持三个平台**）：公告拉取、广播、账号绑定 | Java 17 字节码 / Paper 1.21.x · Folia · Velocity 3.x |
| `protocol/` | 双方共享的线上协议契约与 JSON Schema | Markdown / JSON Schema |
| `docs/` | [部署指南](docs/README.md)、[API 参考](docs/API.md)、[验证报告](docs/VERIFICATION.md)、[发布流程](docs/RELEASING.md)（维护者） | Markdown |
| `tools/` | 静态一致性校验、模拟论坛、协议一致性测试 | Node.js |

## 功能

- **公告推送** — 论坛中指定标签（或全部）的新讨论自动入队，插件轮询后在游戏内广播。
- **论坛 → 游戏广播** — 管理员从论坛推送一条消息，游戏内全员显示。
- **游戏内查公告** — 玩家在游戏内输入 `/mcbridge news` 查看论坛最新公告（无需管理员权限）。
- **账号绑定** — 游戏内 `/bind` 拿一次性绑定码，论坛端消费后建立 `论坛账号 ↔ MC UUID` 双向唯一映射。
- **论坛侧展示绑定** — 个人资料页显示已绑定的 MC 账号；绑定的账号会得到一个**草方块徽章**
  （挂 `User.badges()`，所以帖子作者、用户卡片、资料页三处同时出现，并由主题统一样式化；
  玩家名在悬浮提示里）。**仅登录用户可见**，游客拿到的资源里根本不带这些字段。
- **进服绑定引导** — 未绑定的玩家进服时会被提示一次怎么绑定（`game.prompt-unbound`，默认开启）；
  论坛不可达时保持沉默，不会变成每次进服都刷屏。
- **游戏内举报** — 玩家在游戏内输入 `/report <玩家> <原因>` 举报其他玩家，举报自动发送到论坛供管理员处理。
- **多语言** — 论坛侧（`locale/`）与游戏侧（`lang/`）各自独立，**默认均为简体中文**；缺键自动回退中文，不会暴露原始键名。

## 安全设计

- 全部机器接口使用 **HMAC-SHA256** 签名：`{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}`。
- 时间戳允许 ±300 秒偏差，nonce 单次有效（缓存 600 秒），签名比较使用常量时间比较。
- 签名路径从 `/api/mc-bridge` 起算，因此 Flarum 装在子目录也无需额外配置。
- 论坛未配置密钥时机器接口返回 `503` 而不是放行。
- 公开的 `status` 端点只暴露聚合数据与在线玩家名，不含密钥。
- 绑定码一次性、10 分钟过期、字符集剔除易混淆字符。

## 快速开始

```bash
# 1) 论坛侧
#    Flarum 2.x 没有 extensions/ 目录，扩展必须经 Composer 安装。
#    仓库根的 composer.json 用 flarum-subextensions 把 flarum-extension/
#    声明为扩展，因此整个仓库可作为单个包安装，且已上架 Packagist：
#    https://packagist.org/packages/stalirmc/mc-flarum-bridge
cd /path/to/flarum
composer require stalirmc/mc-flarum-bridge
#    没有 SSH？后台 Extension Manager → 安装一个新的扩展程序 →
#    填 stalirmc/mc-flarum-bridge 即可（Packagist 是默认源，无需加仓库）
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear
php flarum assets:publish            # 扩展前端 bundle 变了就要跑这一步
php flarum mc-bridge:secret          # 生成共享密钥，填到插件配置里
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn   # 论坛侧全链路自检

# 2) 游戏侧（同一个 jar 适用于 Paper / Folia / Velocity）
cd mc-plugin && gradle wrapper --gradle-version 8.10   # 首次需生成 wrapper
./gradlew build
#    Paper/Folia：复制 build/libs/McBridge-0.0.2.jar 到 server/plugins/
#    Velocity   ：复制同一个 jar 到 proxy/plugins/
#    编辑 plugins/McBridge/config.yml（Velocity 为 plugins/mc-bridge/config.yml）
#    填入论坛地址与密钥，然后 /mcbridge reload（Velocity 端为 /mcbridge reload）
```

详见 [`docs/README.md`](docs/README.md)，端点细节见 [`docs/API.md`](docs/API.md)。

## 三平台合一

`mc-plugin` 由三个 source set 编成**一个 jar**，jar 根同时放两份平台描述符：

```
McBridge-0.0.2.jar
├── plugin.yml                 ← Paper / Folia 读它，folia-supported: true
├── velocity-plugin.json       ← Velocity 读它（由 @Plugin 注解的处理器生成）
├── config.yml, lang/*.yml
└── cn/stalir/mcbridge/
    ├── (共享核心)              ← 只依赖 JDK + Adventure + Gson，零平台引用
    ├── paper/                 ← PaperPlatform 同时走 Folia 与 Bukkit 两套调度
    └── velocity/
```

各平台只加载自己描述符里写明的入口类，因此同一个文件在三种服务端上都能装。
共享核心（公告渲染、全部命令文案）完全一致，三平台的
协议行为逐字节相同。

## 协议

```
┌──────────────┐   HMAC-SHA256   ┌──────────────────────┐
│  MC Plugin   │ ──────────────► │  Flarum Extension    │
│ Paper/Folia/ │  outbox         │  /api/mc-bridge/*    │
│   Velocity   │  events         │                      │
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
cd mc-plugin && ./gradlew build                            # Java 编译（首次先 gradle wrapper）
```

> 本机若没有 PHP / JDK，把仓库推到 GitHub 即可：`.github/workflows/ci.yml`
> 会在带 PHP 8.3 + JDK 21 的环境里自动跑 `php -l`、真实 `gradle build` 与上述
> 两套测试，并上传编译好的插件 jar。

## 版本支持

| 平台 | 支持情况 | 说明 |
|------|----------|------|
| **Paper** 1.21.x | ✅ 完整功能 | 主目标平台 |
| **Folia** 1.21.x | ✅ 完整功能 | 用 Folia 的 `AsyncScheduler` / `GlobalRegionScheduler` 调度，`plugin.yml` 声明 `folia-supported: true` |
| **Velocity** 3.x | ✅ 完整功能 | 公告广播、`/bind` 与 `/mcbridge` 都可用；代理没有游戏世界，因此只有连接相关的提示 |

- Minecraft：Paper / Folia 1.21.x（`-PpaperApiVersion=` 可覆盖）、Velocity 3.x
- Flarum：2.x（PHP 8.1+）
- Java：插件字节码目标为 **17**（Paper/Folia 跑在 Java 21 上照常加载，Velocity 仍可用 Java 17）；构建工具链为 JDK 21

发版由 tag 驱动：`git tag v0.0.6 && git push origin v0.0.6` 会触发
[`release.yml`](.github/workflows/release.yml)，先校验 tag 与 `gradle.properties`
中的版本一致，再构建并把**同一个通用 jar** 上传到 GitHub Release。
完整步骤（含 Packagist 同步与改名注意事项）见 [发布流程](docs/RELEASING.md)（维护者）。
