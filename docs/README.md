# 部署指南

本文档描述从零把 MC ↔ Flarum 互通跑起来的完整步骤。

## 0. 前置条件

| 组件 | 要求 |
|------|------|
| Flarum | 2.x（PHP 8.1+），已能正常运行 |
| Minecraft 服务端 | Paper 1.21.x（同 API 的 Spigot 衍生端亦可） |
| Java | 21（Paper 运行时自带） |
| 网络 | MC 服务端能访问论坛的 `https://<forum>/api/mc-bridge/*` |

> 若论坛在 Cloudflare 等 CDN 之后，请确认没有对 `/api/mc-bridge/*` 开启
> “Under Attack” 或 WAF 规则拦截，否则插件会收到 403/1010。

## 1. Flarum 侧：安装扩展

### 1.1 放入扩展目录

把 `flarum-extension/` 放到 Flarum 根目录下的 `extensions/mc-bridge/`：

```
<flarum>/
├── extensions/
│   └── mc-bridge/          <- 本仓库的 flarum-extension/
├── composer.json
└── ...
```

### 1.2 注册 path 仓库并安装

编辑 Flarum 根目录的 `composer.json`，加入仓库声明：

```json
{
  "repositories": [
    { "type": "path", "url": "extensions/mc-bridge", "options": { "symlink": false } }
  ]
}
```

然后执行：

```bash
composer require stalir/mc-bridge:'*'
php flarum migrate
php flarum extension:enable stalir/mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear
```

### 1.3 生成共享密钥

```bash
php flarum mc-bridge:secret
```

命令会打印一个 64 位十六进制密钥。**这就是插件要填的 secret**，请妥善保存。

随时可以用下面的命令查看当前密钥：

```bash
php flarum mc-bridge:secret --show
```

### 1.4 可选：限定哪些讨论同步到游戏

默认所有新讨论都会推送到游戏。若只想同步「公告」类标签，先查出标签 ID：

```bash
php flarum tinker --execute="echo \Flarum\Tags\Tag::pluck('id','name');"
```

再用专用命令设置（不需要进 tinker）：

```bash
php flarum mc-bridge:config --tags=1,3     # 只同步标签 1 和 3
php flarum mc-bridge:config --tags=        # 清空过滤，恢复同步全部
php flarum mc-bridge:config --sync-replies=1   # 连回复也推送
php flarum mc-bridge:config --show         # 查看当前设置
```

`sync-replies` 为 `1` 时，符合条件讨论的**每条回复**也会推送到游戏；默认 `0`，
只在开新帖时推送，避免刷屏。

### 1.5 语言（可选）

两边都支持多语言，**默认都是简体中文**，互不影响：

```bash
# 论坛侧：控制台命令输出 + 接口错误消息
php flarum mc-bridge:config --locale=en        # 切换为英文
php flarum mc-bridge:config --locale=zh-Hans   # 切回中文（默认）
php flarum mc-bridge:config --show             # 查看当前语言
```

游戏侧改 `plugins/McBridge/config.yml`：

```yaml
language: zh_CN     # 或 en
```

然后 `/mcbridge reload`。

**新增语言**：

| 位置 | 做法 |
|------|------|
| 论坛侧 | 复制 `locale/zh-Hans.yml` 为 `locale/<新语言>.yml` 并翻译，`extend.php` 里的 `Extend\Locales` 会自动注册整个目录 |
| 游戏侧 | 复制 `lang/zh_CN.yml` 为 `lang/<新语言>.yml` 并翻译，把 `language` 指向它 |

两边都会在缺键时**回退到中文**，不会把原始键名显示给玩家。校验脚本会检查各
语言的键集是否一致。

> ⚠️ 改语言后论坛侧需要清一次缓存：`php flarum cache:clear`

## 2. Minecraft 侧：构建并安装插件

### 2.1 构建

仓库不含 Gradle wrapper 的二进制，先生成一次或用系统 Gradle：

```bash
cd mc-plugin
gradle wrapper --gradle-version 8.10   # 可选：生成 gradlew / gradlew.bat
./gradlew build                        # 或直接 gradle build
```

需要 **JDK 21**。产物：`build/libs/McBridge-1.0.0.jar`

如果服务器不是 1.21.1，可覆盖 Paper API 版本：

```bash
./gradlew build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

### 2.2 安装

```bash
cp build/libs/McBridge-1.0.0.jar <server>/plugins/
```

启动一次服务器生成 `plugins/McBridge/config.yml`，或直接把仓库里的
`src/main/resources/config.yml` 复制过去。

### 2.3 填写配置

```yaml
forum:
  url: "https://forum.kxkl2024.cn"     # 不要以 / 结尾
  api-prefix: "/api/mc-bridge"

