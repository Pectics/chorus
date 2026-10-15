<!--
@context
refs: ARCHITECTURE.md, sections 2 and 6; AGENTS.md, sections 2 and 3
related: settings.gradle.kts; .gitignore
sync: Keep module status, prerequisites, and commands aligned with enabled modules.
@endcontext
-->

# Chorus

基于 Minecraft 1.20.5+ 原生 `/transfer` 与客户端 Cookie 机制的分布式服务器互联组件：玩家从 Gateway
或任意具备 Sender 能力的服务器跳往目标 Receiver 时，游戏流量由客户端直连目标地址，认证结果通过短期
签名 Ticket 透传，无需常驻游戏流量中转。

> **当前状态：Core Phase 1 已实现，Common 仍为骨架。** Core 可独立签发、验证 Ticket 并持久化防重放；尚未接入 Minecraft 平台。
> [ARCHITECTURE.md](ARCHITECTURE.md) 是唯一架构定义，协作与维护约定见 [AGENTS.md](AGENTS.md)。

## 模块

| 模块 | 职责 | 当前范围 |
|---|---|---|
| `chorus-core` | 纯 Java：节点目录快照、受信任签发者、Ticket 签发与验签、身份解析策略、防重放 | Phase 1 已实现，含静态配置与本地 SQLite |
| `chorus-common` | 平台无关的 Minecraft 生命周期支撑：Cookie 单飞读取、本节点转移会话、平台 SPI | 已纳入 Git 白名单与构建，仍为骨架 |
| `chorus-paper` | Paper 适配：事件与 API 对接、命令、Cookie、Transfer、GameProfile 应用 | 后续开发，暂不收录或构建 |
| `chorus-velocity` | Velocity 适配：入口认证卸载与 Sender 能力 | 后续开发，暂不收录或构建 |
| `chorus-fabric` | Fabric 服务端适配：登录状态机、Cookie、Profile、Transfer | 后续开发，暂不收录或构建 |

依赖方向：平台模块 → `chorus-common` → `chorus-core`；核心不反向依赖任何平台 API。

本地即使存在三个平台的暂存目录，也不会被默认构建加载。平台接口、版本与资源处理将在对应阶段逐项验证。

## 环境要求

- **JDK 21+**：Toolchain 声明为 21，本机缺失时由 foojay resolver 自动下载
- 无需预装 Gradle，使用仓库内的 wrapper
- 首次准备需下载 Gradle 发行包、插件、格式化器与 Maven Central 依赖；缺失 JDK 21 时还需下载 Toolchain
- 当前构建不需要 Paper、Velocity 或 Fabric 依赖；缓存齐备后可在命令中加 `--offline`

## 常用命令

```bash
./gradlew build                  # 当前启用模块的格式检查、构建与测试
./gradlew :chorus-core:build     # 仅核心（不依赖平台 API，最快）
./gradlew :chorus-core:test      # 纯 Core 单元测试
./gradlew :chorus-core:integrationTest # 本地 SQLite 与配置闭环；build 自动包含
./gradlew :chorus-common:test    # common 测试（有测试后执行）
./gradlew spotlessApply          # 应用格式化
./gradlew spotlessCheck          # 只检查格式
./gradlew projects              # 查看实际启用的模块
```

## 开发约定

- Java 21，优先 `record` / `sealed interface` / `switch`；**不使用 Lombok**
- Gradle Kotlin DSL + Version Catalog：依赖与插件版本在 `gradle/libs.versions.toml` 维护；版本入口与例外见 `AGENTS.md` §3
- 平台 API（Paper / Velocity / Fabric）一律 `compileOnly` / `modImplementation`，不打进产物
- 公开 API 的输入约束、线程约束与错误语义随接口一起提交
- 除独立 `.md` 文档外，注释一律使用英语；以 5～10% 为注释密度指导，优先可读代码，只解释代码无法快速表达的语义
- Spotless 统一格式：Java 使用 google-java-format AOSP（4 空格），Gradle Kotlin DSL 使用 ktfmt；`check` / `build` 包含格式检查
- 测试执行不依赖网络或外部数据库服务；SQLite 使用临时文件，集成测试纳入 `check` / `build`
- 实现顺序见 ARCHITECTURE.md §6：先让 `chorus-core` 测试通过，再推进平台接入
- Git 使用白名单，只收录显式放行内容；模块内部的构建产物与缓存不收录
- Agent 主动用本地提交组织阶段与修复，尚未推送的任务历史可整理和重放；保留阶段边界，最终 push 前由人类审计并授权，详见 `AGENTS.md` §3.1
- 提交消息使用 Conventional Commits，`type` / `scope` 小写，英语说明首字母大写，例如 `feat(core): Add ticket claim models`
- 跨文件维护关系采用文件头 `@context` 引用区，格式与持续更新策略见 `AGENTS.md` §5–§6

## Core 独立使用

