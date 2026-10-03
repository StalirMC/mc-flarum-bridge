# 贡献指南

[English](CONTRIBUTING.md) · **简体中文**

这里存放着两个可运行产物和一个 Flarum 扩展，而守护它们的检查并不是常见的「编译通过了」那类。本文列出该运行什么，以及哪些不变量容易被意外破坏。

## 环境要求

| 需要 | 版本 | 用途 |
|------|---------|-----|
| JDK | **21** | 构建 `mc-plugin/`（`paper-api` 1.21.1 是为 21 编译的，所以 17 无法解析它） |
| Node.js | 22 或更新 | `tools/` —— 静态、协议和冒烟套件 |
| Gradle | 8.10 | `cd mc-plugin && gradle build` |
| PHP | 8.3 + Composer | 对扩展做 lint，`composer validate` |
| 一个 JVM 大小的内存 | ~1.5 GB | 只有冒烟测试需要，它会启动一个真实服务端 |

在 Java 或 Node 一侧工作**不需要** PHP 运行时，在 PHP 一侧工作也不需要 Minecraft 服务端。CI 上这些东西都有。

## 检查关卡

在开 pull request 之前运行这些。它们都会在每次 push 时于 CI 中运行，而这些命令在本地运行时完全一样。

| 命令 | 它能证明什么 | 检查数 |
|---------|----------------|-------|
| `node tools/verify.mjs` | 两侧的结构、契约与引用：描述文件、配置键、消息键、路由与文档的对齐、迁移契约、受审计 Flarum 版本的 API 面、CI 自检 | 386 项检查 |
| `node tools/protocol-test.mjs` | 线上协议是可实现的：一个模拟论坛驱动文档化的端点，并把 Java/PHP 的行为与之比对 | 46 项检查 |
| `cd mc-plugin && gradle build` | 真实编译，加上 JVM 内自检和两项 jar 断言（`selfTest`、`verifyJar`） | 77 项自检检查 + 2 项 jar 断言 |
| `node tools/smoke-server.mjs --jar <jar> --project paper --version 1.21.1` | **真实服务端能加载并启用**该 jar —— 见 [docs/SMOKE-TEST.md](docs/SMOKE-TEST.md) | Paper 和 Folia 运行 |
| `find flarum-extension -name '*.php' -exec php -l {} \;` | 任何地方都没有 `Parse error` | 所有 PHP 文件 |
| `cd flarum-extension && composer validate --no-check-lock --no-check-publish` | 清单是可安装的 | - |

`mc-plugin/neoforge/` 是作为 `gradle build` 的一部分构建的；它是一个独立的 Gradle 项目，因为 Minecraft 依赖必须由 ModDevGradle 持有。

## 版本纪律

一个版本号存在于三个文件中，它们必须一致，否则 CI 会失败：

- `mc-plugin/gradle.properties` → `version=`
- `mc-plugin/src/main/java/cn/stalir/mcbridge/Version.java` → `VERSION`
- `flarum-extension/composer.json` → `version`

发布工作流还会拒绝不是 `v` + 该版本号的标签。要发布：把三处都提升版本、提交，然后推送标签（见 [docs/RELEASING.md](docs/RELEASING.md)）。

## 容易被破坏的不变量

这些都是强制执行的，而失败信息并不总会指出原因：

- **共享核心保持平台中立。** `cn/stalir/mcbridge` 下的任何内容（除 `paper`/`neoforge` 包之外）都不得引用 `org.bukkit`、`io.papermc`、`net.kyori`、`net.minecraft` 或 `net.neoforged`。这里扫描的是 class 文件，所以只在 javadoc 里出现的 import 不算数，但真实存在的 import 会让构建失败。Adventure 是 Paper 的东西，而 Minecraft 1.21.1 并不自带它 —— 于是才有了项目自己的 `Message` 类型。
- **每个已注册的路由都必须出现在 `docs/API.md` 中。** 路由在那里缺失时 `verify.mjs` 会失败，而 `protocol/README.md` 未提到它时会给出警告。
- **`plugin.yml` 与代码必须一致。** Java 里用到但未在 `plugin.yml` 中声明的命令或权限会失败；声明了却从未检查的会给出警告。
- **配置文件和语言文件必须保持对等。** `BridgeConfig` 读取的每个键都必须存在于 `config.yml` 中，插件使用的每个消息键都必须存在于 `lang/zh_CN.yml` 和 `lang/en.yml` 中。
- **迁移遵循 Flarum 2.x 契约**：每个文件返回一个 `['up' => ..., 'down' => ...]` 数组，其中的闭包接受 `Illuminate\Database\Schema\Builder`。一个读取 `$schema` 却没有捕获它的嵌套闭包，曾经让 `php flarum migrate` 直接致命错误；`verify.mjs` 和 CI 的反射检查都会查找它。
- **扩展通过 `app.registry` 注册**，而不是 `app.extensionData`（2.x 已将其替换），并且设置页面是懒加载的 chunk，必须按模块路径来扩展。
- **不要手工编辑 `flarum-extension/js/dist`。** 那是构建产物；请在 `flarum-extension/js` 中运行 `npm run build` 重新构建。

## 文档

- 不带后缀的文件是**英文**；对应的 `*.zh-CN.md` 是**简体中文**。这涵盖了各个 README 以及 `docs/` 下的所有内容。
- 有两个文件只有英文，因为翻译副本会漂移：`protocol/README.md` 和 `CHANGELOG.md`。
- 改变行为意味着要更新**两种**语言版本，并且两者必须保持相同的标题、相同的代码围栏和相同的表格行，以便互相 diff。
- 软件用中文打印的运行时字符串（日志行、随附的默认值）在英文文档中按原样引用，并在括号中给出释义。它们是数据，不是散文 —— 不要「把它们翻译掉」。

## 提交与 pull request

- 提交主题使用约定式前缀，与历史记录一致：`feat:`、`fix:`、`docs:`、`ci:`、`chore:`、`refactor:`、`test:`。
- 让改动保持可审查，并说明你运行了哪个关卡。其余的由 CI 跑。
- 这里的 CI 失败是真实信号：这些套件就是为了捕捉编译器捕捉不到的问题而写的，它们确实抓到过真实缺陷（一个从未注册的路由、一次版本提升后仍带着旧版本的 jar、一个什么都没采集到的聊天监听器）。
- 不要在提交、issue 或截图中包含可用的 `security.secret`、玩家个人数据或聊天记录。
