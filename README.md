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

> **当前状态：Core Phase 1 与 SQL 服务器节点注册表已实现，Common 仍为骨架。** Core 可独立签发、验证 Ticket 并持久化防重放；尚未接入 Minecraft 平台。
> [ARCHITECTURE.md](ARCHITECTURE.md) 是唯一架构定义，协作与维护约定见 [AGENTS.md](AGENTS.md)。

## 模块

| 模块 | 职责 | 当前范围 |
|---|---|---|
| `chorus-core` | 纯 Java：服务器节点注册表快照、受信任签发者、Ticket 签发与验签、身份解析策略、防重放 | Phase 1 与 SQL 服务器节点注册表已实现，信任和密钥仍在本地 |
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
./gradlew build                  # 当前启用模块的格式检查、静态分析、构建与测试
./gradlew :chorus-core:build     # 仅核心（不依赖平台 API，最快）
./gradlew :chorus-core:test      # 纯 Core 单元测试
./gradlew :chorus-core:integrationTest # 本地 SQLite 与配置闭环；build 自动包含
./gradlew :chorus-core:sqlIntegrationTest # 独立三库/TLS 验证；需本地 Docker，build 不包含
./gradlew :chorus-common:test    # common 测试（有测试后执行）
./gradlew spotlessApply          # 应用格式化
./gradlew spotlessCheck          # 只检查格式
./gradlew spotbugs               # 分析当前启用模块全部 source set，不执行测试
./gradlew spotbugsMain           # 仅分析生产代码
./gradlew :chorus-core:pitest    # PIT 变异测试；可选，不在 build 中执行
./gradlew projects              # 查看实际启用的模块
```

## 开发约定

- Java 21，优先 `record` / `sealed interface` / `switch`；**不使用 Lombok**
- Gradle Kotlin DSL + Version Catalog：依赖与插件版本在 `gradle/libs.versions.toml` 维护；版本入口与例外见 `AGENTS.md` §3
- 平台 API（Paper / Velocity / Fabric）一律 `compileOnly` / `modImplementation`，不打进产物
- 公开 API 的输入约束、线程约束与错误语义随接口一起提交
- 除独立 `.md` 文档外，注释一律使用英语；以 5～10% 为注释密度指导，优先可读代码，只解释代码无法快速表达的语义
- Spotless 统一格式：Java 使用 google-java-format AOSP（4 空格），Gradle Kotlin DSL 使用 ktfmt；`check` / `build` 包含格式检查
- SpotBugs 静态分析生产代码与测试代码，使用最大分析深度，中/高置信度告警会阻断 `check` / `build`；HTML / XML 报告位于各模块 `build/reports/spotbugs/`。`spotbugs` 包含 SQL 测试源码的编译与分析，但不执行测试或启动数据库
- 普通测试及 `check` / `build` 不依赖外部数据库服务；SQLite 使用临时文件。独立 `sqlIntegrationTest` 使用本地 Docker，首次需下载数据库与 Testcontainers 镜像
- 实现顺序见 ARCHITECTURE.md §6：先让 `chorus-core` 测试通过，再推进平台接入
- Git 使用白名单，只收录显式放行内容；模块内部的构建产物与缓存不收录
- Agent 主动用本地提交组织阶段与修复，尚未推送的任务历史可整理和重放；保留阶段边界，最终 push 前由人类审计并授权，详见 `AGENTS.md` §3.1
- 提交消息使用 Conventional Commits，`type` / `scope` 小写，英语说明首字母大写；正文含多项改动或验证时使用 `- ` 列表，详见 `AGENTS.md` §3.2
- 跨文件维护关系采用文件头 `@context` 引用区，格式与持续更新策略见 `AGENTS.md` §5–§6

## Core 独立使用

入口为 `bootstrap.ChorusCoreBootstrap.open(Path)`（默认 UTC 时钟）或可注入 `Clock` 的重载，返回 `ChorusCoreEngine`。
初始化会读取 YAML、加载本地密钥，并按配置连接 SQL、打开本地 SQLite，必须在启动线程上执行；签发和验证返回 `CompletionStage`。
预期拒绝通过 `TicketRejectedException.code()` 区分，平台 I/O 线程不能 `.join()`。
关闭装配返回的 Engine 会释放其 SQL 连接池、刷新调度器和 ReplayGuard，并等待已接收工作；若在本地工作线程的完成回调内关闭，则发起停止后返回，并在已有任务结束后释放存储。
回调不能阻塞工作线程。
防重放 API 位于 `me.pectics.chorus.core.replay`；本地实现为 `replay.local.LocalReplayGuard`，当前使用 SQLite。
`local / registry / redis` 子包按核销后端职责命名，分别对应配置枚举 `LOCAL / REGISTRY / REDIS`。

配置模型由各业务自己定义：节点位于 `node`，Ticket 编码、签发与验证位于 `ticket`，
密码学位于 `crypto`，Registry 位于 `registry`，防重放位于 `replay`，本地批准配置位于 `trust`。
每个业务的 `config` 子包实现主 `config` 中的文件/配置段加载接口；通用 YAML 读取实现位于 `config.yaml`。
配置文件来源的运行时实现位于 `registry.file` 和 `trust.file`，与配置解析职责分开。

直接构造 `engine.DefaultChorusCoreEngine` 时，分别传入
`node.ServerNodeConfiguration`、`ticket.TicketConfiguration`、`ticket.TicketIssuanceConfiguration`、`ticket.TicketVerificationConfiguration`，
随后传入服务器节点注册表、独立的本地信任对象、签发器、防重放对象与时钟。仅接收角色的签发配置和签发器可为 `null`；
签发角色的 TTL 不得超过验票最大寿命。配置模型不执行 I/O，运行资源由启动装配层创建。
Java 调用方需更新上述包路径与完整类型名；节点角色为 `node.ServerNodeRole`，身份来源为 `identity.AuthenticationSourceType`。
内置身份解析枚举为 `identity.BuiltinIdentityResolutionPolicy`，通过 `TicketVerificationConfiguration.identityResolutionPolicy()` 获取；
签发权限中的 `TicketIssuancePolicy.identityAssertionPolicy()` 表示允许声明的身份来源，与本地 Profile 解析分工独立。

Registry 是可扩展的根注册表，当前仅实现服务器节点注册表 `ServerNodeRegistry`。
Server List 是玩家展示或配置数据的表述；运行时通过同一注册表接口访问服务器节点列表配置文件或共享数据库。
`ChorusCoreEngine.getServerNodeRegistry()` 返回服务器节点注册表接口，`snapshot()` 返回不可变的 `ServerNodeRegistrySnapshot`，其中 `serverNodes()` 提供按节点 ID 索引的记录。
本地批准记录位于独立的 `me.pectics.chorus.core.trust` 包。
节点被纳入服务器节点注册表不会自动获得签发者信任，注册表刷新也不修改本地批准。Chorus 不自动分发信任策略、公钥、私钥或 HMAC 密钥。

三个配置文件各有一个完整、独立的 Schema，格式契约位于 `schema/v1/`，每个文档带有自己的版本化 `$id`：

| 配置文件 | Schema |
|---|---|
| `chorus.yml` | [chorus.schema.json](chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json) |
| `servers.yml` | [servers.schema.json](chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/servers.schema.json) |
| `trusted-issuers.yml` | [trusted-issuers.schema.json](chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json) |

各 Schema 的根节点就是对应文件的完整格式，可以单独用于编辑器或其他 Draft 2020-12 验证器。
构建时 `processResources` 将三个 Schema 压缩为紧凑 JSON，递归移除全部 `$comment`；源文件保留排版、注释与 TODO，JAR 中的资源路径和其余内容保持不变。
运行时使用 `com.networknt:json-schema-validator` 从 Core JAR 加载对应 Schema，无需联网获取。
字段结构、必填项、类型、枚举、数值范围、列表唯一性、条件规则与时长语法均在 Schema 中定义；配置格式变化优先修改对应 Schema 和契约测试。
只有启用 Sender 时才要求签发密钥与 `issuance`，只有启用 Receiver 时才要求 `replay-guard`；已出现的配置段均接受结构验证。
Java 消费验证后的值，负责转换、字段间数值关系、密钥内容和跨文件引用。校验失败不打开状态库、不替换已批准快照。
错误包含文件、JSON Pointer 字段路径及校验关键字；YAML 解析保留 1 MiB 大小、深度、重复键、别名和类型限制。

Core 支持 `CONFIG` / `SQL` Registry 来源、`LOCAL` SQLite 防重放、`ADOPT_UPSTREAM`、`FORCE_OFFLINE_UUID` 和
`STRICT_ONLINE_MATCH`。认证类型支持 `MOJANG`、`OFFLINE_AUTH`、`OFFLINE_RAW`；Yggdrasil、Floodgate、
SQL 核销、通用属性与非空扩展尚未实现；Ticket 支持 Ed25519、HMAC-SHA256 及 ZSTD / GZIP 压缩。`STRICT_ONLINE_MATCH` 要求平台提供真实独立认证结果。

下面是同一管理者互信集群中使用 HMAC 的 Receiver `chorus.yml`。算法在示例中显式指定，`crypto.algorithm` 仍为必填。
**UNDECIDED（架构 §7.2）：寿命、时钟容差、字节限额、容量和锁等待时间均为示例，
没有隐式运维默认值。** 发送端的 `issuance.ttl`、`verification.max-lifetime` 与 `verification.clock-skew` 要求整秒；TTL 为正且不超过最大寿命，Cookie 限额不能超过 5120。

```yaml
node:
  id: backend
  role: BACKEND
