# 部署指南

> ⚠️ **本项目仍在施工阶段，可用性尚未验证，请勿直接用于生产环境。**
> 详见根目录 [README](../README.md) 开头的说明。

本文档描述从零把 MC ↔ Flarum 互通跑起来的完整步骤。

## 0. 前置条件

| 组件 | 要求 |
|------|------|
| Flarum | 2.x（PHP 8.1+），已能正常运行 |
| Minecraft 服务端 | Paper 1.21.x · Folia 1.21.x（同一个插件 jar）；NeoForge 21.1.x for 1.21.1（模组 jar） |
| Java | 共享核心字节码目标 17；Paper/Folia 与 NeoForge 均需 Java 21 运行 |
| 网络 | MC 服务端能访问论坛的 `https://<forum>/api/mc-bridge/*` |

> 若论坛在 Cloudflare 等 CDN 之后，请确认没有对 `/api/mc-bridge/*` 开启
> “Under Attack” 或 WAF 规则拦截，否则插件会收到 403/1010。

## 1. Flarum 侧：安装扩展

### ⚠️ 先搞清楚：Flarum 2.x 没有 `extensions/` 目录

**Flarum 2.x 只从 Composer 的 `vendor/composer/installed.json` 发现扩展**，源码依据：

```php
// framework/core/src/Extension/ExtensionManager.php
$manifest = $this->paths->vendor.'/composer/installed.json';
$installed = json_decode($this->filesystem->get($manifest), true);
...
if (Arr::get($package, 'type') === 'flarum-extension' && str_contains($name, '/')) {
    $composerJsonConfs[$packagePath] = $package;
}
```

也就是说**没有「把文件夹丢进某个目录就能装」的机制**——扩展必须经由 Composer
安装，让它出现在 `installed.json` 里。（Flarum 仓库里那个 `extensions/` 目录只是
官方 monorepo 自己的源码布局，不是运行期约定。）

本仓库是 monorepo（扩展在 `flarum-extension/`、插件在 `mc-plugin/`）。根目录的
`composer.json` 使用了 Flarum 2.x 的 **`extra.flarum-subextensions`** 机制：

```json
{
  "autoload": { "psr-4": { "Stalir\\McBridge\\": "flarum-extension/src/" } },
  "extra": { "flarum-subextensions": ["flarum-extension"] }
}
```

`ExtensionManager::subExtensionConfsFromJson()` 会读取这个字段，把子目录里的
`composer.json` 识别为扩展。因此**整个仓库可以作为单个 Composer 包安装**，
扩展 ID 是子目录里声明的 `stalirmc-mc-bridge`。

> 注意 autoload 必须写在根 `composer.json` 里：Composer 不会处理子包自己的
> `autoload`，而 Flarum 也不会替扩展注册命名空间。

---

### 方式 A：后台安装（推荐，不需要 SSH）

该扩展**已上架 Packagist**：<https://packagist.org/packages/stalirmc/mc-flarum-bridge>

1. 管理后台 → **Extension Manager** → **安装一个新的扩展程序** → 填：
   ```
   stalirmc/mc-flarum-bridge
   ```
   Packagist 是 Composer 的默认源，所以**不需要**再手动添加 `vcs` 仓库。
2. 装好后到扩展列表**启用**「MC Bridge」
3. 生成共享密钥（见 1.3）；如果后台没有终端，用方式 B 或在服务器上执行

想跟最新开发版而不是稳定版时，填 `stalirmc/mc-flarum-bridge:dev-main`。

> 只有在 Packagist 尚未同步到某个提交、或要装尚未发布的版本时，才需要退回 `vcs` 方式：
> 后台 → 仓库 → 添加仓库（类型 `vcs`，URL `https://github.com/StalirMC/mc-flarum-bridge`）。

### ⚠️ 从旧包名 `stalir/*` 迁移过来

包名改成了组织名（Composer 要求全小写，所以是 `stalirmc/*`），**扩展 ID 也随之从
`stalir-mc-bridge` 变成 `stalirmc-mc-bridge`**。对已装过的论坛，替换一次即可：

