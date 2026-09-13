# 验证报告

本文档如实记录 MC ↔ Flarum Bridge 的验证状态：**已经验证了什么、用什么方法、
以及为什么某些验证必须在你的服务器上完成。**

## 1. 本机环境限制

在编写本项目的机器上做了工具链探测，结果如下：

| 工具 | 状态 |
|------|------|
| Node.js | ✅ v24.21.0 |
| PHP | ❌ 未安装（PATH 与常见安装目录均无） |
| JDK（java / javac） | ❌ 未安装 |
| Gradle | ❌ 未安装 |
| Composer | ❌ 未安装 |

因此 `php -l`、`./gradlew build`、`composer require` **无法在此环境中执行**。
这是环境限制，不是代码问题。所有能在无 PHP/JDK 条件下验证的不变量都已自动
化验证，其余给出可复制粘贴的运行时验证步骤（第 4 节）。

## 2. 已完成的验证

### 2.1 静态一致性校验 —— 210 项全部通过

```bash
cd mc-flarum-bridge
node tools/verify.mjs
```

实测输出：`checks run: 210 / errors: 0 / warnings: 0 / ALL CHECKS PASSED`

覆盖的 15 类不变量：

| # | 检查项 | 为什么重要 |
|---|--------|-----------|
| 1 | 全部 JSON 文件可解析 | 3 个协议 Schema + composer.json 语法正确 |
| 2 | PHP PSR-4 布局（命名空间 + 类名 vs 路径） | 错一个字符就是「类找不到」致命错误 |
| 3 | 每一条 `use Stalir\McBridge\...` 都能解析到真实文件 | 捕获拼写错误与重命名遗漏 |
| 4 | `extend.php` 中每个 `::class` 引用存在（核心类标记为 external） | 路由/命令/监听器注册不会静默失败 |
| 5 | Java 包声明与目录、类型名与文件名一致 | `javac` 会直接报错的第一类问题 |
| 6 | Java 源码中每个项目内 import 都能解析 | 捕获跨包引用错误 |
| 7 | `plugin.yml` 主类、命令、权限与代码一致 | 主类写错插件直接不加载 |
| 8 | `BridgeConfig` 读取的 **19 个** 配置路径都在 `config.yml` 中定义 | 配置读取返回 null 导致的静默失效 |
| 9 | **插件多语言**：`config.yml` 的 `language` 指向存在的 `lang/*.yml`、`config.yml` 不再承载消息、`Messages.DEFAULT_LANGUAGE`/`FALLBACK_LANGUAGE` 与随包语言一致、代码用到的 **53 个**键在默认语言中全部存在、各语言键集完全相同、没有声明却未被引用的死键 | 玩家不会看到字面量 `bind-failed`，也不会因翻译缺键而显示原始键名 |
| 10 | **PHP 与 Java 的 HMAC 规范化字符串顺序一致**（`timestamp→nonce→method→path→body`） | 两端算法不一致 = 所有请求 401 |
| 11 | 迁移创建的 5 张表与 5 个模型的 `$table` 一一对应 | 查询不存在的表 |
| 12 | 10 条注册路由全部在 `docs/API.md` 中有文档 | 文档与实现漂移 |
| 13 | **Flarum 2.x 框架契约**：迁移必须返回 `['up'=>fn(Builder $schema), ...]`、模型必须显式开启 `$timestamps`、机器路由必须用 `Extend\Csrf` 豁免且**会话路由不得被豁免**、控制台命令必须继承 `AbstractBridgeCommand` 并实现 `fire()`、**必须用 `Extend\Locales` 注册语言目录**、占位符必须是 ICU `{name}` 语法、`BridgeMessages.DEFAULT_LOCALE` 与随包语言一致、各语言键集一致 | 这些是审查与实测中查出的真实缺陷，已固化为自动回归防护 |
| 14 | **CI 工作流自检**：`working-directory` 路径存在、引用的 `tools/*.mjs` 存在、三个 job 已声明、产物路径与 Gradle 默认输出一致 | 避免首次推送就因路径拼错而红 |
| 15 | **Java 编译隐患**：用到的 JDK/第三方简单名必须已 import（先剥离注释）、调度器调用不得直接传未加 `(Runnable)` 强转的方法引用 | 这两类正是首次 CI 编译失败的真实原因，现无需编译器即可拦截 |