ticket:
  cookie-key: chorus:ticket
  max-payload-bytes: 4096
  max-property-bytes: 2048
  compression: ZSTD
crypto:
  algorithm: HMAC_SHA256
registry:
  provider: CONFIG
  config:
    servers-path: servers.yml
verification:
  trusted-issuers-path: trusted-issuers.yml
  max-lifetime: 15s
  clock-skew: 2s
  enforce-transfer-intent: true
  identity-policy: ADOPT_UPSTREAM
  ip-binding: DISABLED
replay-guard:
  backend: LOCAL
  local:
    path: state/replay.db
    max-entries: 10000
    busy-timeout: 1s
```

`servers.yml` 按节点 ID 平铺；`address` 包含主机与端口，IPv6 写作 `"[::1]:25565"`，加载时不进行 DNS 查询：

```yaml
gateway:
  address: gateway.example.net:25565
backend:
  address: backend.example.net:25565
```

`trusted-issuers.yml` 按签发者 ID 平铺；每项由本机管理员批准，服务器节点注册表中的节点不会自动获得信任：

```yaml
gateway:
  algorithm: HMAC_SHA256
  key: keys/gateway.key
  allowed-auth-schemes: [MOJANG]
  allowed-providers: [mojang]
```

配置 Gateway 时，把节点 ID 改为 `gateway`，将 `role` 改为 `GATEWAY`；在 `crypto` 中
加入 `key: keys/gateway.key`，
并在 `verification` 前加入签发权限（接收端仍独立裁定）：

```yaml
issuance:
  ttl: 15s
  allowed-auth-schemes: [MOJANG]
  allowed-providers: [mojang]
  allowed-targets: [backend]
