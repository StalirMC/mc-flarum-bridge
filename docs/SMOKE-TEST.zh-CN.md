# 真实服务端冒烟测试

[English](SMOKE-TEST.md) · **简体中文**

`tools/smoke-server.mjs` 会用构建好的 McBridge jar 启动一个真实的 Minecraft 服务端，并断言该插件确实被启用。这是本仓库中唯一一个在服务端内部执行 jar 的检查；其他每一个都止步于「它能编译」或「jar 里包含正确的条目」。

通过 [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) 中的 `smoke` job 来运行它，该 job 会加载 `java` job 刚刚验证过的那个 jar。

## 它能证明什么

- 服务端接受该 jar：没有 `Could not load 'plugins/...'`，没有 `UnsupportedClassVersionError`，`cn.stalir.*` 上也没有 `NoClassDefFoundError`/`NoSuchMethodError`
- 插件到达 `Enabling McBridge v<version>`，并且它启用的版本就是 jar 文件名里的那个版本
- 服务端在插件存在的情况下完成启动（`Done (`）
- 服务端在其控制台上收到 `stop` 后干净地停止，并以退出码 0 退出

## 它不能证明什么

它不会把两侧连起来。它不启动 Flarum，不创建绑定，不投递公告，也不提交举报。「插件能在真实服务端上启用」是比「桥能工作」严格更弱的断言，投递路径仍然需要 [VERIFICATION.md](VERIFICATION.md) 第 4 节中的清单。

NeoForge 模组同样不在覆盖范围内：NeoForge 需要它自己的安装器步骤，所以该模组仍然只达到「能编译 + 断言 jar 包含正确的描述文件和入口点」。

## 在本地运行它

```bash
# Paper (the primary target, matching the compile-time API)
node tools/smoke-server.mjs --jar mc-plugin/build/libs/McBridge-0.0.21.jar \
  --project paper --version 1.21.1

# Folia
node tools/smoke-server.mjs --jar mc-plugin/build/libs/McBridge-0.0.21.jar \
  --project folia --version 1.21.8
```

选项：`--workdir DIR`（默认为操作系统临时目录下的某个目录）、`--timeout SECONDS`（默认 300）。`JAVA_CMD` 会覆盖 `java` 二进制文件，当你的 `PATH` 上的 `java` 不是服务端所需的 JDK 21 时，这一点就很重要。

失败时脚本会打印服务端日志的最后 40 行；CI 还会把该日志作为 `smoke-log-<project>` 产物上传。

## 版本锁定，以及为什么是这些版本

| 项目 | 锁定版本 | 原因 |
|---------|----------------|-----|
| Paper | 1.21.1（显式 `--version` 它） | 插件编译所针对的同一个版本（`mc-plugin/gradle.properties` 中的 `paperApiVersion`） |
| Folia | 1.21.8 | **Folia 不发布 1.21.1 构建。** 它的 1.21.x 系列从 1.21.4（ALPHA）开始，而 1.21.8 才是 STABLE 的那个 |

在编辑这个脚本之前，关于下载 API 有两点值得了解：

- **v2 API 已经没了。** `api.papermc.io/v2/...` 会回 **HTTP 410 Gone**；API 现在是 `fill.papermc.io/v3`，并且它**要求 `User-Agent` 请求头**（没有该请求头的请求会被拒绝）。当这个 job 开始失败而代码本身没问题时，首先要检查的就是锁定的旧端点或缺失的请求头。
- **更新的 Folia 系列需要更新的 JDK。** 1.21.11 和 26.x 版本报告的最低要求是 Java 25，而 1.21.8 报告的是 Java 21。把锁定版本往前推，就意味着也要把 CI 的 JDK 往前推。

## 记录的运行结果

两次都是在维护者的机器上针对 `0.0.21` jar、使用 JDK 21（`.tools/jdk21`）执行的，并且都通过了：

| 项目 | 构建 | 结果 |
|---------|-------|--------|
| Paper | 1.21.1 build 133 (STABLE) | `[McBridge] Enabling McBridge v0.0.21`、`Done (31.542s)!`、`stop` → `Stopping server`，退出码 0 |
| Folia | 1.21.8 build 6 (STABLE) | 插件已启用、服务端启动完成、`stop` → 退出码 0 |

这些锁定版本的运行**没有**确认「已启用」之后的任何事情：该运行使用随附的默认配置，所以插件会故意报告 `security.secret` 缺失，并暂停需要它的功能。这是预期行为，不是失败 —— 它就是全新安装的服务端所走的同一条代码路径。
