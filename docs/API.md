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

**认证：HMAC** — 从游戏服务器提交玩家举报，并**在论坛里发成一个带举报标签的讨论**，
供管理员在论坛界面直接处理。

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

**可选字段**（游戏侧 `config.yml` 的 `report:` 段随请求发来；全部可省略）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `report_uid` | string | 可选。本次举报的幂等标识（UUID）。同一个 uid 重复提交只会入库一次，见下 |
| `title` | string | 讨论标题。由插件在**游戏内**渲染，因此可以包含 PlaceholderAPI 的输出。缺省时论坛按自己的模板渲染 |
| `tags` | string[] | 讨论归入哪些标签，每项是 slug 或标签 ID（最多 10 项）。缺省时用论坛设置 |
| `actor` | string | 以哪个论坛账号发布，填用户名或用户 ID。缺省时用论坛设置 |

后三者都是**提示**：无法解析的项会被记进 Flarum 日志并跳过，**不会**让这次举报失败 ——
举报本身已经入库，玩家不该因为论坛侧的配置笔误看到 500。

**`report_uid` 与重试**：客户端超时**不能说明**服务端没处理完 —— 现实中更常见的是服务端做完了、
回复在路上丢了。插件因此只在**完全没有收到响应**时用同一个 uid 重试一次；
论坛把该 uid 在缓存里认领 600 秒，重试直接返回第一次的结果：

```json
{ "ok": true, "report_id": 42, "discussion_id": 128, "duplicate": true }
```

`duplicate` 只是给调用方的额外信息；`ok` 为 `true` 即表示举报已入库。
这样一来一次超时不会变成两条记录或两条讨论。

反过来，只要收到**任何** HTTP 状态码（哪怕是 500），就说明服务端已经答复、结果确定，
插件不会重试 —— 重试只会重复同一个错误。

响应 `201`：

```json
{ "ok": true, "report_id": 42, "discussion_id": 128 }
```

举报会做两件事：

1. **落库**到 `mc_reports` 表，初始状态 `pending`（留档与审计）。
2. **发一条讨论**到论坛的举报标签下（可挂多个），标题如 `[举报] Steve（由 Alex 提交）`，
   正文列出被举报人、举报人、服务器、时间、记录编号与举报原因。

讨论是通过 Flarum 自己的 JSON:API 管道创建的（和用户在论坛发帖走同一条路径），
所以首帖、标签关联、回复计数与作者的已读状态都由框架负责，扩展不去手写这些行。

| 行为 | 说明 |
|------|------|
| 发布账号 | 请求里的 `actor` 优先；否则用设置；否则取**最早的管理员**（`--report-actor=<用户ID>`） |
| 举报标签 | 请求里的 `tags` 优先；否则用设置；否则按 slug `reports` / 名称 `举报` 查找；都没有且装了 `flarum/tags` 时自动创建一个次级标签（`--report-tags=4,14`） |
| 标题 | 请求里的 `title` 优先；否则按设置里的模板渲染（`--report-title="..."`） |
| 自配置 | 实际生效的账号与标签会**写回设置**，因此 `--show` 看到的是真实值 |
| 失败处理 | 讨论创建是**尽力而为**：举报已经入库，论坛侧出错不会让玩家的 `/report` 变成 500。失败写进 Flarum 日志，响应里的 `discussion_id` 为 `null` |
| 不会广播 | 举报讨论永远不会被推送到游戏（见下） |

> **不会进游戏**：`QueueAnnouncement` 会跳过举报讨论 —— 只要它带有**任一**举报标签，
> 或作者是举报发布账号。否则举报内容（含举报人身份）会被广播给所有在线玩家。
>
> 注意 `flarum/tags` 对一条讨论能挂的主/次标签数量有限制，超出时那次创建会失败；
> 失败原因会写进 Flarum 日志。

`discussion_id` 在论坛侧创建失败时为 `null`；调用方可以忽略它。

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
| `php flarum mc-bridge:config --show` | 查看公告标签过滤、回复同步、保留天数、输出语言、举报标签、举报发布账号与举报标题模板 |
| `php flarum mc-bridge:config --locale=en` | 切换输出语言（默认 `zh-Hans`，可选 `en`） |
| `php flarum mc-bridge:config --tags=1,3` | 只把标签 1、3 的新讨论推送到游戏（空值 = 全部） |
| `php flarum mc-bridge:config --sync-replies=1` | 连回复也推送 |
| `php flarum mc-bridge:config --report-tags=4,14` | 指定举报讨论归入哪些标签，逗号分隔（空值 = 自动识别 slug `reports` / 名称 `举报`） |
| `php flarum mc-bridge:config --report-actor=3` | 指定举报讨论以哪个账号发布（空值 = 最早的管理员） |
| `php flarum mc-bridge:config --report-title="[举报] {target}"` | 游戏侧没发来标题时使用的模板（空值 = 内置默认） |
| `php flarum mc-bridge:selftest` | 离线自检：密钥、HMAC、表结构、查询、绑定码格式 |
| `php flarum mc-bridge:selftest --url=https://your.forum` | 追加一次真实的带签名 HTTP 回环请求 |