入口为 `CoreBootstrap.open(Path)`（默认 UTC 时钟）或可注入 `Clock` 的重载，返回 `ChorusCoreEngine`。
初始化会读取 YAML、加载密钥并打开本地 SQLite，必须在启动线程上执行；签发和验证返回 `CompletionStage`。
预期拒绝通过 `TicketRejectedException.code()` 区分，平台 I/O 线程不能 `.join()`。
关闭 Engine 会关闭其 ReplayGuard 并等待已排队工作；完成回调可能在 SQLite 工作线程执行，回调不能阻塞该线程。

Phase 1 支持 `STATIC` 目录、`LOCAL_SQLITE`、`ADOPT_UPSTREAM`、`FORCE_OFFLINE_UUID` 和
`STRICT_ONLINE_MATCH`。认证类型支持 `MOJANG`、`OFFLINE_AUTH`、`OFFLINE_RAW`；Yggdrasil、Floodgate、
共享 SQL、HMAC、压缩、通用属性与非空扩展尚未实现。`STRICT_ONLINE_MATCH` 要求平台提供真实独立认证结果。

下面是 Receiver 的 `chorus.yml`。**UNDECIDED（架构 §7.2）：寿命、时钟容差、字节限额、容量和锁等待时间均为示例，
没有隐式运维默认值。** TTL、最大寿命和时钟容差要求整秒，TTL 为正且不超过最大寿命；Cookie 限额不能超过 5120。

```yaml
node:
  id: backend
  role: BACKEND
  capabilities:
    sender: false
    receiver: true
cookie-key: chorus:ticket
crypto:
  algorithm: ED25519
  ticket-ttl: 15s
  max-ticket-lifetime: 15s
  clock-skew: 2s
  max-ticket-bytes: 4096
  max-property-bytes: 2048
registry:
  mode: STATIC
  static:
    servers-file: servers.yml
    trust-file: trusted-issuers.yml
verification:
  require-transfer-intent: true
  identity-strategy: ADOPT_UPSTREAM
  ip-binding-mode: DISABLED
replay:
  mode: LOCAL_SQLITE
  file: state/replay.db
  capacity: 10000
  busy-timeout: 1s
```

`servers.yml`：

```yaml
servers:
  gateway:
    host: gateway.example.net
    port: 25565
  backend:
    host: backend.example.net
    port: 25565
```

`trusted-issuers.yml` 的每个公钥绑定都由管理员批准；目录中的节点不会自动获得信任：

```yaml
trusted-issuers:
  gateway:
    keys:
      - kid: gateway-key-01
        public-key-file: keys/gateway.pub
    allowed-auth-types: [MOJANG]
    allowed-providers: [mojang]
    allowed-targets: [backend]
```

配置 Gateway 时，把节点 ID 改为 `gateway`，显式设置 `sender: true`、`receiver: false`；在 `crypto` 中
加入 `key-id: gateway-key-01`、`private-key-file: keys/gateway.key` 和 `public-key-file: keys/gateway.pub`，
并在根级加入签发权限（接收端仍独立裁定）：

```yaml
issuance:
  allowed-auth-types: [MOJANG]
  allowed-providers: [mojang]
  allowed-targets: [backend]
```

密钥由管理员提供：Ed25519 私钥使用 PKCS#8，公钥使用 X.509 SPKI，均支持 DER 或 PEM；加载失败不启用无签名模式。
主配置内的路径相对 `chorus.yml`，信任公钥路径相对 `trusted-issuers.yml`。
`CoreBootstrap.loadRegistries` / `reloadRegistries` 提供显式静态快照加载与替换；加载失败保留旧快照，不自动监视文件。
同一签发者可批准多个 `kid` 支持轮换，删除或禁用条目会在快照替换后撤销相应信任。

Ticket 只使用三段 Compact JWS，Header 固定 `alg: Ed25519`、`typ: chorus-ticket+jwt` 与 `kid`；
Claims 为 `version: 1`、`jti`、`iss`、`aud`、整秒 `iat/nbf/exp`、`identity` 与空 `extensions`。
身份字段沿用 Java 模型名称；UUID 为标准小写连字符形式，用户名为 1～16 个 ASCII 字母、数字或下划线。
Profile 至多一个 `textures`，可省略 `signature`，不接受 JSON null；总属性限额计入名称、值及签名的 UTF-8 字节。
登录用户名逐字符匹配，离线 UUID 映射使用票据中的原始名称，后续转移始终保留原始身份。

SQLite 使用 WAL 与 `synchronous=FULL`，消费成功必须等待持久化提交。已消费记录覆盖 `exp + clockSkew`，
达到容量或遇到磁盘、锁等待、提交错误时拒绝，不提前驱逐有效记录、不切换后端。
数据库绑定节点 ID 与清理使用的时钟容差；不匹配时拒绝启用，避免重新接受此前清理过的旧票据。
正常重启须复用持久本地目录并保留数据库及 WAL；删除状态、恢复旧备份、换空路径或复制为另一独立消费实例不在普通重启保证内。
已有库损坏不自动重建；提交后即使回调丢失或平台登录失败，也不恢复票据，需要可信上游重新签发。

## 许可

MIT，见 [LICENSE](LICENSE)。
