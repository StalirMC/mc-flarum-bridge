# MC Bridge API 参考

基址：`https://<forum>`，所有端点在 `/api/mc-bridge` 前缀下。

认证方式见 [`../protocol/README.md`](../protocol/README.md)。标 **HMAC** 的端点需要
`X-MC-Timestamp` / `X-MC-Nonce` / `X-MC-Signature` 三个请求头；标 **会话** 的端点
需要 Flarum 登录 Cookie。

所有响应中的 `error` 文本都由语言包渲染（默认简体中文），可用
`php flarum mc-bridge:config --locale=en` 切换为英文。错误**结构**不变，只有文案
随语言变化。

## CSRF 行为（重要）

Flarum 对整个 `api` 中间件栈强制校验 CSRF，而服务器没有 session，因此扩展注册了
一个中间件，**对携带 `X-MC-Signature` 头的 `/api/mc-bridge/*` 请求**放行 CSRF 校验
（这类请求由 HMAC 认证）。由此产生的约定：

| 调用方 | 需要什么 |
|--------|---------|
| Minecraft 插件 / 自动化脚本（带签名头） | 只需 HMAC 签名，**不需要** CSRF token |
| 浏览器前端（Flarum 页面内） | 自动携带 CSRF token，无需额外处理 |
| curl 调用**会话**端点（`POST`/`DELETE /link`、管理员的 `POST /broadcast`） | 除 Cookie 外还需 `X-CSRF-Token` 头 |