```

`ticket.compression` 支持 `ZSTD`（默认）、`GZIP` 和 `NONE`，省略该字段时使用 ZSTD；这是已确认的编码默认值，其他运维限额仍须显式配置。
先签名，再压缩完整 JWS；Cookie 字节限额包含压缩帧，解压后 JWS 限制为 64 KiB，属性 UTF-8 预算仍独立生效。
接收端自动识别所有三种编码，本节点的压缩选择只影响签发。`NONE` 保持原有裸 JWS；旧版接收端只支持此格式，滚动升级时先升级接收端，发送端在此之前显式保留 `NONE`。

`replay-guard.backend` 的配置契约列出 `LOCAL | REGISTRY | REDIS`。
**TODO（未实现）**：`REGISTRY` / `REDIS` 防重放后端仍未实现，当前启动会在读取服务器节点注册表或打开状态库前拒绝这些选项；运行时代码和 Schema 均保留 TODO 定位。
`REGISTRY` 的设计语义为复用 `registry` 数据库，不能与 `registry.provider: CONFIG` 组合，该约束由 Schema 校验。
`LOCAL` 使用 SQLite，参数位于 `replay-guard.local`；`redis` 目前只保留空对象位置，详细配置随实现定义，见架构 §3.9 的实现状态契约。

主配置内的路径相对 `chorus.yml`；服务器节点列表配置文件位于 `registry.config.servers-path`，信任文件为 `verification.trusted-issuers-path`，批准的密钥路径相对信任文件。
`registry.config.ConfigServerNodeRegistryLoader.loadRegistry` / `reloadRegistry` 加载和替换 `registry.file.FileServerNodeRegistry` 的服务器节点注册表快照；
`trust.config.ConfigTrustedIssuerLoader.loadStore` / `reloadStore` 独立加载和替换 `trust.file.FileTrustedIssuerStore` 的本地批准记录。
重载各自完整校验后原子替换，失败保留该对象原快照；这些文件 I/O 入口须在平台事件线程之外调用，不自动监视文件。
直接装配 Engine 时可重载所注入的对象；`ChorusCoreBootstrap.open` 创建的本地信任变更通过重新打开 Engine 生效。
迁移旧主配置时，把 `registry.config.issuers-path` 移到 `verification.trusted-issuers-path`；旧位置由 Schema 拒绝，SQL 模式可删除整个 `registry.config`。
`GATEWAY` 仅签发 Ticket，`BACKEND` 仅接收 Ticket，`PEER` 同时支持两种操作。需要接收后继续转服的节点使用 `PEER`，同时配置签发密钥、`issuance` 与 `replay-guard`。
`issuance.allowed-targets` 限制本节点可以签发的目标；本机签发者文件只配置受信任签发者及其身份权限，验票时检查目标为本机。
主配置的 `node.display-name` 目前仅为描述字段，Core 不消费此值；运行时服务器显示名来自所选服务器节点注册表；`CONFIG` 未配置时使用节点 ID，SQL 表要求显式显示名。
单把验签密钥可用 `key: keys/gateway.key`，等价于 `keys: [keys/gateway.key]`；`key` 与 `keys` 至少提供一个，同时提供时只加载 `keys`。
`keys` 是密钥路径字符串列表，可保留多把同算法密钥支持轮换；接收端依次尝试这些密钥。
签发者级 `enabled: false` 会禁用其全部密钥；撤销单把密钥时从列表移除。配置变更在快照替换后生效。

上述 HMAC Gateway 首次启动时，若 `crypto.key` 文件不存在，Core 自动创建父目录并生成 32 字节随机二进制密钥；支持 POSIX 权限时新文件设为 `0600`。
后续启动复用原文件。已有文件为空、长度错误或无法读取时启动失败，不会覆盖或重新生成；写入中断遗留的无效文件需管理员修复。
管理员将生成的文件原样复制到接收节点信任条目所引用的 `keys/gateway.key`，然后按上述本地配置批准。
接收专用节点的 `crypto` 可只保留 `algorithm: HMAC_SHA256`；它从信任条目读取文件，不生成本地签发密钥，批准的密钥缺失时拒绝启动。
`crypto.algorithm` 选择本节点的签发算法，各信任条目的 `algorithm` 独立决定验签算法；同一接收节点可信任不同算法的不同签发者。
信任文件内容无需因路径迁移改变；条目省略 `algorithm` 时仍沿用 `ED25519`，HMAC 必须显式指定。
同一签发者的 `key` / `keys` 使用同一算法，优先级和轮换规则与 Ed25519 相同。
持有某签发者 HMAC 密钥的验票节点也具备生成其有效票据的能力，`BACKEND` 角色不提供密码学上的签发权限隔离。

需要分离签发与验签权限时，使用 **Ed25519**。签发端改为：

```yaml
crypto:
  algorithm: ED25519
  private-key: keys/gateway-private.der
  public-key: keys/gateway.pub