```bash
cd /path/to/flarum
composer remove stalir/mc-flarum-bridge      # 若走方式 C 装的是 stalir/mc-bridge
composer require stalirmc/mc-flarum-bridge
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge
php flarum cache:clear
php flarum assets:publish
```

**数据与配置都会保留**，因为表名与设置键都没变：

| 内容 | 是否保留 | 原因 |
|------|----------|------|
| 5 张数据表（服务器/事件/公告/绑定/绑定码） | ✅ 保留 | 迁移里有 `hasTable` 守卫，重跑不会重建也不会报错 |
| 共享密钥（`mc-bridge.secret`）、语言、公告标签、同步开关 | ✅ 保留 | 设置键与包名无关 |
| 扩展的启用状态 | ⚠️ 需重新启用 | Flarum 按扩展 ID 记录启用列表，ID 变了就是新扩展 |

迁移是**必须**的：旧包名从来没有上架 Packagist，而仓库里的 `composer.json` 现在已经改名，
所以 `stalir/mc-flarum-bridge` 无法再安装。如果 `composer remove` 之后论坛报「扩展不存在」，
执行一次 `composer update stalirmc/mc-flarum-bridge` 让 Composer 重新解析即可。

### 方式 B：SSH / Composer

```bash
cd /path/to/flarum

composer require stalirmc/mc-flarum-bridge

php flarum migrate
php flarum extension:enable stalirmc-mc-bridge   # 用 php flarum extension:list 核对确切 ID
php flarum cache:clear
php flarum assets:publish
```

加 `:dev-main` 可以装开发版。**扩展更新后一定要跑 `assets:publish`**（前端 bundle 变了，
不跑的话浏览器拿到的还是旧脚本，见 1.2.1）。

### 方式 C：不经过 GitHub，直接用本地文件装

把 **`flarum-extension/` 整个目录**（不是仓库根目录）上传到服务器，例如
`<flarum>/packages/mc-bridge/`，然后加一个 **path 仓库**：

```json
{
  "repositories": [
    { "type": "path", "url": "packages/mc-bridge", "options": { "symlink": false } }
  ]
}
```

```bash
composer require stalirmc/mc-bridge:dev-main
php flarum migrate
php flarum extension:enable stalirmc-mc-bridge
php flarum cache:clear
```

> 方式 C 指向的是 `flarum-extension/` 本身（它自带的 `composer.json` 已经是
> `type: flarum-extension`），所以包名是 **`stalirmc/mc-bridge`**，与方式 A/B 的
> `stalirmc/mc-flarum-bridge` 不同。走这条路**不需要** `flarum-subextensions`。

### 1.2.1 更新到最新代码（前端资源必须重新发布）

仓库仍在施工阶段，每次拉取新代码后**必须重新发布前端资源**，否则浏览器拿到的还是旧的
`dist/forum.js`（表现为：代码改了、行为没变）：

```bash
cd <flarum>
composer update stalirmc/mc-flarum-bridge      # 走方式 C 安装的则是 stalirmc/mc-bridge
php flarum cache:clear
php flarum assets:publish                    # 扩展的前端 JS 会复制进 public/assets
```

> 在管理后台点「清除缓存」会顺带执行 `assets:publish`——`ClearCacheController`
> 内部直接调用 `AssetsPublishCommand`，所以两种做法等价。
> 另外浏览器可能仍缓存旧脚本，建议 `Ctrl+F5` 强制刷新一次。

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

## 2. Minecraft 侧：构建并安装

> **两个发行包。** Paper 插件 jar 根放 `plugin.yml`（Paper/Folia 读）；NeoForge 模组
> jar 放 `META-INF/neoforge.mods.toml`（NeoForge 读）。两者由同一份共享核心源码编译，
> 各自独立、互不依赖。构建时各自的 `verifyJar` 会校验描述符、入口类与随包资源都在，
> 且共享层不含任何平台类引用。**不再支持 Velocity。**

