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

**现象**：装上扩展后论坛前端报 `stalirmc-mc-bridge failed to initialize /
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

### 2.6 管理后台启动崩溃 —— 2.x 移除了 `app.extensionData`（已修复）

**现象**：打开 `admin#/extension/stalirmc-mc-bridge` 后控制台报
`stalirmc-mc-bridge failed to initialize / TypeError: Cannot read properties of
undefined (reading 'for')`，位置 `admin.js:8`（即 `app.extensionData.for(...)`）。

**排查**：

| 证据 | 位置 | 结论 |
|------|------|------|
| 核心源码中 `app.extensionData` **0 命中** | `framework/core/js/src` 全树 | 2.x 已彻底移除该 API |
| 类文档示例写作 `app.registry.for('flarum-tags')` | `admin/utils/AdminRegistry.ts:50` | 新入口是 `app.registry`（`AdminRegistry`） |
| `registry = new AdminRegistry();` 是类属性 | `admin/AdminApplication.tsx:117` | 初始化器运行时它已存在（不是懒加载 chunk，无 2.5 那类时序问题） |
| `app.registry.getSettings(this.extension.id)` | `admin/components/ExtensionPage.tsx:179` | 扩展页正是从这里读回设置项 |

**修复**（`flarum-extension/js/admin.js`）：`app.extensionData.for(id)` → `app.registry.for(id)`，
产物为 `n().registry.for(s).registerSetting({…})`。

顺带把前端其余 1.x 写法一并对齐（都已写入 `tools/verify.mjs` 第 16 节）：

| 项 | 1.x 写法 | 2.x 正确写法 | 依据 |
|---|---|---|---|
| 设置区块标题 | `m(FieldSet, …, m('legend', …))` | `m(FieldSet, { label, description })` | `common/components/FieldSet.tsx:19-24`：渲染自己的 `<label class="FieldSet-label">`，且根元素是 `div` 而非 `fieldset` |
| 提示信息 | `m('.Alert.Alert--error', m('li', …))` | `m(Alert, { type, content, dismissible: false })` | `common/components/Alert.tsx:10-27`（不传 `dismissible` 会默认渲染一个关闭按钮） |
| 设置页扩展点 | `extend(SettingsPage.prototype, …)` | `extend('flarum/forum/components/SettingsPage', …)` | 见 2.5：该页是懒加载 chunk |

**同时逐项确认存在**（避免再往返一轮）：`SettingsPage.settingsItems()`
（`SettingsPage.tsx:57`，由 `content()` 经 `listItems` 渲染）、`app.session.csrfToken`
（核心自己也发同名 `X-CSRF-Token` 头）、`app.forum.attribute('apiUrl')`（`ForumResource.php:96`）、
`Extend\Settings->default()`、`Flarum\Post\Event\Posted`、样式类 `.Form-group` /
`.FormControl` / `.FieldSet-label`、`Button` 的 `loading` 属性、
`SelectFieldComponentOptions.options` 的「值 → 标签」映射形状。

### 2.7 三平台合一 jar（Paper + Folia + Velocity）

**目标**：一个 jar 同时能装到 Paper、Folia 和 Velocity 上。

**可行性依据**（逐条取证，不是猜测）：

| 事实 | 来源 | 说明 |
|------|------|------|
| 一个 jar 可以同时携带多个平台描述符 | ViaVersion / ViaBackwards / ViaRewind 的发行包 | `plugin.yml`（Bukkit 系）与 `velocity-plugin.json` 同放 jar 根，各平台只加载自己描述符里的入口类 |
| Folia 的调度 API 就在 **paper-api** 里 | `jd.papermc.io/paper/1.21.1/io/papermc/paper/threadedregions/scheduler/AsyncScheduler.html`（标题即 “paper-api 1.21.1-R0.1-SNAPSHOT API”） | 因此**不需要**额外的 `folia-api` 依赖，也不需要反射；`getAsyncScheduler()`、`getGlobalRegionScheduler()`、`runAtFixedRate`、`execute`、`cancelTasks` 均已核对签名 |
| Folia 只加载声明了 `folia-supported: true` 的插件 | `plugin.yml` 契约 | 构建断言 + CI shell 断言双重校验 |
| Velocity 从 jar 根读取 `velocity-plugin.json` | Velocity 的 “Did not find a valid velocity-plugin.json” 报错机制 | 该文件由 `velocity-api` 的注解处理器从 `@Plugin` 生成，**不手写** |
| Velocity 与 Paper 都自带 Adventure 与 Gson | 两端各自的依赖 | 共享层只 `compileOnly` 这两者，jar 内**零第三方代码**，不需要 Shadow/relocate |

**结构**：三个 source set → 一个 jar。

```
src/main/java      共享核心：BridgeCore（心跳/事件/公告/命令文案）、
                   Platform（平台 SPI）、BridgeConfig、Yaml、Signature、
                   HttpBridgeClient、EventQueue、Messages、Version
src/paper/java     McBridgePlugin、PaperPlatform（Folia/Bukkit 双调度）、
                   PlayerListener、BindCommand、BridgeCommand
src/velocity/java  McBridgeVelocityPlugin（@Plugin）、VelocityPlatform、
                   VelocityListener、VelocityBindCommand、VelocityBridgeCommand
```

**关键设计：协议只写一遍。** 心跳载荷、事件结构、outbox 处理、公告渲染、全部命令
回复都由 `BridgeCore` 生成，平台模块只提供「调度 + 服务器状态 + 输出」三件事。
因此三个平台发出的请求逐字节相同，论坛侧无需区分平台（平台名只出现在
`/mcbridge stats` 与启动日志里）。

**共享层禁止引用平台类**，否则另一平台加载时会 `NoClassDefFoundError`。这条由三处
同时把关：

1. Gradle `verifyJar`：扫描共享层 class 文件的常量池，出现 `org/bukkit/`、
   `com/velocitypowered/`、`io/papermc/` 即构建失败