```

管理员提供 PKCS#8 私钥与 X.509 SPKI 公钥（DER 或 PEM），私钥保留在签发端；接收端只部署公钥，
将本地信任条目显式设为 `algorithm: ED25519`、`key: keys/gateway.pub`，身份权限照常配置。
公钥本身可以分发，但必须可靠确认所属签发者并由本机批准；Chorus 当前也不自动分发公钥。密钥加载失败不启用无签名模式。

Java API 按实际能力组织，范围固定为 Ed25519 与 HMAC-SHA256：`TicketVerifier` 提供验签，
`TicketSigner` 继承它并增加签发与验签视图 `verifier()`。Ed25519 使用 `Ed25519.Signer` / `Ed25519.Verifier`，
HMAC 使用封装共享密钥的 `HmacSha256`。签发器可以直接签名，无须单独配置签名服务。

```java
var sender = Ed25519.loadSigner(privateFile, publicFile);
TicketVerifier receiver = sender.verifier();
byte[] signature = sender.sign(data);
boolean valid = receiver.verify(data, signature);

var shared = HmacSha256.loadOrCreate(sharedFile);
boolean sharedValid = shared.verify(data, shared.sign(data));
```

`Ed25519.loadVerifier(publicFile)` 只加载公钥，`HmacSha256.load(sharedFile)` 只读取已同步的共享密钥。
`DefaultChorusCoreEngine` 直接接收本地 `TicketSigner`；接收专用角色可传 `null`。
`TrustedIssuer.verifiers()` 保存批准的验签视图，Ed25519 签发器传入批准记录时会收敛为仅含公钥的验签器。
配置文件和票据格式沿用上文约定。

Ticket 使用三段 Compact JWS，Header 为 `alg: Ed25519` 或 `alg: HS256`，以及固定的 `typ: chorus-ticket+jwt`；
Claims 为 `version: 1`、`jti`、`iss`、`aud`、整秒 `iat/nbf/exp`、`identity` 与空 `extensions`。
身份字段沿用 Java 模型名称；UUID 为标准小写连字符形式，用户名为 1～16 个 ASCII 字母、数字或下划线。
Profile 至多一个 `textures`，可省略 `signature`，不接受 JSON null；总属性限额计入名称、值及签名的 UTF-8 字节。
登录用户名逐字符匹配，离线 UUID 映射使用票据中的原始名称，后续转移始终保留原始身份。

SQLite 使用 WAL 与 `synchronous=FULL`，消费成功必须等待持久化提交。已消费记录覆盖 `exp + clockSkew`，
达到容量或遇到磁盘、锁等待、提交错误时拒绝，不提前驱逐有效记录、不切换后端。
数据库绑定节点 ID 与清理使用的时钟容差；不匹配时拒绝启用，避免重新接受此前清理过的旧票据。
正常重启须复用持久本地目录并保留数据库及 WAL；删除状态、恢复旧备份、换空路径或复制为另一独立消费实例不在普通重启保证内。
已有库损坏不自动重建；提交后即使回调丢失或平台登录失败，也不恢复票据，需要可信上游重新签发。

## SQL 服务器节点注册表

SQL 来源只提供服务器节点注册表，读取节点记录并发布快照；`trusted-issuers.yml`、信任策略与所有密钥始终由管理员在本地维护。
将主配置的 `registry` 段替换为下例即可，其余签发、验票和 `LOCAL` 防重放配置保持按角色填写。
**UNDECIDED（架构 §7.2）：下面的连接数、刷新间隔、缓存期限与超时都是显式示例，不是默认值。**

```yaml
registry:
  provider: SQL
  sql:
    engine: POSTGRESQL
    host: db.example.net
    port: 5432
    database: chorus
    username: chorus_user
    password-path: registry-password.txt
    tls:
      mode: VERIFY_FULL
      ca-cert: registry-ca.pem
    pool:
      max-connections: 2
      acquire-timeout: 3s
    timeouts:
      connect: 3s
      query: 5s
      socket: 10s
  cache:
    refresh-interval: 10s
    max-stale: 2m