### 2.1 构建

仓库不含 Gradle wrapper 的二进制，用系统 Gradle 8.10+：

```bash
cd mc-plugin
gradle build              # 两个工程都构建
gradle :build             # 只要 Paper/Folia 插件（跳过耗时的 NeoForge 工具链）
```

需要 **JDK 21**（工具链）；共享核心的字节码目标为 Java 17，Paper 与 NeoForge 模块为 21。

首次构建 NeoForge 模组时，ModDevGradle 会下载 Minecraft 1.21.1 与 NeoForge 21.1.100，
并跑一遍 NeoForm（反编译 → 打补丁 → 重编译 5364 个源文件），**约 5-10 分钟**且需要网络；
之后 Gradle 缓存会复用，增量构建只需十几秒。

产物：

```
build/libs/McBridge-<版本>.jar
neoforge/build/libs/McBridge-neoforge-<版本>.jar
```

如果服务器不是 1.21.1，可覆盖 Paper API 版本：

```bash
gradle build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

### 2.2 安装

| 平台 | 放置位置 | 配置目录 |
|------|----------|----------|
| Paper / Folia | `<server>/plugins/` | `plugins/McBridge/` |
| NeoForge | `<server>/mods/` | `config/mc-bridge/` |

```bash
# Paper / Folia
cp build/libs/McBridge-<版本>.jar <server>/plugins/

# NeoForge（另一个文件）
cp neoforge/build/libs/McBridge-neoforge-<版本>.jar <server>/mods/
```

Folia 无需额外步骤：`plugin.yml` 已声明 `folia-supported: true`，插件会自动检测
regionised 服务端并改用 Folia 的 `AsyncScheduler` / `GlobalRegionScheduler`。

NeoForge 模组声明了 `side = "SERVER"`，**只需装在服务端**，客户端不需要安装。
它的命令用权限等级代替 Bukkit 权限节点：`/mcbridge news` 所有人可用，
其余子命令需要 OP（等级 2）。

启动一次服务器生成配置，或直接把仓库里的 `src/main/resources/config.yml`
复制到 `plugins/McBridge/config.yml`（NeoForge 为 `config/mc-bridge/config.yml`）。

> **多台后端一起装？** 可以，但请给它们**不同的 `server.key`**（例如 `survival`
> 与 `creative`），否则它们会在论坛上互相覆盖同一条服务器记录。

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

game:
  prompt-unbound: true                  # 未绑定玩家进服时提示一次怎么绑定
```

> 若 Flarum 装在子目录（如 `https://example.com/forum`），把 `url` 写全即可，
> 签名算法会自动忽略子目录差异。

### 2.4 生效

```
/mcbridge reload
```

看到 `McBridge enabled on paper as server 'survival' -> ...`（NeoForge 上为
`on neoforge`）即表示配置已被读取、插件已注册到论坛。`/mcbridge stats` 的第一行会显示
当前运行平台。

## 3. 验证互通

**先跑论坛侧自检**，它能一次性覆盖密钥、签名、表结构、查询与路由：

```bash
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn
```

全部显示 `OK` 说明论坛侧完全就绪（该命令会发一次真实的带签名回环请求，并在
结束后删除探针产生的临时服务器记录）。

### 3.1 公告推送

在论坛发一个新讨论（或在限定标签下发帖），几秒内游戏内应出现：

```
[论坛] <讨论标题>
<正文摘要>
/d/123
```

### 3.1.1 游戏内查公告

玩家在游戏内输入 `/mcbridge news`（或 `/mcb news`）可查看论坛最新 5 条公告。
该命令**不需要管理员权限**，所有玩家均可使用。

```
-------- 论坛公告 -------- (最近 3 条)
服务器维护通知
今晚 23:00 进行例行维护，预计持续 30 分钟。
周末活动预告
本周六下午 2 点举行建筑大赛，欢迎参加！
```

### 3.2 账号绑定

**玩家侧（正常流程）**

