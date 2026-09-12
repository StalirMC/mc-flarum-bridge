# MC Bridge — Flarum 扩展

把 Flarum 变成 Minecraft 服务器的控制面：接收服务器状态与游戏事件，并把论坛
公告、广播和（可选）指令投递给游戏。

- 目标 Flarum：**2.x**（PHP 8.1+）
- 包名：`stalir/mc-bridge`
- 认证：HMAC-SHA256 + 时间戳 + 一次性 nonce（见 [`../protocol/README.md`](../protocol/README.md)）

## 安装

```bash
# 1. 放到 <flarum>/extensions/mc-bridge/
# 2. 在 composer.json 里加 path 仓库
#    { "type": "path", "url": "extensions/mc-bridge", "options": { "symlink": false } }
# 3. 安装
composer require stalir/mc-bridge:'*'
php flarum migrate
php flarum extension:enable stalir/mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear

# 4. 生成插件要用的共享密钥
php flarum mc-bridge:secret

# 5. 自检（强烈建议，含一次真实的带签名回环请求）
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn
```

完整步骤见 [`../docs/README.md`](../docs/README.md)。

## 组成

```
extend.php                      路由 / 事件 / 控制台命令注册
migrations/                     5 张表的建表迁移
src/Service/BridgeCrypto.php    HMAC 签名与路径规范化
src/Api/Controller/             8 个控制器（1 个抽象基类 + 7 个端点控制器，共 10 条路由）
src/Http/Middleware/            CSRF 放行中间件（HMAC 请求免 session CSRF）
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

## 设置项

| 键 | 默认 | 说明 |
|----|------|------|
| `mc-bridge.secret` | 空 | 共享密钥，由控制台命令写入 |
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