2. `tools/verify.mjs` 第 17 节：源码级 import 检查（共享层不得 import 平台包，
   paper 模块不得 import Velocity，velocity 模块不得 import Bukkit）
3. `verify.mjs` 第 6 节：模块间依赖方向（`main` → 只能 `main`；`paper`/`velocity`
   → 可依赖 `main`）

**Folia 调度**：运行时用 `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`
判断；regionised 时只用 Folia 调度器（此时 `Bukkit.getScheduler()` 会抛
`UnsupportedOperationException`），普通 Paper 上仍走 `BukkitScheduler`。玩家消息在
Folia 上通过 `Player#getScheduler()` 投递到该玩家所属的 region 线程。

**Java 版本**：工具链 JDK 21，但 `options.release = 17`。Paper/Folia 1.21 要求 Java 21
运行、执行 17 的字节码没有问题；而 Velocity 3.x 仍支持 Java 17，目标定 21 会把一部分
代理服主挡在门外。第 17 节会拦截 Java 21 专有 API（`List#getFirst`、`Math#clamp` 等）
以防回归。

**构建期断言（`verifyJar`，CI 中真实执行）**：

| 断言 | 为什么 |
|------|--------|
| 两份描述符 + 两个入口类 + `config.yml` + `lang/*.yml` 都在 | 通用 jar 最典型的失败方式是「能构建但某个平台装不上」 |
| `plugin.yml` 的 `main:` 指向 paper 模块 | 重构后容易残留旧类名（本次就发生过） |
| `plugin.yml` 声明 `folia-supported: true` | 否则 Folia 直接拒绝加载 |
| 两份描述符的版本都等于项目版本 | 版本三处（`gradle.properties`、`Version.java`、描述符）容易漂移 |
| 共享层常量池无平台引用 | 见上 |

**发布**：`.github/workflows/release.yml` 由 tag 触发，先校验 tag 与
`gradle.properties` 版本一致，再构建并上传**同一个通用 jar**。

**仍未验证**：jar **从未在真实的 Folia 或 Velocity 上加载过**（本机没有 JDK，CI 只
编译与静态断言）。首次实机验证请重点看：Folia 启动日志是否出现 `McBridge enabled on
folia`、有无 `UnsupportedOperationException`；Velocity 是否成功加载并打印
`on velocity`；两端 `/mcbridge stats` 的平台行是否正确。

### 2.8 本地真实构建与共享核心自测（本轮新增）

上文 2.4 之后，本机被确认**其实带有一个 JDK**（`C:\Program Files\Zulu\zulu-25`），
只是没有 Gradle。于是把 Gradle 8.10 与 Temurin JDK 21 下载到仓库之外的 `.tools/`
（不进版本库），第一次拿到了**真正的本地编译循环**——不必再靠 CI 往返猜错误。

**这一步立刻抓到一个会让 CI 失败的缺陷**：

| 现象 | 根因 | 处理 |
|------|------|------|
| `Could not resolve io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT`：*Dependency resolution is looking for a library compatible with JVM runtime version 17, but … is only compatible with JVM runtime version 21 or newer* | 为了让 Java 17 的 Velocity 也能加载，我把**整个工程**设成 `options.release = 17`；但 `paper-api` 1.21.1 本身是 Java 21 字节码，Gradle 的变体解析据此直接拒绝 | 改成**按模块**：`main`/`velocity`/`test` 保持 17，`paper` 单独提到 21（Paper/Folia 1.21 本来就必须 Java 21 运行，没有损失） |

**本地 `gradle build` 结果**（JDK 21 + Gradle 8.10）：

```
> Task :compileJava          共享核心
> Task :compilePaperJava     Paper/Folia（release 21）
> Task :compileVelocityJava  Velocity（release 17）
> Task :selfTest             共享核心在真实 JVM 上自测
checks run: 51   failures: 0   SHARED CORE SELF TEST PASSED
> Task :verifyJar
verified McBridge-0.0.1.jar: 36 entries, loadable by Paper, Folia and Velocity
BUILD SUCCESSFUL in 1m 4s
```

**新增的运行时自测（`gradle selfTest`，`build` 会执行它）** 覆盖的正是静态检查与模拟论坛
都碰不到的代码路径——那些检查从不执行 Java：

| 组 | 覆盖内容 |
|----|----------|
| 1. `config.yml` 解析 | 嵌套映射、引号、注释剥离、非 ASCII 值、布尔/整数、块列表（正则白名单两条）、缺键回退、把标量当 section 时回退 |
| 2. 语言文件 | 两个语言的键集一致、`prefix`/`log.enabled`/新增 `stats-platform` 都在、`\"` 转义被正确反转义且不破坏引号状态 |
| 3. 配置校验 | 出厂配置因空密钥而不可用、问题文案已本地化、`log.enabled` 的 `{platform}`/`{key}` 插值无残留占位符、`apiPath`/`endpoint` 拼接 |
| 4. 完整配置 | 可用性、密钥与服务器标识、间隔值、`allow-remote-commands` 默认关闭且开启后正则白名单真正生效（`say hello` / `broadcast …` 通过，`op someone` 拒绝）、过短密钥与缺 scheme 的 URL 都判为不可用 |

**产物实测**（`jar tf` + 直接读描述符与 class 头）：

```
36 个条目 / 64 KB，无任何第三方代码
plugin.yml            version: '0.0.1'  main: cn.stalir.mcbridge.paper.McBridgePlugin  folia-supported: true
velocity-plugin.json  {"id":"mc-bridge", …, "main":"cn.stalir.mcbridge.velocity.McBridgeVelocityPlugin"}
字节码主版本           BridgeCore = 17   McBridgePlugin = 21   McBridgeVelocityPlugin = 17
```

最后一行正是设计意图：**一个 jar 里混用 class 文件版本是合法的**，每个 class 自己声明版本，
JVM 只加载它支持的那些。

