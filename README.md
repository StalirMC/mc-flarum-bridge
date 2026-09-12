# MC  Flarum Bridge

[![CI](https://github.com/StalirMC/mc-flarum-bridge/actions/workflows/ci.yml/badge.svg)](https://github.com/StalirMC/mc-flarum-bridge/actions/workflows/ci.yml)

一套把 Minecraft 服务器与 Flarum 论坛双向打通的开源方案。

> **仓库**：https://github.com/StalirMC/mc-flarum-bridge
> **最新 CI**：[三个 job（静态+协议 / PHP lint / Gradle 编译）全部通过](https://github.com/StalirMC/mc-flarum-bridge/actions)

| 目录 | 组件 | 技术栈 |
|------|------|--------|
| `flarum-extension/` | Flarum 扩展：安全 REST API、数据存储、公告推送队列 | PHP 8.1+ / Flarum 2.x |
| `mc-plugin/` | Paper 插件：状态上报、事件上报、公告拉取、账号绑定 | Java 21 / Paper 1.21.x |
| `protocol/` | 双方共享的线上协议契约与 JSON Schema | Markdown / JSON Schema |
| `docs/` | 部署指南、API 参考与[验证报告](docs/VERIFICATION.md) | Markdown |
| `tools/` | 静态一致性校验、模拟论坛、协议一致性测试 | Node.js |

## 功能

- **服务器状态互通** — 插件定时上报在线玩家数、TPS、MSPT、版本、MOTD、在线名单；论坛可通过公开 API 展示。
- **游戏事件上报** — 进服 / 退服 / 死亡 / 成就批量写入论坛，含离线缓冲与有界队列。
- **公告推送** — 论坛中指定标签（或全部）的新讨论自动入队，插件轮询后在游戏内广播。
- **论坛 → 游戏广播** — 管理员从论坛推送一条消息，游戏内全员显示。
- **远程指令（默认关闭）** — 需显式开启并由正则白名单约束。
- **账号绑定** — 游戏内 `/bind` 拿一次性绑定码，论坛端消费后建立 `论坛账号 ↔ MC UUID` 双向唯一映射。
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
#    声明为扩展，因此整个仓库可作为单个包安装：
cd /path/to/flarum
composer config repositories.mc-bridge vcs https://github.com/StalirMC/mc-flarum-bridge
composer require stalir/mc-flarum-bridge:dev-main
#    没有 SSH？后台 Extension Manager → 仓库 → 添加 vcs 仓库后，
#    在「安装一个新的扩展程序」填 stalir/mc-flarum-bridge:dev-main
php flarum migrate
php flarum extension:enable stalir/mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear
php flarum mc-bridge:secret          # 生成共享密钥，填到插件配置里
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn   # 论坛侧全链路自检

# 2) 游戏侧
cd mc-plugin && gradle wrapper --gradle-version 8.10   # 首次需生成 wrapper
./gradlew build
#    复制 build/libs/McBridge-1.0.0.jar 到 server/plugins/
#    编辑 plugins/McBridge/config.yml 填入论坛地址与密钥
#    执行 /mcbridge reload
```

详见 [`docs/README.md`](docs/README.md)，端点细节见 [`docs/API.md`](docs/API.md)。

## 协议

```
┌──────────────┐   HMAC-SHA256   ┌──────────────────────┐
│  MC Plugin   │ ──────────────► │  Flarum Extension    │
│  (Paper)     │  heartbeat      │  /api/mc-bridge/*    │
│              │  events         │                      │
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

- Minecraft：Paper 1.21.x（`-PpaperApiVersion=` 可覆盖）
- Flarum：2.x（PHP 8.1+）
- Java：21