```

`engine` 可选 `POSTGRESQL / MYSQL / MARIADB`，数据库切换不改变配置结构。`SQL` 模式无需 `registry.config`，不读取 `servers-path`。
本地信任仍由 `verification.trusted-issuers-path` 指定，与切换 Registry 来源无关。
管理员先在选定数据库执行对应的完整初始化脚本，再维护 `chorus_servers`，包括本节点及签发策略允许的所有目标：

| 数据库 | 初始化脚本 |
|---|---|
| PostgreSQL | [postgresql.sql](chorus-core/src/main/resources/me/pectics/chorus/core/registry/sql/v1/postgresql.sql) |
| MySQL | [mysql.sql](chorus-core/src/main/resources/me/pectics/chorus/core/registry/sql/v1/mysql.sql) |
| MariaDB | [mariadb.sql](chorus-core/src/main/resources/me/pectics/chorus/core/registry/sql/v1/mariadb.sql) |

脚本适用于空库，不自动重复执行或迁移；结构版本位于 `chorus_registry_schema`，服务器 ID 区分大小写。
路由表使用拆分的 `host`、`port`，IPv6 的 `host` 不带方括号：

```sql
INSERT INTO chorus_servers (node_id, display_name, host, port)
VALUES ('gateway', 'Gateway', 'gateway.example.net', 25565),
       ('backend', 'Backend', 'backend.example.net', 25565);