第 10 项是这套桥接最关键的契约：两端分别用 PHP 和 Java 独立实现了同一套签名
算法，脚本会提取各自的字段顺序并断言完全一致，同时确认 PHP 使用
`hash_hmac('sha256')` + `hash_equals()`（常量时间比较）、Java 使用
`Mac("HmacSHA256")` + `HexFormat`。

### 2.2 协议一致性测试 —— 32 项全部通过

```bash
node tools/protocol-test.mjs
```

实测输出：`checks: 32 / passed: 32 / failed: 0 / ALL PROTOCOL CHECKS PASSED`（连续 3 次稳定）

拉起一个零依赖的模拟 Flarum（`tools/mock-flarum.mjs`）并以其为服务端，用独立
实现的 JS 签名器（等价于 `Signature.java`）作为客户端，覆盖：

1. 规范字符串语义（5 段 `\n` 连接、**末尾无换行**、method 大写、空 body）
2. HMAC 正确性（**RFC 4231 官方向量** + 独立 ipad/opad 构造交叉验证）
3. 路径规范化（查询串剔除、Flarum 子目录剔除）
4. 正确签名被接受（heartbeat / events / 带查询串的 outbox）
5. 错误密钥 → 401；缺失签名头 → 401
6. 篡改 body → 401；篡改签名 → 401
7. 时间戳窗口（过去 400s、远期未来）→ 401
8. nonce 重放 → 401
9. 公开端点免签名，且响应不含密钥
10. outbox 的 `peek` 语义（peek 不消费 / 消费后第二次为空 / 别名一致）
11. 绑定码格式与字符集（8 位、无 `0/O/1/I`）、已绑定返回 `already_bound`
12. 子目录安装与 **25 路并发**签名请求（nonce 各不相同）

> 该测试独立于 PHP/Java 实现，验证的是**协议规范本身可被正确实现**，是「两端
> 算法是否真的能互通」最强的离线证据。

### 2.3 独立代码审查（发现 2 个 blocker 并已修复）

由两个独立子代理分别对两端做对抗性审查。**Flarum 侧审查逐行比对了
`flarum/framework` 2.x 分支的真实源码**（并确认该分支 `Application::VERSION`
正是 `2.0.0-rc.8`）；Paper 侧审查逐条对照官方 javadoc。审查发现了 **2 个会让
功能完全不可用的 blocker**，均已修复：

#### Flarum 侧

| 级别 | 问题 | 处理 |
|------|------|------|
| **BLOCKER** | 迁移使用 `$this->schema`。Flarum 2.x 的 `Migration` 已重构为纯静态工厂类，**没有 schema 属性**，2.x 的契约是返回 `['up' => fn(Builder $schema), ...]`。原写法在 `php flarum migrate` 时必然抛 `Call to a member function hasTable() on null`，**5 张表一张都建不出来** | 改为返回闭包数组 ✅ |
| **BLOCKER** | Flarum 对整个 `api` 中间件栈强制校验 CSRF，只豁免 `token`/`registration-token` 路由。服务器没有 session 也没有 CSRF token，**所有 POST 端点会被 400 拦下**，HMAC 认证代码根本执行不到 | 第一次尝试用 `Extend\Middleware('api')->insertBefore(CheckCsrfToken::class, ...)` 插一个放行中间件——**实测无效**（`flarum.api.handler` 是单例，管道只构建一次，之后注册的 extender 被静默忽略），线上回环请求仍返回 `400 csrf_token_mismatch`。最终改用官方 `Extend\Csrf()->exemptRoute()` 按路由名豁免机器端点；`link`/`unlink` 故意不豁免，`broadcast` 因需支持机器调用而豁免、并在控制器内对会话路径单独校验 `X-CSRF-Token` ✅ |
| HIGH | `Flarum\Database\AbstractModel` 默认 `$timestamps = false`（与 Laravel 相反），导致 `created_at`/`linked_at` 恒为 `null`，`mc_events` 的复合索引失效 | 5 个模型显式 `$timestamps = true` ✅ |
| HIGH | 控制台命令继承 Symfony 原生 `Command`，Flarum 不会调用 `setLaravel()`，`$this->laravel` 与 `info()/error()` 助手不可用 | 改用 `Flarum\Console\AbstractCommand` + `fire(): int` ✅ |
| MEDIUM | `broadcast` 认证分支会静默降级为 403，排障时误判为权限问题 | 带任一 bridge 认证头即强制走 HMAC，失败直接 401 ✅ |
| MEDIUM | `/link` 的未登录 401 会漏出 Flarum 的 JSON:API 错误信封，与本扩展统一格式不一致 | 捕获 `NotAuthenticatedException` 返回统一 `{"error":...}` ✅ |
| MEDIUM | outbox 的 SELECT→UPDATE 之间无事务/行锁，并发轮询可重复投递 | 事务 + `lockForUpdate()` + 幂等 UPDATE ✅ |
| MEDIUM | 无后台设置 UI，`locale/en.yml` 为死键 | 文档明确说明改用控制台命令，locale 标注为预留 ✅ |
| LOW | `player_uuid` 同时 `unique()` 与 `index()` 冗余；composer 约束 `php ^8.1` 过宽；README 计数与启用命令有误 | 均已修正 ✅ |