1. 在游戏内执行 `/bind`，聊天栏出现 8 位绑定码（10 分钟内有效）。
2. 打开论坛 → **右上角点击头像 → 设置** → 往下翻到「Minecraft 账号」区块，
   把绑定码填进去提交即可。绑定后该区块会显示已绑定的游戏昵称，
   个人资料页与帖子作者名旁也会出现 MC 徽章。
3. 再次 `/bind` 会提示已绑定，并显示论坛用户名。
4. 解除绑定：在同一区块点「解除绑定」。

> 首次进服且未绑定的玩家会被提示一次该流程（`game.prompt-unbound`，默认开启）。

> **唯一映射**：一个论坛账号只能绑定**一个** Minecraft 账号（`user_id` 唯一）。
> 多服务器场景下（生存服 + 创造服），该绑定**跨服共享** —— 玩家在任意一台服务器
> 绑定后，所有服务器都会识别为同一论坛账号。若玩家想在另一台服务器绑定不同的
> Minecraft 账号，必须先解除当前绑定。

**接口侧（自动化脚本或自建表单）**

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

解绑同样是接口调用：`DELETE /api/mc-bridge/link`（需要一个已登录的会话）。

> 扩展仍保留一个免构建的绑定页面 `/mc-bridge/link`（登录后直接访问），
> 适合把链接发给找不到设置页的玩家；正常流程用上面的「头像 → 设置」即可。

### 3.3 游戏内举报

玩家在游戏内输入 `/report <玩家名> <原因>` 可举报其他玩家：

```
/report Steve 他在出生点恶意破坏
```

举报需要 `mcbridge.report` 权限（默认所有玩家都有）。提交成功后玩家会收到确认消息：

```
已举报玩家 Steve，论坛管理员会尽快处理。
```

**管理员在论坛处理，不在数据库里处理。** 一条举报会产生两样东西：

1. `mc_reports` 表里的一行记录（状态 `pending`，用于留档）；
2. **论坛里的一条讨论**，挂上举报标签（**可以挂多个**），标题如 `[举报] Steve（由 Alex 提交）`，
   正文写明被举报人、举报人、服务器、时间、记录编号与举报原因。

所以管理员只要打开举报标签就能看到全部待处理举报，不需要查库。

标题、标签、发布账号都可以自定义，而且**两侧都能配**：游戏侧 `config.yml` 的值优先，
留空时用论坛侧设置兜底。

**游戏侧**（`plugins/McBridge/config.yml`，改完 `/mcbridge reload`）：

```yaml
report:
  # {target} {reporter} {reason} {server} 由插件填充；%...% 交给 PlaceholderAPI
  title-format: "[举报] {target}（由 {reporter} 提交）"
  # 逗号分隔，每项是 slug 或标签 ID
  tags: "reports,pending"
  actor: ""            # 用户名或用户 ID
```

> **PlaceholderAPI**：装了 PlaceholderAPI 与对应扩展时，`title-format` 里的 `%...%`
> 会以**举报人**的身份展开（如 `%player_name%`）；没装则原样保留。
> 标题是在**游戏内**渲染好再随举报发给论坛的 —— 论坛端拿不到游戏占位符。
> `/report` 在两个发行包上都存在：Paper/Folia 用 Bukkit 权限节点，NeoForge 用 OP 等级。

**论坛侧**（`php flarum mc-bridge:config ...`）：

| 设置 | 默认行为 | 修改方式 |
|------|---------|---------|
| 举报标签 | 先读设置；否则按 slug `reports` / 名称 `举报` 查找；装了 `flarum/tags` 但一个都没有时**自动创建一个次级标签** | `--report-tags=4,14` |
| 发布账号 | **最早的管理员**（普通成员未必有在举报标签下发帖的权限；用举报人自己的账号还会把举报人身份公开） | `--report-actor=3` |
| 标题模板 | `[举报] {target}（由 {reporter} 提交）`，仅在游戏侧没有发来标题时使用 | `--report-title="[举报] {target}"` |
| 恢复自动 | 传空值即可回到自动识别 | `--report-tags= --report-actor= --report-title=` |