server:
  key: "survival"                       # 多服时每台不同
  name: "Stalir 生存服"

security:
  secret: "<第 1.3 步生成的密钥>"
```

> 若 Flarum 装在子目录（如 `https://example.com/forum`），把 `url` 写全即可，
> 签名算法会自动忽略子目录差异。

### 2.4 生效

```
/mcbridge reload
```

看到 `McBridge enabled as server 'survival' -> ...` 和随后的心跳成功日志即表示
连通。

## 3. 验证互通

**先跑论坛侧自检**，它能一次性覆盖密钥、签名、表结构、查询与路由：

```bash
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn
```

全部显示 `OK` 说明论坛侧完全就绪（该命令会发一次真实的带签名回环请求，并在
结束后删除探针产生的临时服务器记录）。

### 3.1 服务器状态

```
/mcbridge status
```

应显示论坛记录的在线服务器数与玩家数。

也可以不开游戏直接验证——用一个已登录的论坛账号访问：

```bash
curl -s https://forum.kxkl2024.cn/api/mc-bridge/status | jq
```

### 3.2 公告推送

在论坛发一个新讨论（或在限定标签下发帖），几秒内游戏内应出现：

```
[论坛] <讨论标题>
<正文摘要>
/d/123
```

### 3.3 游戏 → 论坛

玩家进出服务器后，论坛后台的 `mc_events` 表会新增记录。

### 3.4 账号绑定

1. 玩家在游戏内执行 `/bind`，聊天栏出现 8 位绑定码（10 分钟内有效）。
2. 玩家登录论坛，调用绑定接口（或由你提供的页面/表单提交）。
   这是**会话**端点，除 Cookie 外还需带 `X-CSRF-Token`（详见
   [`API.md` 的 CSRF 一节](API.md#csrf-行为重要)）：

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/link \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"code":"ABCD2345"}'
```

3. 再次 `/bind` 会提示已绑定，并显示论坛用户名。
4. 解绑：`DELETE /api/mc-bridge/link`（同样需要登录会话）。

## 4. 从 Flarum 推送到游戏

### 4.1 广播

在后台用一个已登录且为管理员的会话（会话端点需带 `X-CSRF-Token`）：

```bash
TOKEN=$(curl -s -c jar.txt https://forum.kxkl2024.cn/ -o /dev/null; \
        grep XSRF-TOKEN jar.txt | awk '{print $7}')
curl -s -X POST https://forum.kxkl2024.cn/api/mc-bridge/broadcast \
  -H 'Content-Type: application/json' \
  -b jar.txt -H "X-CSRF-Token: $TOKEN" \
  -d '{"title":"维护通知","body":"今晚 23:00 重启","type":"broadcast"}'
```

也可以改用带签名的机器调用（不需要 Cookie / CSRF），签名方式见
[`../protocol/README.md`](../protocol/README.md) 第 5 节。

游戏在下一次 outbox 轮询（默认 20 秒）后全员显示。

### 4.2 远程指令（默认关闭）

若确实需要从论坛下发指令，先在 `config.yml` 打开白名单：

```yaml
game:
  allow-remote-commands: true
  remote-command-whitelist:
    - "^broadcast .+"
    - "^say .+"
```

再提交一条 `type=command` 的消息：

```json
{ "type": "command", "payload": { "command": "say hello from the forum" } }
```

> ⚠️ 打开后，任何拿到 secret 的人都能执行白名单内的指令。除非你完全信任
> secret 的保管，否则不要开启。

## 5. 多服务器

每台服务器用不同的 `server.key`（同一个 secret 即可）。消息的
`server_key` 为 `null` 时投递给所有服务器；指定 `server_key` 时只投递给那台。

## 6. 故障排查

| 现象 | 排查方向 |
|------|---------|
| `503 The MC Bridge secret is not configured` | 论坛侧还没执行 `mc-bridge:secret` |
| `401 Signature verification failed` | 两端 secret 不一致，或 `api-prefix` 被改过 |
| `401 Request timestamp is outside the allowed window` | 服务器时间不同步，配置 NTP |
| `401 Duplicate nonce detected` | 通常意味着请求被重放或代理重复发送 |
| `403` + Cloudflare 页面 | 关闭该路径的 WAF/挑战 |
| 游戏内无公告 | 用 `/mcbridge outbox` 看队列；确认 `announcement_tag_ids` 包含该标签 |
| 事件表为空 | 确认 `sync.report-joins` 等开关为 `true`，且日志无心跳失败 |

插件日志中「Heartbeat failed」每 10 次才打印一次，避免刷屏。
