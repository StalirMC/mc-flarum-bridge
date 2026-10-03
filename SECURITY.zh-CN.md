# 安全策略

[English](SECURITY.md) · **简体中文**

McBridge 在 Minecraft 服务端与一个 Flarum 论坛之间放入了一个共享密钥和一个 HTTP API，因此这里的缺陷通常就是那条信任边界上的缺陷。欢迎举报，我们会认真对待。

## 举报漏洞

**请不要为安全问题开设公开 issue。** 改用 GitHub 的私密举报通道：

1. 打开[仓库](https://github.com/StalirMC/mc-flarum-bridge) → **Security** → **Report a vulnerability**（GitHub Security Advisories），或
2. 使用 **Security** 标签页上的 **Report a vulnerability** 按钮。

这样我们就有了一条私密讨论串。有用的举报包含：

- 受影响的版本（游戏服务端上的 `/mcbridge stats`，或论坛上的 `composer show stalirmc/mc-flarum-bridge`），
- 平台（Paper / Folia / NeoForge，以及 Minecraft 版本），
- 攻击者能得到什么，以及可复现它的具体请求或聊天命令，
- 是否需要有效的 `security.secret` 才能得手。

请不要在举报中附带可用的 `security.secret`、会话 Cookie 或玩家个人数据。如果必须展示流量，请替换掉它们。

这里没有漏洞赏金，也不承诺响应时间；这是一个在业余时间维护的小项目。已确认的问题会在下个版本中修复，并在发布说明中致谢，除非你另有要求。

## 支持的版本

只支持最新的发布版本。修复会以新的 `0.0.x` 发布，并且**不会**向后移植到更旧的标签。Flarum 扩展和 jar 必须处于同一版本：版本不匹配是「桥突然什么都不做」最常见的原因。

## 信任边界是什么

上报之前值得先理解这些，这样举报才会落到正确的层面上：

- 每个机器请求都用 `security.secret` 以 HMAC-SHA256 对 `{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}` 签名。该密钥在论坛一侧生成（`php flarum mc-bridge:secret`），并粘贴到游戏服务端的配置文件中。
- 当时间戳超出了 **±5 分钟**的窗口、nonce 已被使用过（保留 **10 分钟**），或签名不匹配时，请求会被拒绝。比较是恒定时间的。
- 被签名的路径是桥接路径，而不是原始请求路径，因此子目录安装（`https://forum.example.com/forum`）不会破坏签名，也无法夹带路径。
- 机器端点位于 `/api/mc-bridge/` 之下，并且免除 CSRF；它们由签名而非会话进行认证。
- 游戏内权限是独立的：`mcbridge.bind` 和 `mcbridge.report` 默认对所有人开放，`mcbridge.admin` 默认仅限管理员。

## 面向运维人员的加固清单

- 通过 **HTTPS** 提供论坛服务，并把 `forum.url` 设为 `https://` URL。签名证明的是真实性和完整性，而不是机密性：在明文 HTTP 下，窃听者可以读取每一条举报正文（玩家名，以及若已启用，聊天记录），并且可以在 5 分钟窗口内重放一次截获的请求。
- 给 `security.secret` 真正的熵（生成的值即可），并把它当作密码对待：不要放进 git、截图或支持请求中。
- **密钥泄露就轮换**：重新运行 `php flarum mc-bridge:secret` 并更新游戏服务端的配置，然后执行 `/mcbridge reload`。轮换不会让其他任何东西失效。
- 让扩展和 jar 保持同一版本，并让论坛本身保持已打补丁的状态。
- 限制能读取游戏服务端配置文件和论坛 `.env` 的人员；两侧都持有一份密钥副本。
- 聊天上下文只从**公开聊天**中采集，并且作为举报的一部分存储在论坛上。`report.chat-context-lines`（默认 10，最多 500）和 `report.chat-context` 开关控制它；如果你的玩家的隐私预期（或当地法律）要求完全不保留聊天记录，就把该开关设为 `0`。私信和命令永远不会被采集。

## 不视为漏洞的事项

- 任何需要已经失陷的论坛管理员账号、论坛主机上的 shell、Minecraft 服务端的控制台访问或直接数据库访问才能做到的事情。到了那一步，攻击者本来就已经持有一份密钥副本了。
- 使用泄露的密钥调用机器端点。那是密钥轮换问题，不是 CVE：按上面的方式轮换即可。
- 全新安装时插件报告 `security.secret` 缺失，或暂停它的功能。这是预期的故障即关闭行为。
- 只影响显示效果的缺失功能。
- 针对没有在反向代理层做速率限制的部署、靠单纯流量发起的拒绝服务。这里没有内置速率限制器（见「可以添加什么」的说明）；请在论坛前面加一个。
