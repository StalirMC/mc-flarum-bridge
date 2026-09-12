# McBridge — Paper 插件

把 Paper 服务端接入 Flarum：上报状态与事件，接收论坛公告/广播，并提供游戏内
`/bind` 账号绑定。

- 目标：**Paper 1.21.x**（同 API 的 Spigot 衍生端亦可）
- Java：**21**
- 依赖：Paper API（`compileOnly`）、服务器自带的 **Gson**、Paper 内置的 Adventure

## 构建

本仓库不包含 Gradle wrapper 的二进制（`gradle-wrapper.jar`），需先生成一次：

```bash
cd mc-plugin

# 方式 A：已安装 Gradle（8.x）
gradle wrapper --gradle-version 8.10    # 生成 gradlew / gradlew.bat / wrapper jar
./gradlew build                         # Windows: gradlew.bat build

# 方式 B：直接用系统 Gradle，不生成 wrapper
gradle build

# 目标 Paper 版本不同时（会覆盖 gradle.properties 中的默认值）
./gradlew build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT
```

需要 **JDK 21**（`java.toolchain` 会自动寻找；找不到时请设置 `JAVA_HOME`）。

产物：`build/libs/McBridge-1.0.0.jar`

## 安装

1. 把 jar 放进 `plugins/`。
2. 启动一次服务器生成 `plugins/McBridge/config.yml`。
3. 填入论坛地址与共享密钥（用 `php flarum mc-bridge:secret` 生成）。
4. `/mcbridge reload`。

```yaml
language: zh_CN                            # 输出语言，默认简体中文

forum:
  url: "https://forum.kxkl2024.cn"
  api-prefix: "/api/mc-bridge"
server:
  key: "survival"
  name: "Stalir 生存服"
security:
  secret: "<至少 32 字符的共享密钥>"
```

## 语言

玩家可见的消息与插件日志**不在 config.yml 里**，而在
`plugins/McBridge/lang/<语言>.yml`。随 jar 附带：

| 文件 | 语言 |
|------|------|
| `lang/zh_CN.yml` | 简体中文（默认） |
| `lang/en.yml` | English |

首次启动会把它们解压到插件目录，可直接编辑（已有文件不会被覆盖）。

```yaml
# config.yml
language: zh_CN     # 改成 en 即切换为英文
```

**新增语言**：把 `lang/zh_CN.yml` 复制为 `lang/ja_JP.yml` 并翻译，
再把 `language` 设为 `ja_JP`。某个键若在新语言里缺失，会**自动回退到中文**，
不会显示成原始键名。

占位符用花括号，例如 `{code}`、`{reason}`；颜色代码用 `&`。

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/bind` | `mcbridge.bind`（默认所有玩家） | 申请绑定码并在聊天栏显示 |
| `/mcbridge status` | `mcbridge.admin` | 查看论坛记录的服务器状态 |
| `/mcbridge outbox` | `mcbridge.admin` | 查看待投递消息（只读） |
| `/mcbridge broadcast <内容>` | `mcbridge.admin` | 提交一条广播到论坛队列 |
| `/mcbridge stats` | `mcbridge.admin` | 本地统计（队列、失败次数等） |
| `/mcbridge reload` | `mcbridge.admin` | 重新加载配置并重启任务 |

## 运行机制

| 任务 | 默认间隔 | 作用 |
|------|---------|------|
| 心跳 | 30s | `POST /heartbeat` 上报 TPS/MSPT/在线玩家 |
| 事件刷新 | 10s | 批量 `POST /events` |
| Outbox 轮询 | 20s | `GET /outbox` 并投递公告/广播 |

- 所有网络调用都在**异步线程**执行；消息展示与指令执行回到主线程。
- 事件先入本地有界队列（默认 200 条，超出丢弃最旧的），论坛不可用时不会阻塞
  服务器，也不会无限占用内存。
- 心跳失败日志每 10 次才打印一条，避免刷屏。
- 关闭服务器时会尽力发送一次 `online=false` 心跳与 `stop` 事件。

## 安全

- 每个请求都用 HMAC-SHA256 签名，包含时间戳与一次性 nonce。
- `game.allow-remote-commands` **默认关闭**；开启后只有匹配
  `game.remote-command-whitelist` 正则的指令会被执行。
- 打开远程指令等于把游戏控制台交给任何持有 secret 的人，请谨慎。

## 源码结构

```
cn/stalir/mcbridge/
├── McBridgePlugin.java       插件入口、任务编排、outbox 处理
├── BridgeConfig.java         配置读取与校验
├── Signature.java            HMAC-SHA256 签名
├── HttpBridgeClient.java     HTTP 客户端
├── EventQueue.java           有界事件队列
├── Messages.java             消息渲染（Adventure）
├── listener/PlayerListener.java   进服/退服/死亡/成就
├── task/HeartbeatTask.java        状态上报
├── task/OutboxTask.java           公告轮询
└── command/                       /bind 与 /mcbridge
```

## 开发

本机没有 JDK 时仍可做静态一致性校验：

```bash
node ../tools/verify.mjs
```
