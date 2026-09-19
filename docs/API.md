# MC Bridge API 参考

基址：`https://<forum>`，所有端点在 `/api/mc-bridge` 前缀下。

认证方式见 [`../protocol/README.md`](../protocol/README.md)。标 **HMAC** 的端点需要
`X-MC-Timestamp` / `X-MC-Nonce` / `X-MC-Signature` 三个请求头；标 **会话** 的端点
需要 Flarum 登录 Cookie。

所有响应中的 `error` 文本都由语言包渲染（默认简体中文），可用
`php flarum mc-bridge:config --locale=en` 切换为英文。错误**结构**不变，只有文案
随语言变化。

## CSRF 行为（重要）

Flarum 对整个 `api` 中间件栈强制校验 CSRF，而服务器没有 session 也没有 CSRF token。
扩展通过官方的 **`Extend\Csrf`** 扩展器按**路由名**豁免机器端点：

```php
(new Extend\Csrf())
    ->exemptRoute('mc-bridge.outbox')
    ->exemptRoute('mc-bridge.announcements')
    // ...
```

由此产生的约定：

| 端点 | 是否需要 CSRF token |
|------|--------------------|
| 机器端点（`outbox` / `announcements` / `bind/start` / `bind/status` / `broadcast`） | ❌ 不需要，靠 HMAC 认证 |
| `POST /mc-bridge/broadcast`（带签名头） | ❌ 不需要 |
| `POST /mc-bridge/broadcast`（管理员会话） | ✅ **需要** `X-CSRF-Token`，控制器内单独校验 |
| `POST` / `DELETE /mc-bridge/link`（会话） | ✅ 需要，**未豁免**，由框架校验 |