**CI**：同一个提交 `dd28da7` 的
[run #24](https://github.com/StalirMC/mc-flarum-bridge/actions) 三个 job
（静态+协议 / PHP lint / Gradle 编译）全部通过，与本地结论一致。

**仍然未经实机验证**：jar 依旧**没有在真实的 Paper / Folia / Velocity 上加载过**。
以上全部结论都停留在「编译、静态断言、在真实 JVM 上执行共享核心」这一层，不能替代上机。

### 2.9 首次实机启动，以及它暴露的三个缺陷（0.0.2 修复）

用户把 0.0.1 装进了真实的 Paper 服务端，首次启动的日志是这套代码第一次真正跑在服务端上：

```
[17:13:49 WARN]: [McBridge] Could not save config.yml to plugins\McBridge\config.yml because config.yml already exists.
[17:13:49 WARN]: [McBridge] Could not save zh_CN.yml to plugins\McBridge\lang\zh_CN.yml because zh_CN.yml already exists.
[17:13:49 WARN]: [McBridge] Could not save en.yml to plugins\McBridge\lang\en.yml because en.yml already exists.
```

**这条日志同时是证据和缺陷报告**：

- 它是证据：`[McBridge]` 前缀、`plugins\McBridge\` 数据目录、`lang/` 子目录全部出现，
  说明**插件在真实 Paper 上成功启用**，`onEnable → BridgeCore.start() → Messages.load()`
  这条链路真的执行了，没有 `NoClassDefFoundError`、没有崩溃。2.8 节里「从未在真实服务端加载过」
  这句话到此作废。
- 它是缺陷：这三行警告来自 **Bukkit 自己**——`JavaPlugin#saveResource(path, false)` 在目标文件
  已存在时会主动打这条 WARN。也就是说文件「已存在」是**每次启动的正常状态**，却被报成了警告。

#### 缺陷 1：每次启动都刷三条无意义警告

| | |
|---|---|
| 根因 | `PaperPlatform.saveResource()` 无条件调用 `plugin.saveResource(path, false)`；Bukkit 在该文件已存在且 `replace=false` 时自己打 WARN |
| 修复 | 先判断目标文件是否存在，存在就直接返回，根本不去调 Bukkit 那个方法（`PaperPlatform.java`） |
| 为什么值得修 | 这三行会永久污染控制台日志，把真正的告警淹掉；而「不覆盖用户改过的文件」这个意图本来就已经由 `replace=false` 保证了，不需要靠一条警告来表达 |

#### 缺陷 2：绑定了账号，论坛却一直显示「未绑定」（用户实际反馈）

这是本轮真正影响功能的问题，根因是一条**没接上的路由**：

| 步骤 | 实际发生的事 |
|------|--------------|
| 游戏内 `/bind` 拿码 → 论坛输入 | ✅ 正常 |
| `POST /api/mc-bridge/link` 落库 | ✅ 正常（`mc_bindings` 里确实有记录） |
| 论坛前端读状态 `GET /api/mc-bridge/link` | ❌ **该路由从未注册**：`LinkStatusController` 写好了、也 `use` 进来了，却漏了 `->get(...)` 那一行 |
| 前端拿到 Flarum 的 404 错误文档 | ❌ 代码读 `body.bound` → `undefined` → **当成「未绑定」渲染** |

前端「读不到就把 falsy 当 false」的写法，让一个 404 变成了一个看起来正常的「未绑定」界面——
错误被吞掉了，所以表现成「绑定成功但论坛不显示」。

修复三处：

1. `extend.php` 补上 `->get('/mc-bridge/link', 'mc-bridge.linkStatus', LinkStatusController::class)`
2. 前端 `refresh()` 改为先看 HTTP 状态与 `body.ok === true`，**任何不是本扩展产生的响应都报错**
   （显示论坛返回的 `error`，否则显示本地化的 `load_error`），不再静默降级
3. 新增 `tools/verify.mjs` 检查：**前端调用的每个 `/mc-bridge/*` 都必须有匹配 HTTP 方法的路由注册**
   （从 `extend.php` 的 `Extend\Routes('api')` 块里解析方法+路径，与 JS 里的 `fetch`/`request` 调用比对）

第 3 条做过**反向验证**，确认它真的能抓到这类 bug，而不是一条永远绿的装饰：

```
（临时删掉 GET 路由）
FAIL frontend endpoints
     the frontend calls GET /mc-bridge/link but extend.php registers no such API route
errors: 1
（恢复后）errors: 0，ALL CHECKS PASSED
```

#### 缺陷 3：版本号升了，打进 jar 的却还是旧版本

把版本从 0.0.1 提到 0.0.2 后重新构建，`verifyJar` 直接失败，中间产物
`build/resources/paper/plugin.yml` 还停在上一轮的时间戳与 `0.0.1`。

| | |
|---|---|
| 根因 | `expand(version: project.version)` 是**任务动作**，不是任务输入；Gradle 判定 `processPaperResources` UP-TO-DATE，不重新展开，于是 jar 里仍是旧版本号。CI 因为每次都是干净构建所以不会暴露，本地反复构建才会 |
| 修复 | 在 `processPaperResources` 上声明 `inputs.property('version', project.version)` |
| 防护 | `verifyJar` 本来就断言「两份描述符都带上项目版本」，所以它抓到了这次错误——这条断言值得保留；`verify.mjs` 另加一条检查确认 `inputs.property('version'` 存在 |

#### 本轮结论

`tools/verify.mjs` 303 项、协议 33 项、本地 `gradle build`（含 51 项共享核心自测）
与 `verifyJar` 全部通过，版本 0.0.2 已发版。**但请注意**：缺陷 2 是由用户在真实环境里用出来的，
不是我们的检查发现的——这正是 2.8 节那句「不能替代上机」的含义。

### 2.10 四项联动功能（0.0.3）

在 2.9 修完缺陷后，按用户点名实现了四项功能。全部按「能被静态检查覆盖」的方式写，
因为这台机器没有 Flarum 环境，浏览器里的效果只能靠人工确认。

| 功能 | 实现位置 | 关键点 |
|------|----------|--------|
| 资料页显示已绑定的 MC 账号 | `Api\UserResourceFields.php` + `js/forum.js`（`UserPage.sidebarItems`） | 字段挂在 **user 资源**上，随帖子已有的作者数据一起到达，不必为每个作者多打一次请求 |
| 帖子/回复作者名旁的 MC 徽章 | 同上 + `PostUser.userViewItems`（优先级 95，紧跟在名字 100 之后、群组徽章 90 之前） | 同一份字段，所以资料页与徽章不可能出现不一致 |
| 进服绑定引导 | `BridgeCore.promptBindingIfNeeded` + 平台 `sendToPlayer` + 两端监听器 | 查询走异步、回复走主线程（Folia 走该玩家的 region）；论坛不可达时**保持沉默**，只在日志记 fine |
| 论坛服务器状态页 | `Api\Controller\StatusPageController`（`GET /mc-bridge/status`）+ 侧边栏入口 | 服务端渲染、无需前端构建；只展示公开 status 端点已有的聚合数据，因此游客可见 |

几个刻意的取舍：

- **可见性**：`mcBridge*` 字段的 `visible` 回调要求 `$context->getActor()->isRegistered()`，
  游客的 payload 里根本没有这些字段（不是前端隐藏，是后端不下发）。
- **查询成本**：字段按用户逐个查（一页约二十次索引查询）。不做「一次载入全部绑定」是为了
  避免扫一张随绑定数增长的表，也不做跨请求缓存是为了避免解绑后仍显示旧值。这段取舍写在了
  `UserResourceFields` 的类注释里。
- **进服引导的触发**：与 `report-joins` 开关**无关**——不记录进服事件也会引导；反过来，
  `game.prompt-unbound: false` 可以整体关掉。
- **状态页链接用普通 `<a>`**：该页由 PHP 渲染，用 Mithril 的 Link 会被前端路由拦截并要求一个
  并不存在的 JS 组件。

#### 新增的静态检查（`tools/verify.mjs` 第 18 节）

这一轮新增的失败模式是「前端读的字段/键名与 PHP 声明的对不上」——两边都合法，界面却什么都没有。
因此加了跨语言契约检查：

| 检查 | 做法 |
|------|------|
| 前端读的 user 属性必须被 PHP 声明 | 从 JS 收集 `.attribute('mcBridge*')`，与 `UserResourceFields.php` 的 `Schema\*::make('...')` 比对 |
| 前端要的翻译键必须在每个 locale 里存在 | 从 JS 收集 `translator.trans('stalirmc-mc-bridge.*')`，与两个 locale 文件的键集比对 |

为此把前端里的翻译键从模板字符串改成**字面量**（`` trans(`${EXTENSION_ID}.…`) `` → `trans('stalirmc-mc-bridge.…')`），
否则键名对静态工具不可见。

同样做了反向验证——故意把 `mcBridgePlayerName` 拼错成 `mcBridgePlayerNam`：

```
FAIL frontend attributes
     the frontend reads mcBridgePlayerNam but UserResourceFields does not declare it
errors: 1
（恢复后）errors: 0，ALL CHECKS PASSED
```

#### 本轮验证结果

- `tools/verify.mjs` **318 项**、协议 33 项全部通过
- 本地 `gradle build`（JDK 21 + Gradle 8.10）通过，`verifyJar` 报告 36 个条目
- 前端 `npm run build` 通过（forum.js 4.56 KiB）
- **仍未验证**：资料页区块、作者徽章、状态页、进服引导都**没有在真实浏览器/服务端上看过一次**。
  静态检查能保证「字段和键名对得上、路由存在、版本一致」，但保证不了「看起来对、位置合适」。

### 2.11 一次把论坛打挂的事故（0.0.4 修复）

**现象**：更新到 0.0.3 之后整个论坛 500，线上抓到的异常是：

```
BadMethodCallException: Call to undefined method Flarum\User\Guest::isRegistered()
  .../vendor/stalirmc/mc-flarum-bridge/flarum-extension/src/Api/UserResourceFields.php:30
```

日志里 `Flarum\User\Guest` 与 `Flarum\User\User` **两种都报**，说明这个方法在部署的版本上对任何 actor 都不存在。

**根因**：`isRegistered()` 是 2.x 分支后来才加的辅助方法，**2.0.0-rc.8 没有**。写这段代码时
我对照的是本地那份 framework 检出，而它来自 2.x 分支、**比线上新**——于是用了一个线上不存在的
API。更糟的是它写在 `visible` 回调里，**每个会序列化用户的请求都会命中**，首页首当其冲。

**修法**：改用 `(int) ($context->getActor()?->id ?? 0) > 0`。按 rc.8 源码核对：游客是
`Flarum\User\Guest` 且 `public int $id = 0`，并且 **`Guest extends User`**，所以
`instanceof User` 这种直觉写法挡不住游客；真实账号 id 恒为正。该判断在任意版本都成立。

顺带修掉一个尚未爆发的同类问题：`intdiv()` 收到 Carbon 3 返回的 float，已改为先取整。

**流程性修复**：`tools/verify.mjs` 第 19 节禁止 `isRegistered()`，并把**审计基准版本
`2.0.0-rc.8` 写进检查**，同时校验 `composer.json` 的 `flarum/core` 约束覆盖该版本。
写在检查里的教训是：**对照本地 framework 副本写代码不算核对，必须以部署的那个 release 为准。**

### 2.12 草方块徽章与主题内嵌的状态挂件（0.0.5）

用户反馈两点：作者名旁的 MC 徽章显示成一个「黑块」；希望服务器状态**嵌进 avocado 主题**。
两件事都先在**真实站点上用浏览器核对过 API 存在性**，而不是照本地副本推断：

| 核对项 | 结果（线上 `flarum.reg`） |
|--------|--------------------------|
| `core / common/models/User`，且原型上有 `badges()` | ✅ 存在 |
| `core / forum/components/IndexSidebar`，且原型上有 `items()` | ✅ 存在 |
| `core / forum/components/PostUser#userViewItems`、`UserPage#sidebarItems` | ✅ 存在 |
| `core / forum/components/SettingsPage` | 懒加载 chunk，`reg.get` 取不到（符合预期，字符串形式 `extend` 会等它） |
| 扩展命名空间 | `stalirmc-mc-bridge`、`ramon-avocado` |

**徽章为什么是黑块**：之前把 `span.Badge` 加在 `PostUser-badges` 列表**外面**，主题的
`PostBadges.less` 管不到它，于是一个没有图标、没有主题样式的暗色胶囊。
改为挂 **`User.badges()`**：该列表正是 `<ul class="PostUser-badges badges badges--packed">`
的内容来源，于是徽章进入主题的样式上下文，并且**同时出现在帖子作者、用户卡片、资料页侧栏**
——这就是「统一」的正确落点。图标是按用户要求画的**内联草方块 SVG**（无图片资源、无 CSS 构建、
不会 404），玩家名放在 `title`/`aria-label` 里。

**状态挂件**：`IndexSidebar.items()` 挂入一个自包含组件（`McBridgeStatus`），读公开的
`/api/mc-bridge/status`，60 秒自刷新，样式只用 Flarum 的 CSS 变量，因此**不依赖任何主题**。
选择这个挂点的依据是 avocado 的 `AllDiscussionsPage` 源码里直接渲染了
`<IndexSidebar />`（见其 `js/src/forum/components/AllDiscussionsPage.tsx`），所以挂件会出现在
该主题的侧边栏里；换成别的主题也一样有效。

**线上已确认可用**（这一轮真的跑通了）：
`GET /mc-bridge/status` 渲染正常，内容为 `survival` 在线、玩家 1/20、TPS 20.00、MSPT 5.3 ms、
版本、MOTD、在线名单与最近事件（join/start/stop）——**心跳、事件上报、状态页这条链路是真通的**。

**仍未验证**：0.0.5 的前端改动没有在浏览器里看过（需要先 `assets:publish`），
草方块的实际观感与挂件在侧边栏的位置都需要用户确认。

### 2.13 上架 Packagist（0.0.6 之后）

用户把包提交到了 <https://packagist.org/packages/stalirmc/mc-flarum-bridge>。抓取
Packagist API 核对到的实际情况：

| 核对项 | 结果 |
|--------|------|
| 收录的版本 | `v0.0.1` … `v0.0.6` 全部在列，`dev-main` 指向 `b94dfbe`（改名那次提交） |
| 默认分支 / 类型 | `main` / `library`，`extra.flarum-subextensions: ["flarum-extension"]` 正确 |
| **最高稳定版** | **`v0.0.6`** ← 因此 `composer require stalirmc/mc-flarum-bridge` 会装到当前版本 |

最后一行本来有风险：仓库里存在一个**临时留下的 `v1.0.0` tag**（指向很早的提交），一旦推到远端，
Packagist 会认为最高版本是 1.0.0，于是不带约束的 `composer require` 会装回**几个月前的代码**。
核对 `git ls-remote --tags origin` 确认它**从未推送到远端**（Packagist 只读远端 tag），
随后把本地那个 tag 也删掉了，避免以后误推。

文档相应改成 Packagist 直装：不再需要 `composer config repositories... vcs`，
后台安装也只要在「安装一个新的扩展程序」里填 `stalirmc/mc-flarum-bridge`；
`vcs` 方式保留为「装尚未发布版本」时的退路。同时修掉文档里一处改名之前就存在的笔误——
`php flarum extension:enable` 后面要填**扩展 ID**（`stalirmc-mc-bridge`），不是包名。

**实测到的同步行为**：提交后过了几分钟再去抓 Packagist API，`dev-main` 仍停在 `b94dfbe`、
抓取时间也还是包提交那一刻，说明**当前没有任何东西在自动触发抓取**。

曾试过在发版工作流里调 Packagist 的 update API（用两个 secret 触发），**已按维护者要求移除**：
发版流程保持简单，同步交给 Packagist 页面那一次点击；想省这一步就在 Packagist 侧配 webhook。

这类**发布者的运维信息**随后被移出了给安装者看的部署指南，集中到
[`RELEASING.md`](RELEASING.md)（发布流程，维护者）：部署指南只讲安装者要做的事
（`composer require` / `composer update` / `assets:publish`）。

### 2.14 删掉两项功能，并修掉管理页的两处显示（0.0.7）

维护者要求移除两项功能，理由是「远程指令没必要」「状态页在 avocado 主题里不生效」。两项都
**整套删除**，没有留下半截实现：

| 删除项 | 涉及位置 |
|--------|----------|
| 远程指令 | `BridgeConfig`（开关、白名单、校验、`isRemoteCommandAllowed`）、`BridgeCore.handleRemoteCommand` 与 outbox 的 `command` 分支、`Platform.dispatchConsoleCommand` 及两端实现、`config.yml` 两个键、两端语言文件的日志键与白名单校验键、`SelfTest` 的 14 处断言、README/插件文档/API 文档 |
| 状态页 + 侧边栏挂件 | `StatusPageController`（整文件）、`extend.php` 的论坛路由与 `use`、`McBridgeStatus.js`（整文件）、`forum.js` 里的 `IndexSidebar.items` 扩展、两个 locale 的 `forum.status` 与 `page.status` 键组、文档里的入口与说明 |

**保留**的是 `GET /api/mc-bridge/status` 这个公开只读接口：游戏内 `/mcbridge status` 仍在用它，
所以插件与论坛之间的状态数据链路没有断，只是不再有论坛侧的展示页面。

改动后自测从 51 项降到 40 项（远程指令的断言随功能一起删除），`verify.mjs` 从 320 项降到 311 项
（少了一个白名单键与相关断言），全部为 0 错误。删除过程中 verify.mjs 报了一次**未使用语言键**
警告（`config.problem.whitelist` 已无代码引用），随后一并删除。

#### 管理页的两处显示问题（来自维护者截图）

后台扩展页显示 `版本 0.0`、作者名「Stalir」的链接指向 `/admin`。两处都在 Flarum 读取
**子扩展自己的 `composer.json`** 时产生，源码依据：

| 现象 | 代码依据 | 修法 |
|------|----------|------|
| 版本显示 `0.0` | `ExtensionManager::extensionFromJson()` → `Arr::get($package, 'version', '0.0')`；`subExtensionConfsFromJson()` 读的是 `flarum-extension/composer.json`，而它当时没有 `version` | 给子包加 `"version": "0.0.7"` |
| 作者链接回 `/admin` | `Extension::getLinks()` → `authors[].link = homepage ?? (email ? mailto: : '')`；空串被浏览器解析成当前页面 | 给每个 author 加 `homepage` |

两处都加进了 `verify.mjs` 第 17 节：**子包的 `version` 必须与 `gradle.properties` 一致**（否则
管理页会显示与发布版本不符的号），**每个 author 必须带 `homepage` 或 `email`**（否则又是一个
只会刷新 `/admin` 的死链）。`RELEASING.md` 同步记录：版本号现在是**四处**一致，升级时都要改。

### 2.15 删掉服务器状态上报与游戏事件上报（未发版）

维护者要求删除「服务器状态互通」与「游戏事件上报」两项功能。删除后插件只做四件事：拉取论坛
公告并广播、把广播提交给论坛、账号绑定、未绑定玩家的进服提示 —— **不再向论坛推送任何数据**。

| 删除项 | 主要位置 |
|--------|----------|
| 服务器状态（心跳） | `BridgeCore` 的心跳构建/发送/调度与停机心跳、`statusMessage`、`HttpBridgeClient.heartbeat`/`fetchStatus`、`Platform` 的七个服务器状态 getter 与两个平台实现（含 Paper 的 `getTPS`/`getAverageTickTime` 反射）、`HeartbeatController`、公开的 `/api/mc-bridge/status`、`StatusController`、`config.yml` 的 `heartbeat-interval-seconds` |
| 游戏事件上报 | `EventQueue`（整文件）、`BridgeCore.enqueue*`/`flushEvents`、`EventController`、Paper 的退服/死亡/成就监听、Velocity 的断线监听、`config.yml` 的 `event-flush-interval-seconds` / `max-queued-events` / `report-joins\|quits\|deaths\|advancements` |

**保留** `mc_servers` / `mc_events` 两张表与其模型：不动数据库、不丢历史数据，只是不再写入
（`docs/API.md` 的数据表一节已注明）。`/mcbridge status` 子命令随功能一起删除，
`outbox` / `broadcast` / `stats` / `reload` 保留。

规模：Java 14 个文件、PHP 3 个控制器、mock 与协议测试（33 → 28 项）、`verify.mjs`（315 → 284 项）、
构建自测 40 → 37 项（其中「语言键数」的下限断言同时从 50 放宽到 40：它是防解析失败的护栏，
不是精确计数）。`gradle build`、`verify.mjs`、`protocol-test.mjs` 全部通过。

> 本次**不发版**：按维护者要求，不再为每个改动升版本号，改动只进 `main`。

### 2.16 徽章显示与属性翻译（0.0.8 – 0.0.10）

三处显示问题，都是在真实论坛上被维护者发现后定位的，结论都写进了代码注释与 `verify.mjs`：

1. **徽章文字重复**（0.0.9）：avocado 主题的 `less/forum/PostBadges.less` 用
   `.PostUser-badges .Badge::after { content: attr(aria-label); }` 把 `aria-label` 当作胶囊上的
   可见标签，其源码注释明确写着这对**任何**遵循核心约定的徽章都生效。上一版既在子元素里放了
   可见文字，又把「Minecraft 账号：xxx」放进 `aria-label`，于是两段一起被画了出来。
   正解就是核心约定本身：**`aria-label` 装标签，子元素只放图标**。
2. **悬浮提示多出一个逗号**（0.0.10）：`app.translator.trans()` 返回 vnode（多段消息是数组），
   直接交给 HTML 属性会被 `String()` 用**逗号**连接，「Minecraft 账号：{name}」于是变成
   「Minecraft 账号：,名字」。Flarum 自己的解法是 `extractText()`（核心用它生成后台下拉选项），
   它以空串拼接。凡属性位置的翻译一律过它：徽章 2 处、绑定码输入框、FieldSet 标签、后台 3 个设置项。
   `verify.mjs` 增加检查：属性值以翻译调用开头且未被 `extractText()` 包裹即失败（已做反向验证）。
3. **管理页版本显示 `0.0`、作者链接跳 `/admin`**（0.0.7）：前者因为 Flarum 读**子扩展自己的**
   `composer.json` 的 `version`，缺失时回退成写死的 `0.0`；后者因为
   `authors[].link = homepage ?? email ?? ''`，空串被浏览器解析成当前页面。两处补齐字段后
   纳入 `verify.mjs` 第 17 节强制校验。

0.0.10 曾被撤回重发一次：维护者要求作者名改为 `StalirMC`，而当时 Packagist 只抓到 `v0.0.6`，
所以撤回是干净的 —— 删除 tag 后在新提交上重建，Release 就地更新（资产已替换并实测）。

### 2.17 游戏内查公告与举报（0.0.12）

两个新功能，都由游戏侧命令发起：

| 功能 | 游戏内入口 | 论坛侧 | 新表 |
|------|-----------|--------|------|
| 查公告 | `/mcbridge news`（**无需权限**） | 复用 `GET /outbox`，插件只筛 `announcement` 并取前 5 条 | — |
| 举报 | `/report <玩家> <原因>` | `POST /api/mc-bridge/report` | `mc_reports` |

设计要点：

1. **查公告复用现有拉取**：`outboxMessages()` 是管理员看的原样列表（peek，不消费），
   `newsMessages()` 在其上只保留 `announcement` 类型并限 5 条，供普通玩家查看。
   `plugin.yml` 里 `/mcbridge` 命令整体不设权限位，把判断下移到子命令，
   于是 `BridgeCommand` 能在检查 `mcbridge.admin` **之前**先放行 `news`——否则普通玩家
   会因为父命令的权限位根本进不到这一段。Tab 补全同样按玩家权限给出不同列表。
2. **举报只写不回**：`POST /api/mc-bridge/report` 要求 `reporter_uuid` 是合法 UUID、
   `target_name` 与 `reason` 非空，落库到 `mc_reports`（初始状态 `pending`），
   玩家侧只收到一句确认；处理状态（`reviewed` / `dismissed`）由管理员在库里改。
3. **增量迁移**：`mc_reports` 与 `mc_outbox` 的 `target_uuid` / `actor_id` 两列同时写进了
   创建迁移与 `2025_06_28_000000_add_bridge_features.php`。**已装过旧版的论坛必须跑一次
   `php flarum migrate` 才会建表加列** —— 创建迁移在旧论坛上是"已执行"状态不会再跑，
   这正是那份增量迁移存在的原因（每步都有 `hasTable` / `hasColumn` 守卫，新装论坛执行它是空操作）。
4. `X-MC-Diagnostic` 头在联调里派上了用场：本机缺 PHP，但与线上论坛比对规范字符串时，
   服务端回显的 `canonical` 与本地构造的**逐字节一致**，从而确认"签名不匹配"是密钥不同，
   而不是算法或路径写错。

> **投票功能实现过两轮，最后整个去掉了。** 第一轮是自建投票
> （`mc_activities` / `mc_votes` 两张表 + `ActivityController` / `VoteController`），
> 维护者指出要的是与论坛已有的 `fof/polls` 联动；第二轮改成联动
> （读 `polls` / `poll_options`、借 `MultipleVotesPoll` 命令把票记到绑定账号名下），
> 随后维护者决定不要投票功能了。两轮的痕迹都已清除：表、控制器、`/vote` 命令与权限、
> `poll-*` / `vote-*` 语言键、mock 与协议测试的对应分组都不在代码里。
> 留这段记录是为了说清楚：**`mc_outbox` 的 `target_uuid` / `actor_id` 两列和
> `mc_reports` 表与投票无关**，增量迁移仍然必须跑。

规模：mock 与协议测试 28 → **32 项**（举报 4 项），`verify.mjs` **301 项**，
构建自测 37 项。`gradle build`、`verify.mjs`、`protocol-test.mjs` 全部通过。

**线上实测（forum.kxkl2024.cn）**：用 `DeepSeekHarness` 账号换取 Flarum token 后确认
`GET /api/mc-bridge/link` 正常返回，而当时新加的 `/api/mc-bridge/polls` 返回 `404`
—— 即论坛侧仍是旧版，这两个功能需要先部署（见第 4 节）。

### 2.18 增量迁移的闭包作用域致命错误（0.0.13）

**这是 0.0.12 的回归，由用户在真实论坛上试出来的**，也是本轮最值得记的一条。

| 项目 | 内容 |
|------|------|
| 现象 | 游戏内 `/report` 收到 `HTTP 500 ({"errors":[{"status":"500","code":"db-error"}]})` |
| 第一反应（错的） | 误判成「论坛侧扩展没升级 / 迁移没跑」——实际上扩展已是新版，**正是新版的迁移本身**把 `php flarum migrate` 打崩了 |
| 真实根因 | `2025_06_28_000000_add_bridge_features.php` 把列检查写在了 Blueprint 回调**内部**：`$schema->table('mc_outbox', function (Blueprint $table) { if (! $schema->hasColumn(...)) ... })`。**PHP 闭包不继承外层作用域**，回调里的 `$schema` 是未定义变量（`null`），于是 `Call to a member function hasColumn() on null` 致命错误 |
| 连锁后果 | 迁移每次都在中途 fatal；Flarum 的 `Migrator` 只在成功后 `log()`，所以它永远不会被标记为已执行 → 每次 `migrate` 都崩、`mc_reports` 永远建不出来 → `/report` 永远 500。自检也如实报出「缺少：mc_reports」 |
| 影响面 | `mc_outbox` 一旦存在就会调用 `$schema->table(...)`，回调随即执行，因此**新装与升级的论坛都会崩**，不只是老论坛 |
| 修复 | 列检查移到外层闭包，结果以布尔量传入回调：`function (Blueprint $table) use ($addTargetUuid, $addActorId)`；`down` 同样处理。附带好处是不再在构建 Blueprint 的过程中回头查询 schema |
| 为什么没被拦住 | `verify.mjs` 的 section 11/13 **只读 `walk(...)[0]`，即第一个迁移文件**（创建迁移），增量迁移从未被静态检查；且旧检查只看 `$this->schema` / `extends Migration`，**从不进入闭包体**。299 项全绿，却漏掉一个必崩的迁移 |

**防护（本轮新增，且已验证有效）**：

1. section 11 与 13 改为遍历**全部**迁移文件，不再只看第一个。
2. 新增**闭包作用域检查**：解析出每个闭包（含嵌套）的参数表、`use (...)` 子句与花括号配平的函数体；
   函数体引用了 `$schema`，而它既不是本闭包的参数、也没被 `use` 捕获，即判定为必崩的运行时错误。
   检查前先剥掉 PHP 注释，避免注释里的花括号干扰配平。
3. 新增**迁移漂移检查**：同一张表被多个迁移创建时，列定义必须完全一致 —— 否则新装论坛与升级论坛
   会得到两套不同 schema，而只有其中一套是被测试过的。
4. **回归自证**：把 0.0.12 那段 bug 原样塞回文件，`verify.mjs` 立刻 `FAILED`（1 个错误，
   并指名 `2025_06_28_000000_add_bridge_features.php`）；还原后恢复 298 项全绿。
   抓不到 bug 的防护等于没有防护。

**教训**：这类错误编译期完全合法、原有静态检查也看不见，只有真机 `php flarum migrate` 才会炸；
而且它对任何执行到该迁移的安装都必炸。**「本地没有 PHP 环境」使这道工序长期只由用户承担**，
这也是 CI 里 `php -l` + 迁移闭包反射那一步值得继续保持的原因。

### 2.19 两处「用了却没 import」的类引用（0.0.14）

**与 2.18 是同一个盲区的一体两面**，同样是用户在线上试出来的。

| 项目 | 内容 |
|------|------|
| 现象 | 论坛「设置 → Minecraft 账号」显示「无法读取绑定状态。」；游戏内 `/bind` 拿到的绑定码在论坛提交后无反应 |
| 日志 | 9 次 `Class "Stalir\McBridge\Api\Controller\McOutboxMessage" not found`，位置 `LinkController.php:109` 与 `:138` |
| 根因 A | `LinkController` 的 `link()` / `unlink()` 里 `new McOutboxMessage()`，但**没有 `use Stalir\McBridge\Model\McOutboxMessage;`**。PHP 按当前命名空间解析，于是去找 `Stalir\McBridge\Api\Controller\McOutboxMessage`，必然不存在 —— 绑定事务在最后一步抛错，整个 POST/DELETE 变成 500 |
| 根因 B | 同一模式还藏在 `ConfigCommand`：它用 `BridgeMessages::isAvailable()` / `SETTING_KEY` / `resolveLocale()` 却同样没 import —— 也就是 `php flarum mc-bridge:config` 一样必崩，只是这一轮没人跑到 |
| 为什么没被拦住 | `verify.mjs` 的 section 3「PHP imports resolve」只检查**正向**：每个 `use` 指向的类必须存在。它从不检查**反向** —— 用到的类有没有 import。299 项全绿，两处必崩的调用一次都没被看过 |
| 修复 | 补上两行 `use`；另外让 `LinkStatusController` 在未登录时返回扩展自己的错误结构（`link_login_required`），而不是 Flarum 的 JSON:API envelope —— 后者在前端只能落成一句无从下手的「无法读取绑定状态」 |

**防护（本轮新增，且已验证有效）**

新增**反向 import 检查**：对 `src/` 下每个文件解析出命名空间与 `use` 别名表（含 `use X as Y`），再收集所有会按当前命名空间解析类名的引用形式 ——
`new X(`、`X::`、`extends`、`implements`、`instanceof`、`catch (X`、类型声明（`Foo $bar`，含 `?Foo`）与返回类型（`): Foo`）。
若某个名字是本项目定义过的类，却既没被 import、也不与当前文件同命名空间，就判为错误。
只针对**本项目自己的类名**判定，所以 PHP 内建与 vendor 类不会误报；以 `\` 开头的完全限定名由负向后顾排除。

加完立刻抓出 `LinkController` 与 `ConfigCommand` 两处；修完 301 项全绿，且把引用形式从 3 种扩到 8 种后失败数没有增加（无新增误报）。

**顺带修的前端可诊断性**：`McBridgeSection` 原先在响应异常时只显示 `load_error` 一句文案。
现在会优先取扩展自己的 `error` 字段，其次取 Flarum JSON:API 的 `errors[0].detail` / `code`，都没有时也附上 HTTP 状态码，并 `console.warn` 出原始响应 ——
否则「会话过期」和「服务端 500」在界面上长得一模一样，正是这次排查卡住的原因。

**教训**：`verify.mjs` 里最贵的两个 bug（2.18 的闭包作用域、2.19 的缺失 import）都不是「检查写错了」，而是**检查只有单向** ——
只看「声明能否被解析」，不看「使用能否被解析」。静态检查要么做双向，要么就会成系统地漏掉一整类错误。

规模：`verify.mjs` **301 项**、协议 32 项、构建自测 37 项，全部通过。

## 3. 无法在本机验证的内容（现由 CI 覆盖）

> 本机没有 PHP / JDK，这些检查**已全部由 CI 在带 PHP 8.3 / JDK 21 的真实环境中
> 自动执行并通过**（见 2.4）。下表保留为「在任何机器上手动复核」的参考命令。

| 项目 | 命令 | 期望 | CI 状态 |
|------|------|------|---------|
| PHP 语法 | `find flarum-extension -name '*.php' -exec php -l {} \;` | 无 `Parse error` | ✅ CI 已执行通过 |
| Java 编译 | `cd mc-plugin && gradle wrapper --gradle-version 8.10 && ./gradlew build` | `BUILD SUCCESSFUL` | ✅ CI 已执行通过，jar 已上传为 artifact |
| Flarum 安装 | `composer require stalirmc/mc-flarum-bridge` | 扩展出现在管理后台 | ⬜ 需在真实论坛执行（CI 不安装 Flarum） |
| 迁移执行 | `php flarum migrate` | 6 张表建立 | ⬜ 需真实数据库（CI 仅反射校验迁移契约） |
| **桥接自检** | `php flarum mc-bridge:selftest --url=https://你的域名` | 全部 `OK` | ⬜ 需在真实论坛执行 |
| 插件加载（Paper） | 放入 jar 后启动服务器 | 日志出现 `McBridge enabled on paper as server ...` | ⬜ 需在真实服务器执行 |
| 插件加载（Folia） | 同一个 jar 放入 Folia 的 `plugins/` | 日志出现 `on folia`，且无 `UnsupportedOperationException` | ⬜ 需真实 Folia 服务端 |
| 插件加载（Velocity） | 同一个 jar 放入代理的 `plugins/` | 代理日志出现 `on velocity`，`/mcbridge stats` 平台行为 velocity | ⬜ 需真实 Velocity 代理 |
| 真实心跳 | 观察日志 / `GET /api/mc-bridge/status` | 服务器状态出现在论坛 | ⬜ 需在真实服务器执行 |

其中 `mc-bridge:selftest` 是专为此设计的：它在**运行论坛的那台机器**上检查密钥
长度、HMAC 签名/验签/篡改检测、规范化字符串格式、路径规范化（含子目录安装）、
6 张表是否存在、查询路径是否可用、绑定码字符集，并在给出 `--url` 时发起一次
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
#   /mcbridge news      -> 应列出论坛最新公告（所有玩家都可用）
#   /bind               -> 应返回 8 位绑定码
#   /report <玩家> <原因> -> 应提示已提交，论坛后台出现 pending 记录

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