用 curl 调用会话端点时，从 `XSRF-TOKEN` Cookie 取值放进 `X-CSRF-Token` 头：

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/link \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"code":"K7MPQ2XY"}'
```

> 该放行**仅**作用于带签名头的请求：会话端点的 CSRF 保护依然生效，浏览器伪造请求
> 无法带上自定义头，因此不存在 CSRF 缺口。

## 后台界面

目前**没有**管理后台 UI。`mc-bridge.*` 设置项通过控制台命令管理：

```bash
php flarum mc-bridge:secret                # 生成/查看密钥
php flarum mc-bridge:config --tags=1,3     # 公告标签过滤
php flarum mc-bridge:selftest --url=...    # 全链路自检
```

`locale/en.yml` 中的文案已为将来的后台设置页预留。

---

## POST /api/mc-bridge/heartbeat

**认证：HMAC** — 上报服务器状态。建议每 30 秒一次。

请求：

```json
{
  "server_key": "survival",
  "online": true,
  "name": "Stalir 生存服",
  "version": "Paper 1.21.1",
  "motd": "A Minecraft Server",
  "players_online": 3,
  "players_max": 40,
  "tps": 19.97,
  "mspt": 12.4,
  "player_names": ["Alice", "Bob", "Carol"]
}
```

`server_key` 必填；其余字段可选且会被忽略未知字段。

响应 `200`：

```json
{
  "ok": true,
  "server": {
    "server_key": "survival",
    "online": true,
    "players_online": 3,
    "players_max": 40,
    "tps": 19.97,
    "mspt": 12.4,
    "version": "Paper 1.21.1",
    "motd": "A Minecraft Server",
    "player_names": ["Alice", "Bob", "Carol"],
    "last_heartbeat_at": "2026-01-01T12:00:00+00:00"
  },
  "pending_messages": 0,
  "server_time": "2026-01-01T12:00:00+00:00"
}
```

`online` 在响应中表示“心跳新鲜且未主动下线”：超过 120 秒无心跳即视为离线。

---

## POST /api/mc-bridge/events

**认证：HMAC** — 批量上报游戏事件。

请求：

```json
{
  "server_key": "survival",
  "events": [
    {
      "type": "join",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Alice",
      "happened_at": "2026-01-01T12:00:00Z"
    },
    {
      "type": "death",
      "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "player_name": "Alice",
      "message": "cause=FALL world=world"
    }
  ]
}
```

- 单次最多 **100** 条。
- `type` 只接受：`join` `quit` `death` `advancement` `chat` `command` `start` `stop` `custom`。
- 也可以只发一个事件对象（不带 `events` 数组），服务端会自动包装。

响应 `201`：

```json
{ "ok": true, "stored": 2, "rejected": [] }
```

部分失败时仍返回 `201`，`rejected` 列出被拒条目：

```json
{
  "ok": true,
  "stored": 1,
  "rejected": [{ "index": 1, "reason": "Unsupported event type." }]
}
```

全部失败返回 `422`。

---

## GET /api/mc-bridge/outbox

**认证：HMAC** — 拉取待投递消息（公告 / 广播 / 指令）。

查询参数：

| 参数 | 默认 | 说明 |
|------|------|------|
| `server_key` | — | 必填（也可用 `X-MC-Server` 头） |
| `limit` | 20 | 1–100 |
| `peek` | false | `true` 时只读不消费 |

响应 `200`：

```json
{
  "ok": true,
  "server_key": "survival",
  "peek": false,
  "messages": [
    {
      "id": 12,
      "type": "announcement",
      "title": "服务器更名公告",
      "body": "随着模组服二周目的开启，我们决定更名…",
      "url": "/d/50",
      "payload": { "discussion_id": 50, "post_id": 210, "author": "kxkl2024", "is_op": true },
      "created_at": "2026-01-01T11:59:00+00:00"
    }
  ]
}
```

除非 `peek=true`，返回的消息会被标记为已投递，下次不再返回。

> `/api/mc-bridge/announcements` 是同一端点的别名。

---

## POST /api/mc-bridge/bind/start

**认证：HMAC** — 为玩家签发一次性绑定码（游戏内 `/bind` 调用）。

请求：

```json
{
  "server_key": "survival",
  "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "player_name": "Alice"
}
```

响应 `201`：

```json
{
  "ok": true,
  "already_bound": false,
  "code": "K7MPQ2XY",
  "expires_at": "2026-01-01T12:10:00+00:00",
  "expires_in_seconds": 600,
  "link_url": "/settings"
}
```

已绑定时返回 `200`：

```json
{
  "ok": true,
  "already_bound": true,
  "binding": {
    "user_id": 5,
    "username": "Alice",
    "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "player_name": "Alice",
    "server_key": "survival",
    "linked_at": "2025-12-01T09:00:00+00:00"
  }
}
```

同一玩家重复调用会作废上一个未使用的码。绑定码字符集为
`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`（去掉了易混淆的 `0 O 1 I`），长度 8。

---

## GET /api/mc-bridge/bind/status

**认证：HMAC** — 查询某个 UUID 的绑定状态。

| 参数 | 说明 |
|------|------|
| `server_key` | 必填 |
| `uuid` | 玩家 UUID（带或不带连字符均可） |

响应 `200`：

```json
{
  "ok": true,
  "bound": true,
  "binding": { "user_id": 5, "username": "Alice", "player_uuid": "…", "player_name": "Alice", "server_key": "survival", "linked_at": "…" }
}
```

未绑定时：

```json
{ "ok": true, "bound": false, "pending_code": "K7MPQ2XY", "expires_at": "2026-01-01T12:10:00+00:00" }
```

---

## POST /api/mc-bridge/broadcast

**认证：HMAC 或管理员会话** — 向一台或所有服务器投递一条消息。

请求：

```json
{
  "server_key": "survival",
  "type": "broadcast",
  "title": "维护通知",
  "body": "今晚 23:00 重启",
  "url": "/d/66",
  "payload": {}
}
```

- `server_key` 省略（或 `null`）时投递给**所有**服务器。
- `type` 可为 `broadcast` / `announcement` / `command`。
- `title` 与 `body` 至少提供一个。
- `type=command` 时，指令放在 `payload.command`；接收端需在白名单内才会执行。

响应 `201`：

```json
{ "ok": true, "message": { "id": 13, "type": "broadcast", "title": "维护通知", "body": "今晚 23:00 重启", "url": "/d/66", "payload": {}, "created_at": "…" } }
```

非管理员且无签名 → `403`。

---

## GET /api/mc-bridge/status

**认证：公开** — 供论坛前端渲染服务器小组件的只读快照。

响应 `200`：

```json
{
  "ok": true,
  "totals": { "servers": 2, "servers_online": 1, "players_online": 3 },
  "servers": [
    {
      "server_key": "survival",
      "online": true,
      "players_online": 3,
      "players_max": 40,
      "tps": 19.97,
      "mspt": 12.4,
      "version": "Paper 1.21.1",
      "motd": "A Minecraft Server",
      "player_names": ["Alice", "Bob", "Carol"],
      "last_heartbeat_at": "2026-01-01T12:00:00+00:00"
    }
  ],
  "recent_events": [
    { "id": 88, "server_key": "survival", "type": "join", "player_uuid": "…", "player_name": "Alice", "message": null, "happened_at": "2026-01-01T11:59:30+00:00" }
  ]
}
```

仅暴露聚合信息与在线玩家名，不含任何密钥。

---

## POST /api/mc-bridge/link

**认证：论坛会话（需登录）** — 消费绑定码，把游戏账号关联到当前论坛账号。

请求：

```json
{ "code": "K7MPQ2XY" }
```

响应 `201`：

```json
{ "ok": true, "binding": { "user_id": 5, "username": "Alice", "player_uuid": "…", "player_name": "Alice", "server_key": "survival", "linked_at": "…" } }
```

错误：

| 状态 | 场景 |
|------|------|
| `404` | 绑定码不存在或已被使用 |
| `409` | 该码已被使用 / 论坛账号已绑定其他游戏账号 / 游戏账号已绑定其他论坛账号 |
| `410` | 绑定码已过期 |
| `422` | 格式不是 8 位大写字母数字 |

---

## DELETE /api/mc-bridge/link

**认证：论坛会话（需登录）** — 解除当前论坛账号的绑定。

响应 `200`：`{ "ok": true }`；未绑定时 `404`。

---

## 数据表

| 表 | 用途 |
|----|------|
| `mc_servers` | 每台服务器一行，最新心跳状态 |
| `mc_events` | 游戏事件流水，`(server_key, created_at)` 与 `player_uuid` 建索引 |
| `mc_outbox` | 待投递给游戏的队列，`delivered_at` 为 NULL 表示未投递 |
| `mc_bindings` | 已确认的 `user_id ↔ player_uuid` 绑定（两侧均唯一） |
| `mc_bind_codes` | 一次性绑定码，含过期与使用时间 |

## 控制台命令

| 命令 | 说明 |
|------|------|
| `php flarum mc-bridge:secret` | 生成并保存新的共享密钥 |
| `php flarum mc-bridge:secret --show` | 打印当前密钥 |
| `php flarum mc-bridge:secret <值>` | 写入指定密钥（至少 32 字符） |
| `php flarum mc-bridge:config --show` | 查看公告标签过滤、回复同步、保留天数、输出语言 |
| `php flarum mc-bridge:config --locale=en` | 切换输出语言（默认 `zh-Hans`，可选 `en`） |
| `php flarum mc-bridge:config --tags=1,3` | 只把标签 1、3 的新讨论推送到游戏（空值 = 全部） |
| `php flarum mc-bridge:config --sync-replies=1` | 连回复也推送 |
| `php flarum mc-bridge:selftest` | 离线自检：密钥、HMAC、表结构、查询、绑定码格式 |
| `php flarum mc-bridge:selftest --url=https://your.forum` | 追加一次真实的带签名 HTTP 回环请求 |