用 curl 调用会话端点时，从 `XSRF-TOKEN` Cookie 取值放进 `X-CSRF-Token` 头：

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/link \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"code":"K7MPQ2XY"}'
```

> **安全边界**：`link` / `unlink` 作用于登录用户的账号，**故意不在豁免名单里**。
> `broadcast` 为了机器调用被豁免，因此控制器对**会话路径**单独校验
> `X-CSRF-Token`，跨站表单依旧打不进来。

## 后台界面

目前**没有**管理后台 UI。`mc-bridge.*` 设置项通过控制台命令管理：

```bash
php flarum mc-bridge:secret                # 生成/查看密钥
php flarum mc-bridge:config --tags=1,3     # 公告标签过滤
php flarum mc-bridge:selftest --url=...    # 全链路自检
```

`locale/en.yml` 中的文案已为将来的后台设置页预留。

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
- `type` 可为 `broadcast` / `announcement`。
- `title` 与 `body` 至少提供一个。
- 插件只**展示**这些消息，不会执行任何指令。

响应 `201`：

```json
{ "ok": true, "message": { "id": 13, "type": "broadcast", "title": "维护通知", "body": "今晚 23:00 重启", "url": "/d/66", "payload": {}, "created_at": "…" } }
```

非管理员且无签名 → `403`。

---

## POST /api/mc-bridge/report

**认证：HMAC** — 从游戏服务器提交玩家举报，供论坛管理员处理。

请求：

```json
{
  "server_key": "survival",
  "reporter_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "reporter_name": "Alex",
  "target_name": "Steve",
  "reason": "他在出生点恶意破坏"
}
```

- `reporter_uuid` 必须是有效的 UUID。
- `target_name` 与 `reason` 必填。
- `reason` 最长 1000 字符。

响应 `201`：

```json
{ "ok": true, "report_id": 42 }
```

举报存储在 `mc_reports` 表中，初始状态为 `pending`。论坛管理员可手动将状态改为
`reviewed`（已处理）或 `dismissed`（已驳回）。

---

## GET /api/mc-bridge/activity

**认证：HMAC** — 查询当前进行中的活动投票，或最近结束、尚未被领取结果的投票。

请求：

```
GET /api/mc-bridge/activity?server_key=survival
```

响应 `200`：

```json
{
  "ok": true,
  "open": {
    "id": 7,
    "title": "下次活动玩什么？",
    "options": ["建筑大赛", "PvP 锦标赛", "跑酷"],
    "closes_at": "…",
    "closed": false,
    "open": true,
    "total_votes": 12,
    "tally": [5, 4, 3]
  },
  "results": null
}
```

- `open` 为 `null` 表示当前没有进行中的投票。
- 当某个投票刚到期时，服务端会把它标记为 `closed`，第一个来查询的服务器会拿到
  `results`（含 `winner` 字段），之后 `results` 恢复为 `null`。这样结果只广播一次。

---

## POST /api/mc-bridge/activity

**认证：HMAC 或管理员会话** — 发起一个活动投票（例如"下次活动玩什么"），
投票会通过 outbox 轮询自动广播到游戏内。

请求：

```json
{
  "server_key": "survival",
  "title": "下次活动玩什么？",
  "options": ["建筑大赛", "PvP 锦标赛", "跑酷"],
  "closes_in_minutes": 60
}
```

- `server_key` 省略时向所有服务器广播。
- `options` 需要 2–10 个非空字符串。
- `closes_in_minutes` 可选，默认 60，范围 5–10080（7 天）。

响应 `201`：

```json
{ "ok": true, "activity": { "id": 7, "title": "…", "options": ["…"], "closes_at": "…" } }
```

非管理员且无签名 → `401` / `403`。

---

## POST /api/mc-bridge/vote

**认证：HMAC** — 记录一名玩家在某活动投票中的选择。

请求：

```json
{
  "server_key": "survival",
  "activity_id": 7,
  "player_uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "player_name": "Alex",
  "option_index": 0
}
```

- `option_index` 从 0 开始，超出 `options` 范围 → `422`。
- 投票已结束 → `409`。
- 同一玩家重复投票会**覆盖**之前的选项（`(activity_id, player_uuid)` 唯一）。

响应 `200`：

```json
{ "ok": true, "activity_id": 7, "option_index": 0, "total_votes": 13 }
```

---

## GET /api/mc-bridge/link

**认证：论坛会话（需登录）** — 返回当前登录账号的 Minecraft 绑定，供论坛前端显示。

响应 `200`：

```json
{ "ok": true, "bound": true, "binding": { "user_id": 5, "username": "Alice", "player_uuid": "…", "player_name": "Alice", "server_key": "survival", "linked_at": "…" } }
```

未绑定时 `bound` 为 `false`、`binding` 为 `null`。

> 这个 GET 曾经漏注册：`LinkStatusController` 写好了却没挂路由，前端读状态拿到 404，
> 于是「绑定成功但论坛仍显示未绑定」。`tools/verify.mjs` 现在会比对前端调用的每个
> `/mc-bridge/*` 与 `extend.php` 里注册的方法+路径。

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

## 用户资源附加字段（user resource）

论坛前端要在**资料页**与**每篇帖子的作者名旁**显示 MC 账号，因此绑定信息被挂在 Flarum 的
user 资源上（`Stalir\McBridge\Api\UserResourceFields`），而不是让前端为每个作者各发一次请求。

| 字段 | 类型 | 说明 |
|------|------|------|
| `mcBridgePlayerName` | string \| null | 已绑定的 MC 玩家名 |
| `mcBridgeServerKey` | string \| null | 绑定时所在服务器的 `server.key` |
| `mcBridgeLinkedAt` | datetime \| null | 绑定时间 |

**可见性**：三个字段的 `visible` 回调都要求 `actor->isRegistered()`，即**只有登录用户**能在
payload 里看到它们；游客的响应里根本不包含这些字段（不是前端隐藏）。

**成本**：字段按用户逐个查询（一页约二十次索引查询）。之所以不一次性载入全部绑定，是为了
避免扫一张随绑定数增长的表；之所以不做跨请求缓存，是为了避免解绑后仍显示旧值。

---

## 论坛页面（服务端渲染，无需前端构建）

| 路径 | 认证 | 用途 |
|------|------|------|
| `GET /mc-bridge/link` | 需登录 | 输入绑定码 / 解除绑定（`POST` 同一路径，带 CSRF token） |

两者都由 PHP 直接渲染 HTML（`LinkPageController`、`StatusPageController`），因此不依赖 npm 构建；
论坛侧边栏的「服务器状态」入口由前端 bundle 以普通 `<a>` 链接加入。

---

## 数据表

| 表 | 用途 |
|----|------|
| `mc_servers` | 早期状态上报留下的表；上报功能已移除，表保留但不再写入 |
| `mc_events` | 早期游戏事件流水；上报功能已移除，表保留但不再写入 |
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