```

运行账号当前只需读取服务器节点记录与版本表，节点不建表、不写服务器节点记录。**TODO：未来管理/核销写操作可能要求账号隔离或凭据字段调整，当前配置不是最终权限模型。**
**TODO：PostgreSQL 的 `sql.schema` 暂不引入，本轮固定使用 `public`。** 心跳、共享信任与密钥表、SQL 核销均不在本轮实现。

`password-path` 与 `ca-cert` 相对 `chorus.yml`；密码是上限 64 KiB 的 UTF-8 文件，只去掉末尾一个 LF/CRLF，保留其余空白。
`VERIFY_FULL` 校验证书链和主机名，省略 `ca-cert` 时使用 JVM 默认信任库；私有 CA 可提供 PEM bundle。
`DISABLED` 必须移除 `ca-cert`。MySQL `caching_sha2_password` 的完整认证可能需要 TLS；实现不自动打开公钥检索。
`acquire-timeout` 是等连接池的时间，最小 500ms；`connect / query / socket` 分别限制物理连接、单条 SQL 和网络读取。
SQL 与缓存时长须为整毫秒且不超过 2147483647ms；驱动只支持秒的设置会向上取整。

启动必须成功读取第一个完整服务器节点注册表快照，结构版本、服务器节点记录或本节点/目标引用有错则启动失败。
后续后台读取成功才整体替换；失败保留旧快照，不重置期限，也不回退 `servers.yml`。
`max-stale` 从最后一次完整成功刷新开始计算，到期拒绝依赖服务器节点注册表的操作；使用单调时钟，不受系统时间回拨影响。
成功读到相同的服务器节点记录仍更新成功时间。过期只影响路由，本地信任仍可验票；SQL 刷新不重读本地信任和密钥，SQL 模式的信任变更通过重新打开 Engine 生效。
根 Registry 无法为操作提供可用数据时，使用统一的 `REGISTRY_UNAVAILABLE` 错误码；当前服务器节点注册表的访问失败映射到此错误。
注册表可访问但缺少目标节点时使用 `UNKNOWN_TARGET`；本地信任验票独立于 Registry 可用性。

Java 的 `SqlServerNodeRegistry.refresh()` 可显式异步刷新，返回 `CompletionStage`；并发调用共用当前刷新尝试。
失败异常不携带驱动原始诊断，防止输出凭据；`snapshot().stale()` 在刷新失败或超过刷新间隔时为真，超过 `max-stale` 则抛出不可用异常。
直接构造 `DefaultChorusCoreEngine` 时，SQL 协作者仍由调用方关闭；通过 `ChorusCoreBootstrap.open` 创建时自动随 Engine 关闭。

独立验证命令是 `./gradlew :chorus-core:sqlIntegrationTest`，使用测试专用 CA 与仅含 `localhost` 名称的证书，要求本地 Docker。
测试覆盖三库初始化、大小写 ID、增删改刷新、错误结构版本、查询锁超时、TLS 成功/不可信 CA/主机名不匹配，
以及 SQL 服务器节点注册表配合本地 Ed25519/HMAC 和 SQLite 的完整闭环。镜像和依赖版本在 Version Catalog 固定；当前已测 PostgreSQL 17.11、MySQL 8.4.10、MariaDB 11.4.13，不代表已验证其他版本。
测试证书及私钥仅为公开测试夹具，不用于部署；签发 CA 的私钥不入库。

## 许可

MIT，见 [LICENSE](LICENSE)。