首次举报时解析到的账号与标签会**写回设置**，因此 `php flarum mc-bridge:config --show`
显示的就是真实生效值 —— 这一步也是必需的：公告同步正是靠它识别举报讨论（见下）。

> **多标签的注意点**：`flarum/tags` 限制一条讨论能挂多少个主标签 / 次标签
> （后台 → 标签 → 设置）。超出限制时创建会失败，失败原因写进 Flarum 日志；
> 要么少挂一个，要么给发布账号 `bypassTagCounts` 权限。

> **举报不会被广播进游戏。** 举报讨论含有举报人身份，因此插件在同步公告时会跳过
> 带**任一**举报标签的讨论，以及由举报发布账号发起的讨论 —— 这两条是独立的兜底，任一条生效即可。
>
> 讨论创建是**尽力而为**：举报已经入库，论坛侧若出错（标签超限、没人有权限发帖等），
> 玩家的 `/report` 仍然返回成功，`discussion_id` 为 `null`。

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

## 5. 多服务器

每台服务器用不同的 `server.key`（同一个 secret 即可）。消息的
`server_key` 为 `null` 时投递给所有服务器；指定 `server_key` 时只投递给那台。

## 6. 故障排查

| 现象 | 排查方向 |
|------|---------|
| `composer require` 报 *is fixed to … (lock file version) by a partial update but that version is rejected by your minimum-stability* | 与扩展无关：论坛 lock 里锁着 `fof/*`、`ianm/*` 等 **beta 版本**，而 `minimum-stability` 不允许，于是**任何**新包的部分更新都会被拒。见下方专条 |
| `503 The MC Bridge secret is not configured` | 论坛侧还没执行 `mc-bridge:secret` |
| `401 Signature verification failed` | 两端 secret 不一致，或 `api-prefix` 被改过 |
| `401 Request timestamp is outside the allowed window` | 服务器时间不同步，配置 NTP |
| `401 Duplicate nonce detected` | 通常意味着请求被重放或代理重复发送 |
| `403` + Cloudflare 页面 | 关闭该路径的 WAF/挑战 |
| 游戏内无公告 | 用 `/mcbridge outbox` 看队列；确认 `announcement_tag_ids` 包含该标签 |
| 游戏内偶尔提示「论坛没有及时回应，举报**可能**已经提交」 | 客户端超时但服务端很可能已处理完。插件已在同一个 `report_uid` 下重试过一次，重复提交也不会产生第二条记录，所以按提示**不要再举报一次**即可。频繁出现时查论坛的 PHP-FPM 是否被占满 / CDN 是否在拖慢 `/api/mc-bridge/*` |

### 6.1 `composer update` 被 minimum-stability 拒绝

完整报错形如：

```
Package x/y is fixed to 1.2.3-beta.1 (lock file version) by a partial update
but that version is rejected by your minimum-stability.
Make sure you list it as an argument for the update command. Use the option
--with-all-dependencies (-W) ...
```

**与 MC Bridge 无关**：论坛的锁文件里锁着 `fof/*`、`ianm/*` 等 **beta 版本**，
而根 `composer.json` 的 `minimum-stability` 是默认的 `stable`，于是任何「部分更新」
在重新解析依赖时都会被这些已锁定的 beta 包卡住。

按报错提示照做即可 —— **把要更新的包名写进命令**，并加 `-W`：

```bash
composer update stalirmc/mc-flarum-bridge -W
```

`-W`（`--with-all-dependencies`）允许 Composer 顺带升降级那些被锁定的依赖，这正是它缺的能力。

仍然不行时，临时放宽稳定性、更新、再改回来：

```bash
composer config minimum-stability beta
composer config prefer-stable true
composer update stalirmc/mc-flarum-bridge -W
composer config minimum-stability stable
php flarum cache:clear
```

> 报错里点名的包不一定是 MC Bridge：被拒的是**锁文件里那个 beta 包**。
> 只要按提示把**你真正要更新的包**列为参数并加 `-W` 即可。

