# MC Bridge — Flarum 扩展

把 Flarum 变成 Minecraft 服务器的控制面：接收服务器状态与游戏事件，并把论坛
公告、广播和（可选）指令投递给游戏。

- 目标 Flarum：**2.x**（PHP 8.1+）
- 包名：`stalirmc/mc-bridge`
- 认证：HMAC-SHA256 + 时间戳 + 一次性 nonce（见 [`../protocol/README.md`](../protocol/README.md)）

## 安装

> ⚠️ **Flarum 2.x 没有 `extensions/` 目录。** 它只从 Composer 的
> `vendor/composer/installed.json` 发现扩展，所以扩展**必须经由 Composer 安装**。
> 详见 [`../docs/README.md`](../docs/README.md) 第 1 节（含源码依据与三种方式）。

**方式 A — 后台安装（推荐）**：Extension Manager → 仓库 → 添加 `vcs` 仓库
`https://github.com/StalirMC/mc-flarum-bridge` → 安装
`stalirmc/mc-flarum-bridge:dev-main` → 启用「MC Bridge」。

**方式 B — SSH / Composer**：

```bash
cd /path/to/flarum
composer config repositories.mc-bridge vcs https://github.com/StalirMC/mc-flarum-bridge
composer require stalirmc/mc-flarum-bridge:dev-main
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear
```

**方式 C — 不上 GitHub**：把**本目录**（`flarum-extension/`）上传到
`<flarum>/packages/mc-bridge/`，加 `path` 仓库后安装 `stalirmc/mc-bridge:dev-main`。

装好后：

```bash
php flarum mc-bridge:secret                    # 生成插件要用的共享密钥
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn   # 全链路自检
```

完整步骤见 [`../docs/README.md`](../docs/README.md)。

## 为什么仓库根目录有 composer.json

因为它用 Flarum 2.x 的 `extra.flarum-subextensions` 把本目录声明为扩展，使
**整个 monorepo 能作为一个 Composer 包安装**：

```json
{
  "autoload": { "psr-4": { "Stalir\\McBridge\\": "flarum-extension/src/" } },
  "extra": { "flarum-subextensions": ["flarum-extension"] }
}
```

`ExtensionManager::subExtensionConfsFromJson()` 会读取该字段。autoload 必须写在
根 `composer.json` 里——Composer 不处理子包自己的 `autoload`，Flarum 也不会替
扩展注册命名空间。

## 组成

```
extend.php                      路由 / 事件 / 控制台命令注册
migrations/                     5 张表的建表迁移
src/Service/BridgeCrypto.php    HMAC 签名与路径规范化
src/Service/BridgeMessages.php  语言解析与翻译包装（默认中文）
locale/                         语言文件：zh-Hans（默认）、en
src/Api/Controller/             8 个控制器（1 个抽象基类 + 7 个端点控制器，共 10 条路由）
extend.php                      路由 / CSRF 豁免 / 事件 / 语言 / 控制台命令注册
src/Model/                      Eloquent 模型
src/Listener/QueueAnnouncement.php   新帖 → outbox 队列
src/Console/SecretCommand.php   php flarum mc-bridge:secret
src/Console/ConfigCommand.php   php flarum mc-bridge:config --tags=1,3
src/Console/SelfTestCommand.php php flarum mc-bridge:selftest
```

## 端点

机器接口（HMAC）：`heartbeat`、`events`、`outbox`、`bind/start`、`bind/status`、`broadcast`。
论坛接口（会话）：`status`（公开）、`link`（POST/DELETE）。

字段与时序见 [`../docs/API.md`](../docs/API.md)。

## 多语言

语言文件在 `locale/`，随扩展附带：

| 文件 | 语言 |
|------|------|
| `locale/zh-Hans.yml` | 简体中文（默认，110 个键） |
| `locale/en.yml` | English |

**必须在 `extend.php` 里显式注册**，Flarum 不会自动扫描扩展的 locale 目录：

```php
new Extend\Locales(__DIR__.'/locale'),
```

切换语言（控制台命令与接口错误的语言）：

```bash
php flarum mc-bridge:config --locale=en      # 或 --locale=zh-Hans
php flarum mc-bridge:config --show           # 查看当前语言
```

`mc-bridge.locale` 的默认值是 `zh-Hans`，与论坛自身的 `default_locale`
**无关** —— 所以即使论坛默认是英文，扩展的输出默认仍是中文。

新增语言：把 `locale/zh-Hans.yml` 复制为 `locale/ja.yml` 并翻译，然后用
`--locale=ja` 切换。缺键会回退到 Flarum 的 fallback（`en`），因此建议保持
各语言键集一致（校验脚本会检查这一点）。

## 设置项

| 键 | 默认 | 说明 |
|----|------|------|
| `mc-bridge.secret` | 空 | 共享密钥，由控制台命令写入 |
| `mc-bridge.locale` | `zh-Hans` | 输出语言，见上文「多语言」 |
| `mc-bridge.announcement_tag_ids` | 空 | 逗号分隔的标签 ID；空表示同步所有新讨论 |
| `mc-bridge.sync_replies` | `0` | 设为 `1` 时连回复也推送到游戏 |
| `mc-bridge.max_announcement_age_days` | `30` | 预留：outbox 清理窗口 |

用 `php flarum mc-bridge:config` 查看与修改这些设置，无需手写 tinker 代码。

## 数据表

`mc_servers`、`mc_events`、`mc_outbox`、`mc_bindings`、`mc_bind_codes`。

## 权限与安全

- 所有机器接口都要求合法签名；未配置密钥时返回 `503` 而不是放行。
- nonce 单次有效（缓存 600 秒），时间戳允许 ±300 秒偏差。
- 绑定码一次性、10 分钟过期，字符集去掉了易混淆字符。
- `broadcast` 端点对会话调用方要求管理员身份。
- 公开的 `status` 端点只暴露聚合数据与在线玩家名，不含密钥。

## 开发

```bash
# 语法检查（需要本机有 PHP）
find . -name '*.php' -not -path './vendor/*' -exec php -l {} \;
```

本仓库还提供了一个不依赖 PHP 的静态一致性校验脚本：

```bash
node ../tools/verify.mjs
```