#### Paper 侧

审查结论：**12 个 Java 文件零编译错误**，import 完整、无类型名冲突、覆写签名
正确、所有引用 API 在 Paper 1.21.1 上逐一实证存在；Gson 2.10.1 与 Adventure 是
`paper-api` 的 compile 依赖，**无需 shade**。

| 级别 | 问题 | 处理 |
|------|------|------|
| **BLOCKER** | `gradle.properties` 是纯 key/value 文件不做 Groovy 求值，我却写入了 `providers.gradleProperty(...)`，导致 paper-api 坐标变成非法字符串、依赖解析失败、12 个文件全部报 `package org.bukkit does not exist` | 改为纯数据 `paperApiVersion=1.21.1-R0.1-SNAPSHOT`（`-P` 覆盖依然有效）✅ |
| **BLOCKER** | 缺 `settings.gradle`，Gradle 7+ 直接拒绝执行该构建 | 已新增 ✅ |
| HIGH | 无 Gradle wrapper 而文档让用户执行 `./gradlew` | 文档给出 `gradle wrapper` 生成步骤 ✅ |
| HIGH | 异步任务直接触碰 Bukkit API（`getOnlinePlayers`/`getMotd`/`getMaxPlayers`），Paper 明确禁止 | 改为**主线程采集快照 + 异步只发 HTTP** ✅ |
| MEDIUM | 每次 `/mcbridge reload` 泄漏一个 `HttpClient`（线程 + 连接池） | 新增 `close()`，reload 时释放旧实例 ✅ |
| MEDIUM | `onDisable` 同步发两次请求，论坛不可达时最多阻塞关机约 20 秒 | 关机路径使用 3 秒超时 ✅ |
| MEDIUM | 跨线程读写的字段无 `volatile` | 已加 ✅ |
| MEDIUM | `BindCommand` 未判类型就调 `getAsBoolean()` | 已加 `isJsonPrimitive()` 判断 ✅ |
| LOW | `getMotd()` 已弃用、`player.yml` usage 漏 `stats`、`ignoreCancelled` 用在不可取消事件上 | 已修正/标注 ✅ |

**协议模拟测试的自纠**：子代理在实现 mock 过程中自查出 6 个缺陷，最严重的一个是
规范字符串末尾多了一个 `\n`（会导致**所有**签名与 PHP/Java 端不匹配），最终用
RFC 4231 官方向量与独立 ipad/opad 实现交叉验证锁死。

上述所有契约类问题都已写进 `tools/verify.mjs` 第 13 节作为**自动回归防护**，不会
再被改回。

### 2.4 真实 CI 运行 —— 三个 job 全部通过 ✅

仓库：**https://github.com/StalirMC/mc-flarum-bridge**

| 运行 | 提交 | 结果 |
|------|------|------|
| #1 | `84b5954` | ❌ 失败（暴露 4 个真实缺陷，见下） |
| #2 | `b5b7f5d` | ✅ **全部通过** —— [run #2](https://github.com/StalirMC/mc-flarum-bridge/actions/runs/34698839560) |

run #2 的三个 job：

| Job | 内容 | 结果 |
|-----|------|------|
| Static consistency + protocol conformance | `verify.mjs`（185 项）+ `protocol-test.mjs`（32 项），Linux/Node 22 | ✅ |
| PHP lint + extension manifest | `php -l` 全部文件、`composer validate`、清单检查、**实际 require 迁移文件并反射校验 up 闭包签名** | ✅ |
| Build the plugin with Gradle | Temurin JDK 21 + Gradle 8.10 → `gradle build` | ✅ |

产物：**`McBridge-plugin` jar（34.6 KB）已成功构建并上传为 workflow artifact**，
运行耗时 45 秒。

#### 首次 CI 运行抓到的问题（本机无法发现）

这正是把仓库推上 CI 的意义——本机没有 PHP/JDK，而 `javac` 给出了 4 个真实缺陷：

