# 发布流程（维护者）

这份文档是给**发布者**看的。安装者在 [`README.md`](README.md)（部署指南）里只需要
`composer require stalirmc/mc-flarum-bridge`，不需要知道 Packagist 是怎么同步的。

## 1. 一次发版的完整步骤

```bash
# 1) 改版本号（两处必须一致，verify.mjs 会校验）
#    mc-plugin/gradle.properties      version=X.Y.Z
#    mc-plugin/src/main/java/cn/stalir/mcbridge/Version.java   VERSION = "X.Y.Z"

# 2) 本地校验（三个模块编译 + 37 项构建自测 + verifyJar + 316 项静态检查 + 41 项协议测试）
cd mc-plugin && ./gradlew build && cd ..
node tools/verify.mjs
node tools/protocol-test.mjs

# 3) 提交并打 tag —— tag 必须等于 v + gradle.properties 里的版本
git add -A && git commit -m "chore: release X.Y.Z"
git tag -a vX.Y.Z -m "McBridge X.Y.Z"
git push origin main && git push origin vX.Y.Z
```

推 tag 会触发 `.github/workflows/release.yml`，它会：

1. 校验 tag 与 `gradle.properties` 的版本一致（不一致直接失败，不会发错版本）
2. 在 JDK 21 下 `gradle build`（含 `verifyJar` 的通用 jar 内容断言）
3. 把 `McBridge-X.Y.Z.jar` 作为 GitHub Release 附件上传

CI（`.github/workflows/ci.yml`）同时会在 `main` 上跑静态检查、协议测试与 PHP lint。

## 2. 版本号的三个来源

| 位置 | 谁在用 |
|------|--------|
| `mc-plugin/gradle.properties` 的 `version` | Gradle 注入 `plugin.yml`、决定 jar 名 |
| `Version.java` 的 `VERSION` | Velocity 的 `@Plugin(version = …)` 与 HTTP User-Agent（注解只能取编译期常量） |
| git tag `vX.Y.Z` | 触发发版、决定 Release 标题 |
| `flarum-extension/composer.json` 的 `version` | **Flarum 管理页显示的版本号**（见下方说明） |

`tools/verify.mjs` 第 17 节校验这几处一致，`release.yml` 校验它们与 tag 一致。

> **为什么子包也要写 `version`**：Flarum 对 `flarum-subextensions` 读的是**子扩展自己的**
> `composer.json`（`ExtensionManager::extensionFromJson` →
> `Arr::get($package, 'version', '0.0')`）。这个字段缺失时管理页会显示一个写死的 **`0.0`**，
> 与真实版本无关。这个文件不会单独发到 Packagist，所以在这里写 `version` 没有副作用，
> 但**升版本时必须一起改**（verify.mjs 会拦住不一致）。
>
> 同一个文件的 `authors[].homepage` 决定管理页里作者名的链接：它的取值顺序是
> `homepage` → `email` → **空串**，空串会被浏览器解析成当前页面，于是点「StalirMC」只会
> 回到 `/admin`。所以每个 author 都要有 `homepage` 或 `email`（verify.mjs 也会校验）。

> 注意：`processPaperResources` 的 `expand(version: …)` 已声明为 task input，否则改了版本号
> Gradle 仍会判定该任务 UP-TO-DATE，把旧版本号打进 jar（0.0.2 发版时踩过）。

## 3. Packagist

包地址：<https://packagist.org/packages/stalirmc/mc-flarum-bridge>

**新 tag 推上去后，Packagist 需要被触发一次才会抓取。** 实测（见
[`VERIFICATION.md`](VERIFICATION.md) 2.13）：仓库没有配置 webhook，推完 commit 几分钟后
Packagist 仍停在旧提交、抓取时间没变，所以**默认不会自动同步**。

两种做法，任选其一：

- **手动**：去上面的页面点一次 **Update**（最省事，改完就能 `composer require` 到新版本）
- **自动**：在 Packagist 页面按它给出的 URL，到 GitHub 仓库 → Settings → Webhooks 配一个 webhook

> 曾在 `release.yml` 里加过一步「发布后调 Packagist update API」（读两个 secret 触发），
> **已按维护者要求移除**：发版流程保持简单，同步交给 Packagist 页面那一次点击。
> 想省这一步就在 Packagist 侧配 webhook，不需要再改本仓库。

## 4. 包名与扩展 ID（改名要三思）

Flarum 的**扩展 ID 由子包名推导**：`stalirmc/mc-bridge` → `stalirmc-mc-bridge`。它出现在
翻译域、`locale/*.yml` 根键、前端 initializer 与全部翻译键里，改包名必须一起改。

改名的代价（0.0.6 做过一次）：

| 影响 | 说明 |
|------|------|
| 已装论坛必须重装 | `composer remove <旧包>` → `composer require <新包>` → `php flarum extension:enable <新 ID>` |
| 扩展启用状态丢失 | Flarum 按扩展 ID 记录启用列表 |
| 数据与设置**保留** | 表名与设置键都与包名无关；迁移里有 `hasTable` 守卫，重跑安全 |
| 旧包名无法再安装 | Packagist 上只有新名，VCS 读的是仓库当前 `composer.json` |

## 5. 发布前的检查清单

- [ ] 扩展前端有改动 → 提醒安装者（及自己的测试站）跑 `php flarum assets:publish`，否则浏览器还是旧脚本
- [ ] 改过 `flarum-extension/` 的 PHP → CI 的 PHP lint 通过
- [ ] 改过 `mc-plugin/` 的 Java → `verifyJar` 通过（两份描述符、两个入口类、共享层零平台引用）
- [ ] 新加/改了 Flarum API 调用 → **对照部署的那个 release** 核对，而不是本地 framework 副本
      （0.0.3 用了一个 rc.8 不存在的 `isRegistered()`，把整个论坛打成 500，见 VERIFICATION.md 2.11）
- [ ] 新加/改了前端读的字段或翻译键 → `verify.mjs` 第 18 节会比对 PHP 声明与 locale
- [ ] 新加/改了前端调用的接口 → `verify.mjs` 会比对 `extend.php` 里注册的方法+路径