| 文件 | 错误 | 根因 |
|------|------|------|
| `HttpBridgeClient.java:54,62,131,142` | `cannot find symbol` | 加 `Duration` 超时参数时**漏了 `import java.time.Duration;`** |
| `HttpBridgeClient.java:120` | `signedBuilder cannot be applied to given types` | 给 `signedBuilder` 加超时参数后，`get()` 调用点仍是旧的 4 参数 |
| `McBridgePlugin.java:158,355` | `reference to runTaskTimerAsynchronously / runTaskAsynchronously is ambiguous` | Bukkit 调度器同时有 `Runnable` 与 `Consumer<BukkitTask>` 重载，而**隐式类型的方法引用不参与适用性判定**，导致歧义；需显式 `(Runnable)` 强转 |
| CI 的 PHP 清单检查 | `autoload psr-4 mapping ... must point at src/` | 内联 `php -r` 里把 PSR-4 键写成 `"Stalir\\\\McBridge\\\\"`；YAML 块标量原样传给 shell，PHP 得到两个真实反斜杠，永远匹配不上 composer.json 的单反斜杠 |

全部已修复（提交 `b5b7f5d`），并**把这两类 Java 隐患写进 `tools/verify.mjs` 第 15 节**
（缺 import 检测 + 调度器方法引用歧义检测），无需编译器即可拦截回归。

> 附：修正一处此前的不实记录 —— `BindCommand` 的 `already_bound` 类型检查在上一轮
> 被报告为"已修复"，但实际并未改动；本次已真正修复（提交 `b5b7f5d`）。

### 2.5 前端 bundle 初始化崩溃 —— 懒加载 chunk 陷阱（已修复）

**现象**：装上扩展后论坛前端报 `stalir-mc-bridge failed to initialize /
TypeError: Cannot read properties of undefined (reading 'prototype')`，「个人设置」里
根本看不到绑定码输入框。

**排查**（对照 `.tools/flarum-framework` 中的 Flarum 2.x 源码逐条取证）：

| 证据 | 位置 | 结论 |
|------|------|------|
| `settings: { path: '/settings', component: () => import('./components/SettingsPage') }` | `framework/core/js/src/forum/routes.ts:39` | 设置页是**动态 `import()` 的懒加载 chunk**，不在主 bundle 里 |
| 核心 `dist/forum.js` 中 `flarum.reg.add("core","forum/components/SettingsPage",…)` 命中 **0** 次（该产物共注册 217 个模块） | 核心构建产物 | 静态 `import SettingsPage from 'flarum/forum/components/SettingsPage'` 在 bundle 求值那一刻就是 `undefined` |
| `extend(object: T \| string, …)` 的字符串分支：`flarum.reg.onLoad(namespace, id, module => extend(module.prototype, …))` | `framework/core/js/src/common/extend.ts:37-43` | 传**模块路径字符串**时由 `extend` 自行取 `.prototype`，并等模块就绪 |
| `namespaceAndIdFromPath` 正则 | `common/ExportRegistry.ts:260` | `'flarum/forum/components/SettingsPage'` → `namespace='core'`、`id='forum/components/SettingsPage'` |
| `onLoad()`：已注册则**立即回调**，未注册则入队，等 `add()` 时触发 | `common/ExportRegistry.ts:109-117` | 两种时序都覆盖，chunk 加载完成后模块自己 `reg.add` 即触发 |
| `expose-loader` 是 `framework/core/js` 的依赖，核心 `.tsx` 只 `import type Mithril` | `framework/core/js/package.json:39` | 全局 `m` 是官方约定，组件里直接用 `m(...)` 正确 |

**修复**（`flarum-extension/js/forum.js`）：不再静态 import 设置页，改为按模块路径注册：

```js
extend('flarum/forum/components/SettingsPage', 'settingsItems', function (items) {
  items.add('mc-bridge', m(McBridgeSection), 12);
});
```

产物中已是 `(0,r.extend)("flarum/forum/components/SettingsPage","settingsItems",…)`，
`SettingsPage.prototype` 不再出现（构建产物 3.3 KB）。

**顺带确认**：`McBridgeSection` 用到的 `common/Component`、`common/components/FieldSet`、
`Button`、`LoadingIndicator` 均**在主 bundle 中注册**，静态 import 安全；只有懒加载的
核心页面组件不能静态 import。

**回归防护**：`tools/verify.mjs` 第 16 节（8 项）——入口文件必须位于 `js` 根目录、
禁止静态 import 懒加载核心模块（SettingsPage / PostsPage / NotificationsPage /
PostStream / PostStreamScrubber / DiscussionsUserPage / UserSecurityPage）、必须按模块
路径 `extend` 设置页、产物不得含 `SettingsPage.prototype`、前端 `this.t()` 用到的 9 个 key
必须存在于**所有** locale 文件。

## 3. 无法在本机验证的内容（现由 CI 覆盖）

> 本机没有 PHP / JDK，这些检查**已全部由 CI 在带 PHP 8.3 / JDK 21 的真实环境中
> 自动执行并通过**（见 2.4）。下表保留为「在任何机器上手动复核」的参考命令。

| 项目 | 命令 | 期望 | CI 状态 |
|------|------|------|---------|
| PHP 语法 | `find flarum-extension -name '*.php' -exec php -l {} \;` | 无 `Parse error` | ✅ CI 已执行通过 |
| Java 编译 | `cd mc-plugin && gradle wrapper --gradle-version 8.10 && ./gradlew build` | `BUILD SUCCESSFUL` | ✅ CI 已执行通过，jar 已上传为 artifact |
| Flarum 安装 | `composer require stalir/mc-bridge:'*'` | 扩展出现在管理后台 | ⬜ 需在真实论坛执行（CI 不安装 Flarum） |
| 迁移执行 | `php flarum migrate` | 5 张表建立 | ⬜ 需真实数据库（CI 仅反射校验迁移契约） |
| **桥接自检** | `php flarum mc-bridge:selftest --url=https://你的域名` | 全部 `OK` | ⬜ 需在真实论坛执行 |
| 插件加载 | 放入 jar 后启动服务器 | 日志出现 `McBridge enabled as server ...` | ⬜ 需在真实服务器执行 |
| 真实心跳 | 观察日志 / `GET /api/mc-bridge/status` | 服务器状态出现在论坛 | ⬜ 需在真实服务器执行 |

其中 `mc-bridge:selftest` 是专为此设计的：它在**运行论坛的那台机器**上检查密钥
长度、HMAC 签名/验签/篡改检测、规范化字符串格式、路径规范化（含子目录安装）、
5 张表是否存在、查询路径是否可用、绑定码字符集，并在给出 `--url` 时发起一次
**真实的带签名 HTTP 回环请求**，从而覆盖路由、中间件、签名校验、持久化的
完整链路。

## 4. 运行时验证清单（复制粘贴即可）

```bash
# ---- 论坛侧 ----
cd <flarum>
php flarum migrate
php flarum cache:clear
php flarum mc-bridge:secret                    # 记下输出的密钥
php flarum mc-bridge:config --tags=1,3         # 可选：只同步「公告」标签
php flarum mc-bridge:selftest --url=https://forum.kxkl2024.cn

# ---- 游戏侧 ----
cd mc-plugin && gradle wrapper --gradle-version 8.10 && ./gradlew build
# 复制 build/libs/McBridge-1.0.0.jar 到 server/plugins/
# 编辑 plugins/McBridge/config.yml 填入 forum.url 与 security.secret
# 启动服务器，然后：
#   /mcbridge stats     -> 配置状态应为「正常」
#   /mcbridge status    -> 应返回论坛记录的服务器数
#   /bind               -> 应返回 8 位绑定码
```

在论坛发一个新讨论，20 秒内游戏内应出现 `[论坛] <标题>`。

## 5. 验证强度说明

诚实的边界声明：

- 静态校验能证明**结构正确、契约一致、引用可解析**，不能替代编译器。
- 协议测试能证明**协议规范自洽且可实现**，但不能证明 PHP/Java 的具体实现
  运行时无错（例如某处 API 在目标版本上签名不符）。
- **编译这一环已由 CI 补上**：第 2.4 节的真实运行证明插件能在 JDK 21 + Paper API
  下编译出 jar、全部 PHP 文件能通过 `php -l`。而且这条路径确实有价值——首次运行
  就抓出了 4 个本机无法发现的真实缺陷。
- 仍然**没有**验证的是「运行时行为」：CI 只编译，不启动 Flarum、不启动 Minecraft
  服务器、不连数据库。因此：
  - Flarum 侧的接口/迁移/绑定流程，仍建议跑一次 `mc-bridge:selftest`；
  - 插件侧的真实心跳与公告投递，需装到服务器上观察。
- 最终判定仍需第 4 节在真实服务器上跑一次。`mc-bridge:selftest` 已把论坛侧这一步
  压缩为一条命令。
