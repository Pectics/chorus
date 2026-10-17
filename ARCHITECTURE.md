# Chorus：基于 Minecraft 1.20.5+ `/transfer` 与 Cookie 机制的分布式服务器互联架构设计方案

> **修订版**：Draft v1.2（Core Phase 1 与本地持久化防重放）\
> **文档用途**：可独立阅读的架构与接口设计稿；不是声称所有平台 API 均已验证的实现手册。  
> **项目名称**：`Chorus`  
> **Maven Group / 包名前缀**：`me.pectics.chorus`  
> **目标环境**：Java 21+ / Minecraft Java Edition 1.20.5+  
> **开发范式**：Gradle Kotlin DSL + Version Catalog / Agent-First / VSCode 轻量审计 / CLI 测试

本文保留原有项目命名、模块骨架与开发风格，调整控制面、认证信任、Ticket 状态和职责边界。**尚未决定的产品选项不擅自定为最终结论**：特别是 SQL 与本地票据核销的最终默认策略、管理员配置分发机制、跨服主联盟的完整认证委托协议。

---

## 1. 项目背景、原始需求与核心问题

### 1.1 原始需求

Minecraft Java Edition 自 Snapshot 24w03a（对应 1.20.5 开发周期）引入原生服务器转移与客户端 Cookie 能力：

1. 服务端使用 `ClientboundTransferPacket` 要求客户端连接另一台服务器。新连接重新进入登录流程，可携带 Transfer Handshake Intent；目标服务端须支持 Transfer。
2. 服务端在 Configuration / Play 阶段向客户端写入最多 **5 KiB（5120 字节）** 的 Cookie；目标服务器可在 Login / Configuration / Play 阶段读取。Cookie 可跨 Transfer 保留，但不会作为长期磁盘登录凭据持久保存。

Chorus 利用这些机制构建**无常驻游戏流量中转的跨服互联体系**：玩家从 Gateway 或任意支持 Sender 的服务器跳往目标 Receiver 时，游戏流量由客户端直接连到目标地址；认证结果通过短期签名 Ticket 透传。

最初的需求包括：

- `chorus-core` 依据配置初始化、管理密钥、签发和验证 Ticket，并暴露与 Minecraft 平台无关的接口。
- `chorus-paper`、`chorus-fabric`、`chorus-velocity` 等模块利用平台特化 API 实现 Cookie、Transfer、登录验证与 GameProfile 写入。
- Sender 签发 JWT，存储到 `chorus:ticket`，然后发送 Transfer。
- Receiver 在完成登录前请求 Cookie、校验票据并解析出最终身份。
- 支持 Gateway、Backend、Peer 等部署方式；既适用于同一服主的集群，也为不同服主之间的受控联盟预留权限模型。

### 1.2 为什么不能强制绑定客户端 IP

例如：美国 Gateway → 日本 Peer A → 上海 Backend B。玩家可分别使用美国代理、日本代理及中国大陆直连。**出口 IP 不一致是正常情况，不构成默认风险信号。**

因此 Chorus 的基础校验不使用 IP 绑定。以后确需提供 IP 附加策略时，也必须默认关闭，并不得阻碍规则分流。

### 1.3 需要解决的问题

**身份差异**：Mojang 正版 UUID、离线 UUID、外置认证 UUID 不一定一致。Receiver 必须识别经过上游确认的身份，与目标服采用的存档 UUID 分开处理。

**授权传递**：JWT 签名正确，不等于某台服务器可以断言任意认证来源、任意用户或任意目标节点。需要明确签发者信任范围。

**节点配置**：传统 Proxy 集中维护 `servers` 列表；Chorus 没有必然存在的中央代理。需要让管理员管理节点目录，同时选择静态配置或可选的共享 SQL 控制面。

**一次性票据**：限定有效期、签发者和目标节点，避免同一 Ticket 被目标节点重复接受。其后端可以是本地 SQLite 持久化记录，也可以在有明确需要时使用共享 SQL。

**协议生命周期**：发送 Cookie 只能在 Configuration / Play 阶段；Receiver 可在 Login 阶段请求。登录成功后需进入 Configuration，才可按协议覆盖旧 Cookie。

### 1.4 v1 范围、优先级与非目标

| 优先级 | 场景 | 计划 |
|---|---|---|
| P0 | Gateway 正版认证 → 离线 Backend 继承正版 UUID 与皮肤 | 第一等场景、首版目标 |
| P1 | 正版 → 正版、离线 → 离线、正版 → 保留旧离线 UUID 存档的 Backend | 核心模型支持，平台可用性逐项验证 |
| P2 | Yggdrasil / authlib-injector | 功能扩展，优先于 Floodgate |
| P3 | Floodgate / Geyser | 预留身份表示，不承诺首版端到端转移 |

v1 **不承诺**：统一背包/经济/玩家存档、跨服数据写锁、全局单会话、所有版本的客户端 Mod 兼容、恶意客户端主动分享 Cookie 后不可冒用、全网强一致节点在线状态。

不同 Backend 在本产品边界内可视为不同服务器。游戏数据共享系统有自己的并发和持久化责任；Chorus 的本地转移中状态用于防止重复执行转移动作，不是跨服存档事务。

---

## 2. 总体工程结构与 Agent-First 开发规范

### 2.1 Gradle 多模块工程划分（保留原有骨架）

```text
chorus/
├── gradle/
│   └── libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
│
├── chorus-core/                         # 纯 Java 21；无 Minecraft 平台 API 依赖
│   └── src/main/java/me/pectics/chorus/core/
│       ├── api/                        # ChorusCoreEngine、输入输出与错误类型
│       ├── config/                     # 配置模型、校验与装配
│       ├── model/                      # ServerNode、IdentityClaim、TicketClaims 等
│       ├── crypto/                     # Ed25519/HMAC 签发与验签能力、JWS 编解码
│       ├── registry/                   # ServerNodeRegistry、TrustedIssuerRegistry
│       │   ├── config/                 # 配置文件提供的目录与信任快照
│       │   └── sql/                    # 可选的 Turso/libSQL 与 JDBC 实现
│       ├── policy/                     # UUID / Profile 身份解析策略
│       └── state/                      # ReplayGuard：仅负责 Ticket 防重放
│           ├── sqlite/                 # 本地 SQLite 持久化消费记录
│           ├── sql/                    # 可选 SQL 实现；后端选型待确定
│           └── redis/                  # 仅预留未来扩展，不进入 MVP
│
├── chorus-common/                       # 平台无关的 Minecraft 生命周期支撑
│   └── src/main/java/me/pectics/chorus/common/
│       ├── cookie/                     # SingleFlightCookieReader
│       ├── session/                    # 本节点 TransferSessionRegistry
│       └── spi/                        # ChorusPlatformAdapter、平台能力模型
│
├── chorus-paper/                        # Paper 适配；Folia 兼容单独验证
├── chorus-velocity/                     # Velocity 适配
└── chorus-fabric/                       # Fabric 服务端适配
```

**依赖方向**：平台模块 → `chorus-common` → `chorus-core`；平台模块也可直接依赖 `chorus-core`。核心不反向依赖平台 API。

`registry/sql` 和 `state/sql` 表示逻辑适配器；如相关外部依赖较多，可在未来提取为独立发布的 adapter artifact，**不要求 MVP 先增加 Gradle 子模块**。

`chorus-core` 可以使用通用 Java 库、数据库驱动和密码学库，但不能持有 `Player`、`GameProfile` 或 Netty `Channel` 等平台对象。数据库 I/O 与定时调度通过接口封装。

### 2.2 Agent-First 规范

- Java 21，优先原生 `record`、`sealed interface`、`switch`；不使用 Lombok。
- Gradle Kotlin DSL、Version Catalog、JDK Toolchain；不依赖特定 IDE。
- 公开 API 文档、输入约束、线程约束和错误语义必须随接口实现一起提交。
- `./gradlew :chorus-core:test` 运行纯 Core 快速单元测试。
- SQL 并发、网络故障、Turso/JDBC 测试放在独立集成测试任务；**不保证全部测试两秒内结束**。
- Agent 必须先让 Core 模块测试通过，再推进平台接入；不应同时生成未经验证的三个平台完整实现。

### 2.3 模块职责

| 模块 | 职责 | 明确不负责 |
|---|---|---|
| `chorus-core` | 节点目录快照、受信任签发者、Ticket、验签、策略、UUID 解析、ReplayGuard | 收发 Minecraft Packet、PlayerQuit、保存背包 |
| `chorus-common` | 单连接 Cookie Single-Flight、本地玩家身份会话、转移中状态、平台 SPI | 共享数据库会话锁、核心签名算法 |
| `chorus-paper` | Paper 事件与 API 对接、命令、Cookie、Transfer、Profile 应用 | Core 密码学实现 |
| `chorus-velocity` | 入口网关或可用能力内的 Sender/Receiver，异步登录对接 | 假定具有与 Paper 相同的登录 API |
| `chorus-fabric` | Fabric 登录状态机与 Cookie、Profile、Transfer 集成 | 将 Mixin 直接编入 Core |

### 2.4 角色决定发送和接收能力

节点通过必填的 `role` 选择固定的操作范围：

| 角色 | 签发 Ticket | 接收 Ticket |
|---|---|---|
| `GATEWAY` | 是 | 否 |
| `BACKEND` | 否 | 是 |
| `PEER` | 是 | 是 |

接收玩家后还需要继续转服的节点使用 `PEER`。不再提供独立的发送、接收能力开关；角色只决定操作是否可用，签发与接收仍分别受本节点签发策略、接收端信任策略约束。

---

## 3. `chorus-core` 核心设计

### 3.1 控制面：谁维护权威 `servers` 列表？

**已确认需求**：管理员维护服务器列表；共享 SQL 是默认倾向但**不是 Chorus 协议运行的必需组件**。静态模式必须可行。

提供两个互斥的**目录来源模式**：

1. **`CONFIG`**：每台 Chorus 节点读取管理员提供的本地 `servers` 与 `trusted-issuers` 配置。不存在自动同步权威；管理员负责分发与更新一致的配置，可用 Git/配置管理工具辅助。它在技术上可工作，不需要“中央 Proxy”。
2. **`SHARED_SQL`**：同一管理域用数据库作为权威节点目录。**数据库表由管理员变更**；节点启动不自动插入/批准可信节点。服务器运行时获取可信快照，使用本地缓存解析目标地址并获取已批准的验签密钥绑定。Turso/libSQL 是推荐适配实现之一，标准 JDBC 是可选后端。

两种模式使用相同的 Core 读取接口；目录选择由 `registry.provider` 表达，`CONFIG` 模式的文件路径位于 `registry.config`。
防重放由 `replay-guard.backend` 表达；`REGISTRY` 后端复用所选目录数据库。

跨服主联盟中不必要求所有服主共用一个具有写权限的数据库。可为每个管理域维护自己的目录，再在本地明确授权**外域签发者**。本地管理员选择信任哪些外域公钥和身份类型。

#### 管理命令（**候选方案，尚未最终确认**）

平台层可以提供 `chorus server list/add/update/remove`、`chorus trust list/add/revoke` 等管理命令，经平台权限检查后交给 `registry` 的管理员写入接口。对于 `CONFIG`，仅更新**本机配置**；对于 `SHARED_SQL`，写入管理域权威数据库。没有共享存储时，命令不会神奇同步到其他节点。

权限验证发生在平台管理界面；是否采用游戏内指令作为正式配置入口，仍待确定。无需首先开发网页后台。

#### 注册与信任分离

- **`ServerNodeRegistry`**：节点 ID、显示名和连接地址，解决“往哪里转”。
- **`TrustedIssuerRegistry`**：由本机管理员批准的签发者、算法、验签密钥列表与身份来源范围，解决“本机信任谁”。
- 心跳信息只用于 UI 与路由提示，不是授予身份签发权限的凭据。
- 节点上报的候选公钥不能自动成为受信任公钥；密钥轮换需批准或受可信旧密钥授权的轮换流程。

**管理员权限约束**：如果多个服务器拿着相同的全写数据库令牌，插件代码中“不要修改其他节点”的约定不是真实安全隔离。管理员写操作与节点运行时读操作应采用可用的权限隔离。若所选后端不支持细粒度数据库权限，必须明确共享写凭据等同于信任整个持有者集合，不能宣称数据库自动同步机制本身防止节点提权。

#### 缓存与数据库故障

- 上次**经过批准**的目录与验签密钥快照可以留在内存中；共享数据库临时不可用时，继续使用该快照执行静态路由与本地验签。
- 缓存中不存在的新节点、陌生公钥、未经批准的密钥变更，一律不因数据库故障而自动被接受。
- 快照的最大可用陈旧时间由配置控制；过期则拒绝依赖该信息的新转移。**撤销密钥传播会受缓存期限影响**，文档必须公开此取舍。
- 节点冷启动且数据库不可用、又没有可验证的本地可信快照时不能凭空发现其他服务器；可使用管理员提供的静态备用快照。
- **不能把 SQL 票据核销错误自动降级为本地核销**：两个后端不共享消费记录，会造成票据重复接受。控制面缓存策略与数据面 ReplayGuard 故障策略是两回事。

### 3.2 `registry` API 设计

节点目录只保存节点 ID、显示名、主机名与端口；分组标签和通用元数据不纳入当前配置及模型。

```java
package me.pectics.chorus.core.registry;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record ServerNode(
    String nodeId,             // 全域唯一；建议约定 namespace/nodeId
    String displayName,
    String host,
    int port
) {}

public record RegistrySnapshot(
    Map<String, ServerNode> servers,
    Instant refreshedAt,
    boolean stale
) {}

public interface ServerNodeRegistry {
    RegistrySnapshot snapshot();         // 热路径读取已验证快照，不做阻塞 SQL
    Optional<ServerNode> find(String nodeId);
}
```

信任信息与路由信息分离：

```java
package me.pectics.chorus.core.registry;

import java.util.Optional;

public interface TrustedIssuerRegistry {
    Optional<TrustedIssuer> findByIssuer(String issuerNodeId);
}
```

`TrustedIssuer` 内含不可变验签器列表（各自封装批准的密钥）、签发者整体启用状态，以及允许的 `AuthType` / `authProvider` 策略；未知签发者返回 `Optional.empty()`。
本机批准某个签发者即表示允许其在这些身份权限内向本机提供 Ticket，不再配置接收端目标列表；验票仍须检查 `aud` 等于本机节点 ID。
同一签发者可在轮换期间保留多把同算法的可信密钥，所有密钥共用签发者的启用状态与策略；撤销单把密钥时将其从列表移除。
静态信任条目的 `algorithm` 为 `ED25519` 或 `HMAC_SHA256`，省略时沿用 `ED25519`；HMAC 必须显式批准。同一签发者的全部密钥绑定同一算法，不根据文件内容或票据 Header 自动转换类型。
静态配置的 `key: path` 等价于 `keys: [path]`，两者至少提供一个；同时提供时只加载 `keys`，不合并或回退到 `key`。
配置、API 与 Ticket 均不使用密钥标识，接收端按签发节点 ID 查找批准记录；签发者启用时依次尝试其批准密钥，验签后使用该签发者的策略。

写入型管理接口另行声明，例如 `ServerDirectoryAdmin` / `TrustAdmin`，不并入面向所有运行节点的公开只读接口。心跳采集可通过独立 `NodePresenceReporter` 实现，**不应让 `registerSelf()` 覆盖管理员的 ServerNode 定义**。

### 3.3 共享数据库存储草案（共享目录与 `REGISTRY` 防重放后端的实现草案）

此处 SQL 示例以 SQLite/libSQL 兼容方言表达。MySQL/PostgreSQL 必须实现对应的 dialect、迁移及并发测试，不能认为 `DELETE ... RETURNING` 在所有 JDBC 数据库中通用。

```sql
-- 管理员维护的权威节点目录；运行时心跳不得覆盖本表
CREATE TABLE chorus_servers (
    node_id        TEXT PRIMARY KEY,
    display_name   TEXT NOT NULL,
    host           TEXT NOT NULL,
    port           INTEGER NOT NULL,
    role_hint      TEXT,
    enabled        INTEGER NOT NULL DEFAULT 1,
    updated_at_ms  INTEGER NOT NULL
);

-- 管理员批准的信任条目；示例为按接收节点授权
CREATE TABLE chorus_trusted_issuers (
    receiver_node_id        TEXT NOT NULL,
    issuer_node_id          TEXT NOT NULL,
    public_keys_ed25519_json TEXT NOT NULL,
    policy_json             TEXT NOT NULL,
    enabled                 INTEGER NOT NULL DEFAULT 1,
    updated_at_ms           INTEGER NOT NULL,
    PRIMARY KEY (receiver_node_id, issuer_node_id)
);

-- 可选的节点观测数据；不用于授权
CREATE TABLE chorus_node_presence (
    node_id          TEXT PRIMARY KEY,
    online_players   INTEGER,
    max_players      INTEGER,
    last_heartbeat_ms INTEGER
);
```

上述 `chorus_node_presence` 只有在能合理限制节点写入权限时才作为共享心跳实现。单纯共享一个全写令牌不能安全阻止节点篡改其他节点的状态；若无法安全实现，可先禁用心跳写入，手动目录仍然可用。

**条件性扩展：** 若最终选择 SQL 票据核销，才增加如下数据表，不在只使用共享目录时创建：

```sql
CREATE TABLE chorus_tickets (
    jti              TEXT PRIMARY KEY,
    issuer_node_id   TEXT NOT NULL,
    target_node_id   TEXT NOT NULL,
    expires_at_ms    INTEGER NOT NULL
);
CREATE INDEX idx_chorus_tickets_expires
    ON chorus_tickets(expires_at_ms);
```

`chorus_sessions` 全局交接锁表不纳入 MVP。若未来真正需要可选全局会话协调，必须先独立定义故障恢复、租约过期、fencing token 与共享数据写入方的责任，再另拟规格。

SQL ReplayGuard 可在具备所需语义的数据库中使用原子条件删除，例如 SQLite/libSQL 的 `DELETE ... RETURNING`，但不能据此假设网络故障时调用方总能知道事务是否提交；重复请求需要按**结果未知**与**已使用**处理，不能放行。

### 3.4 `model`：玩家身份与 Ticket

#### 认证来源及交付优先级

```java
package me.pectics.chorus.core.model;

public enum AuthType {
    MOJANG,
    OFFLINE_AUTH, // 由适配器确认上游密码插件实际完成了认证
    OFFLINE_RAW,  // 仅为未独立认证的离线名称声明
    YGGDRASIL,   // 后续 Feature
    FLOODGATE    // 预留类型；不代表 Bedrock Transfer 已支持
}
```

删除 `AuthType(100/80/50/10)` 这类内置、容易被误用的绝对信任分值。身份来源的可信程度不是由枚举常量决定，而是由 Receiver 对具体签发者和提供者的策略决定。

#### 身份声明

```java
package me.pectics.chorus.core.model;

import java.util.List;
import java.util.UUID;

public record ProfileProperty(
    String name,
    String value,
    String signature
) {}

public record IdentityClaim(
    String username,
    AuthType authType,
    String authProvider,       // 规范化的提供者标识，如 "mojang"
    String providerSubject,    // 该来源内稳定的账户标识；与 GameProfile UUID 分离
    UUID sourceUuid,           // 首次可信入口确认的 Profile UUID
    List<ProfileProperty> properties
) {}
```

- `providerSubject` 与 `authProvider` 一起给出认证命名空间。Mojang 情况下可由正版 UUID 构成；离线身份只能代表上游服实际完成的认证强度。
- `sourceUuid` 与目标服最终的 `finalUuid` 是不同概念。下一跳转服保留原身份声明，而不是把刚刚映射出来的离线 UUID 覆盖回源身份。
- 旧文档的 `mojangUuid`、`offlineUuid` 不再强制每张票据都带；`offlineUuid` 可由明确的兼容策略按用户名确定性生成，省去冗余字段。
- Phase 1 只支持至多一个 `textures` 属性，并设置 UTF-8 字节上限；不把上游属性当成经过 Mojang 独立背书的证明。

#### 已验证身份与登录上下文

```java
public record AuthenticatedIdentity(
    IdentityClaim claim,
    String validatedByNodeId
) {}

public record ReceiverLoginContext(
    String loginUsername,
    boolean transferred,
    VerifiedExternalIdentity independentlyVerified  // 可为 null
) {}

public record VerifiedExternalIdentity(
    String authProvider,
    String providerSubject,
    java.util.UUID verifiedUuid
) {}
```

`AuthenticatedIdentity` 由已完成认证的平台入口或已经验证的 Transfer 会话生成，不能直接由玩家 Login Start 自报 UUID 构造。

`VerifiedExternalIdentity` 仅在 Receiver **确实完成**独立的 Mojang/Yggdrasil 认证后由平台填入。不能把未认证的 `loginPacketUuid` 误标为已验证身份。

#### Ticket Claims

```java
public record TicketClaims(
    int version,
    String jti,
    String issuerNodeId,
    String targetNodeId,
    java.time.Instant issuedAt,
    java.time.Instant notBefore,
    java.time.Instant expiresAt,
    IdentityClaim identity,
    java.util.Map<String, String> extensions
) {}
```

约定：

- 默认单 Cookie：`chorus:ticket`。
- 使用 JWS Compact Serialization，支持 Ed25519（RFC 9864 的 `alg=Ed25519`）及互信部署可选的 HMAC-SHA256（`alg=HS256`）；不接收旧 `EdDSA` 标识或无签名算法。
- JWT 标准 `iat` / `nbf` / `exp` 使用 **NumericDate 秒**；Java API 使用 `Instant`，配置 TTL 使用 `Duration`，不要把自定义毫秒直接写进标准时间字段。
- `jti` 用密码学安全随机值（如 UUIDv4）即可，不强制 UUIDv7。
- `iss` / `aud` 使用集群中明确约定的唯一节点 ID；跨管理域建议使用全域命名，如 `operator-a/gateway-01`。
- 默认不包含 IP Hash 和 `sessionEpoch`；没有共享数据协调方时，Ticket 携带 `sessionEpoch` 不能自动防止跨服存档并发写入。
- Phase 1 保留 `extensions` 接口但拒绝非空扩展；后续扩展必须经过授权、限定长度与命名空间，不能覆盖标准 Claim 或绕过接收端策略。
- Cookie 最大 5120 字节；`core` 应检查**最终编码后的字节长度**。示例项目限额可取 4096 字节，但不是协议强制值。
- 默认使用 `ZSTD` 压缩 Ticket，可显式选择 `GZIP` 或 `NONE`；压缩帧必须带版本标记，并限制最终字节数与解压大小。

压缩编解码保持完整的 version-1 Compact JWS 签名格式，先签名，再压缩完整 JWS。
`NONE` 继续使用原有 ASCII JWS，不增加帧；`ZSTD` / `GZIP` 使用二进制帧：
`CHRS`（4 字节 ASCII）+ 帧版本 `0x01` + 压缩标识（`0x01` 为 ZSTD，`0x02` 为 GZIP）+
解压后 JWS 字节数（4 字节大端正整数）+ 对应标准压缩数据。帧版本与 Claim 的 `version` 分开校验。
压缩配置只决定签发编码；接收端始终识别三种编码，沿用同一信任、算法绑定及验签流程，不递归解压嵌套帧。
完整 Cookie（含压缩帧）受 `max-payload-bytes` 约束；解压后 JWS 另有 64 KiB 硬上限，先检查声明长度，
再进行有界解压并核对实际长度，不按压缩流自报大小分配内存。属性 UTF-8 预算与 JSON 深度、重复键检查仍独立生效。
未知帧版本拒绝为 `UNSUPPORTED_VERSION`；未知压缩标识、损坏或截断流、无效长度拒绝为 `MALFORMED_TICKET`，
Cookie 或声明的解压长度超过上限拒绝为 `TICKET_TOO_LARGE`，不回退尝试其他压缩算法。

**密钥选择**：按 `iss` 查找本地批准的签发者，拒绝禁用的签发者，校验 `alg` 与该签发者批准的算法完全一致，并依次尝试其批准密钥；不得将 Ed25519 公钥转换成 HMAC 密钥，不得尝试其他签发者的密钥或使用 JWT 中的任意 URL 下载密钥。新节点上传公钥不会自动扩大信任集合。

### 3.5 `policy`：身份解析与联盟信任

将两类逻辑严格分开：

1. **`IssuerTrustPolicy`**：决定本机是否接受某签发节点断言的 `authType + authProvider` 身份；由 Receiver 配置裁定。Sender 使用独立的 `IssuancePolicy`，在身份权限之外增加允许签发的目标列表。
2. **`IdentityResolutionPolicy`**：已授权后，决定本地 GameProfile UUID 与皮肤属性。

```java
package me.pectics.chorus.core.policy;

import me.pectics.chorus.core.model.IdentityClaim;
import me.pectics.chorus.core.model.ProfileProperty;
import java.util.List;
import java.util.UUID;

public record ResolvedPlayerProfile(
    UUID finalUuid,
    String finalUsername,
    List<ProfileProperty> properties,
    IdentityClaim originalIdentity
) {}

public interface IdentityResolutionPolicy {
    ResolvedPlayerProfile resolve(IdentityClaim identity);
}
```

| 策略 | 用途 | 关键语义 |
|---|---|---|
| **`ADOPT_UPSTREAM`（默认）** | Gateway 正版认证 → 离线 Backend | 接收端忽略自己初步计算的 Offline UUID，采用上游 `sourceUuid` 和允许的皮肤属性 |
| **`FORCE_OFFLINE_UUID`** | 正版 → 历史离线存档 | 使用 `UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(UTF_8))`，需事先确认旧存档命名规则与插件兼容性 |
| **`FEDERATED_NAMESPACE`** | 多认证命名空间 | 使用稳定命名空间与无歧义编码的 `(authType, authProvider, providerSubject)` 派生 UUIDv5；**解决 UUID 冲突，不等于解决同名在线冲突或证明离线账户所有权** |
| **`STRICT_ONLINE_MATCH`（可选）** | 双正版/同源外置验证 | 需平台提交真实独立验证的 `VerifiedExternalIdentity`，Core 比对来源及 UUID；不在 Core 内调用 Mojang 网络 API |

在 `FORCE_OFFLINE_UUID` 场景，皮肤与安全聊天/客户端 Mod 的兼容性依平台和版本实测，不应保证所有插件无差别工作。

#### 互信集群与受控联盟

- **私人互信集群**：管理员可给指定 Sender 较宽的身份断言权限，例如 Gateway 可签发入口身份，可信 Peer 可重新签发已接受的身份。节点签名密钥泄露后，对该密钥所批准的签发者权限范围承担相应风险。HMAC 的每个共享文件持有者均可签发相同密钥覆盖的身份声明，不能用共享密钥在这些持有者之间隔离签发权限。
- **跨服主联盟**：默认不把外域节点等同于内部 Gateway。每个接收节点独立授权签发者、验签密钥与身份来源；`OFFLINE_RAW` 不应自动拥有 Mojang 断言权限。
- **关键边界**：仅凭单层新签发 JWT，Receiver 无法密码学证明“外域 Peer 转发了原 Gateway 已验证的真实身份，而不是自己捏造同样的身份”。如果希望允许不完全可信节点中转官方身份，必须另行设计**原始身份凭证（Identity Attestation）+ 本次转移授权**的分层签名机制，或让 Receiver 自行复验；这项能力**尚未确认纳入 MVP**。

签发者权限不取决于 Ticket 自己携带的数字 `trustLevel`。

### 3.6 `state`：Ticket 防重放，不管理背包会话锁

原设计 `TicketStateCoordinator` 同时执行票据核销、等待源服退出、远程强踢。此方案存在“源服心跳 10 秒、目标服等待 3 秒”的时序冲突、先核销后等待导致的失败恢复问题，也把游戏数据保存纳入了身份验证。**v1 移除强制跨服会话栅栏**。

保留独立 SPI：

```java
package me.pectics.chorus.core.state;

import java.time.Instant;
import java.util.concurrent.CompletionStage;

public enum ReplayDecision {
    ACCEPTED,
    REPLAYED,
    UNAVAILABLE,
    CAPACITY_EXCEEDED
}

public interface ReplayGuard {
    CompletionStage<ReplayDecision> consume(
        String issuerNodeId,
        String jti,
        String targetNodeId,
        Instant expiresAt
    );
}
```

**本地后端 `LOCAL`（SQLite）**：`aud` 指向一个接收节点及其持久数据库。Receiver 完成其他校验后，以唯一约束原子插入已消费记录；Sender 无须预登记。采用 WAL 与 `synchronous=FULL`，确认事务持久化提交后才成功完成 `CompletionStage`，无需逐票等待 checkpoint。记录至少保留到 `exp + clockSkew`，容量不足不能驱逐仍可能有效的记录。数据库 I/O 在专用执行器上执行。本地状态绑定节点 ID 与清理使用的时钟容差，发现不匹配则拒绝启用。正常进程重启复用原数据库，不需要启动隔离窗口；数据库及 WAL 必须保存在持久本地存储，删除、回滚旧备份或换空路径不属于普通重启保证。已有数据库损坏、提交失败或结果未知时拒绝，不自动重建或降级内存。

**`REGISTRY` 后端（TODO，未实现，最终默认未定）**：该后端复用 `registry` 配置的数据库，不能用于 `CONFIG` 目录；预登记方案中 Sender 在发包前先登记高熵 `jti`，Receiver 进行数据库原子条件消费。SQLite/libSQL 或 PostgreSQL 可使用经验证的 `DELETE ... RETURNING` 方案；MySQL 实现须采用符合自身方言的原子事务/受影响行数方案。数据库不可用时应拒绝，**不得自动退回另一核销后端**。

**待确认选型**：共享 SQL 对节点目录是否有用，与 Ticket 是否应注册/核销于 SQL 是两个独立决策。MVP 可以先实现本地 SQLite ReplayGuard 以测试核心协议；这不代表最终产品默认一定选本地核销。

Phase 1 的运维参数保持显式配置（§7.2）；不提供运行中修改时钟容差的热更新。

**Bearer 边界**：签名和 JTI 一次性消费阻止篡改及重复使用，但不会证明“首次提交的人”一定是原玩家。JWE 也不能防止密文 Ticket 被原样复制。Chorus 不要求原版客户端提供不存在的自定义私钥持有者证明；可选传输保护属于未来平台增强。

### 3.7 `crypto`：密钥与签名

密码学范围固定为 **Ed25519 与 HMAC-SHA256**。按两者的实际能力组织对象，不提供通用算法插件、可替换签名服务或为其他非对称算法预留层级。

```java
public sealed interface TicketVerifier permits Ed25519.Verifier, TicketSigner {
    SignatureAlgorithm algorithm();
    boolean verify(byte[] data, byte[] signature);
}

public sealed interface TicketSigner extends TicketVerifier
        permits Ed25519.Signer, HmacSha256 {
    byte[] sign(byte[] data);
    TicketVerifier verifier();
}
```

共同抽象是操作能力：验签器只要求验签，签发器在其上增加签发。`Ed25519` 内聚密钥对签发器 `Signer`、公钥验签器 `Verifier` 与对应文件加载；`Signer` 组合一个 `Verifier`，通过 `verifier()` 独立交出仅含公钥的验签能力。`HmacSha256` 封装同一共享密钥的签发与验签，`verifier()` 返回自身；不暴露原始密钥，输入字节防御性复制。

两种实现自行完成密码操作，每次调用独占一个 JCA 实例；HS256 使用常量时间比较。`SignatureAlgorithm` 只是这两个配置名称与 JWS 标识的封闭映射，不承担实现注册或策略分派。

`TicketCodec` 直接调用对象的 `sign` / `verify`，无须额外签名服务或按密钥类型分支。Engine 直接持有一个本地 `TicketSigner`（接收专用角色可为空）；受信任签发者保存不可变 `TicketVerifier` 列表并提供批准的算法，传入签发器时通过 `verifier()` 归一到验签视图，避免批准记录保留 Ed25519 私钥，并按归一后的材料检查重复。验票捕获同一批准记录用于验签和策略检查，保留轮换、禁用、算法绑定与快照替换语义。密钥装配在启动阶段完成，本地签发材料的替换通过重新装配 Engine 生效。

密钥轮换：一个签发者可同时批准多把同算法验签密钥，签发端使用当前本地密钥；撤销通过受信任配置传播，数据库故障缓存期的撤销延迟必须被接受并记录。密钥加载失败时禁止以无签名模式继续。

Ed25519 继续加载管理员提供的 PKCS#8 私钥与 X.509 SPKI 公钥（DER / PEM）。HMAC 的 `crypto.key` 指定原始 32 字节 `.key` 文件：签发角色在文件不存在时用密码学安全随机数生成，创建父目录，以排他方式创建文件，并在文件内容持久化后返回；支持 POSIX 权限时新文件设为 `0600`。已有文件直接复用，空文件、长度错误、读取或写入失败均拒绝，不覆盖或自动重置已有密钥。写入中断可能留下无效文件，后续启动仍拒绝，需管理员修复。

管理员将同一 `.key` 文件同步给需要信任该签发者的节点；信任加载与重载只读取文件，缺失时不生成替代密钥。接收专用节点不生成本地签发密钥。先验证所有配置文件的 Schema 和目录目标引用，再生成本地签发密钥、加载批准密钥，最后打开防重放状态；因此 Peer 可在同一次启动中引用刚生成的本地密钥。当前 HMAC 支持静态文件分发，不新增共享 SQL 的密钥存储设计。

### 3.8 `ChorusCoreEngine` 对外 API

既可能只有本地密码学计算，也可能使用 SQL，因此核心接口以 **`CompletionStage` 为统一异步契约**。平台不得在 Netty EventLoop 中 `.join()` 或无超时阻塞。纯本地密码学计算可返回已完成的 Future；SQLite 消费必须等持久化提交确认。

```java
package me.pectics.chorus.core.api;

import me.pectics.chorus.core.model.AuthenticatedIdentity;
import me.pectics.chorus.core.model.ReceiverLoginContext;
import me.pectics.chorus.core.registry.ServerNode;
import me.pectics.chorus.core.registry.ServerNodeRegistry;
import me.pectics.chorus.core.policy.ResolvedPlayerProfile;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public record IssueTicketRequest(
    String targetNodeId,
    AuthenticatedIdentity identity,
    Map<String, String> extensions
) {}

public record IssueTicketResult(
    ServerNode targetServer,
    String cookieNamespacedKey,
    byte[] cookiePayload,
    String jti,
    Instant expiresAt
) {}

public record VerifyTicketRequest(
    byte[] cookiePayload,
    ReceiverLoginContext loginContext
) {}

public record VerifyTicketResult(
    ResolvedPlayerProfile resolvedProfile,
    AuthenticatedIdentity retainedIdentity,
    String jti
) {}

public interface ChorusCoreEngine extends AutoCloseable {
    ServerNodeRegistry getServerRegistry();
    String getCookieKey();
    CompletionStage<IssueTicketResult> issueTicket(IssueTicketRequest request);
    CompletionStage<VerifyTicketResult> verifyAndResolve(VerifyTicketRequest request);
}
```

示例是接口边界，不含平台适配器实现；`byte[]` 在构造/访问时应防御性复制，集合使用不可变快照。

`issueTicket()` 顺序：

1. 检查本节点 Sender 能力及目标路由授权；查找已批准的目标节点快照。
2. 从可信会话获得身份声明，生成 `iss/aud/jti/iat/nbf/exp`。
3. 依据管理员显式配置的本节点签发策略约束身份来源与目标（缺失时不能启用 Sender）；此策略不代替 Receiver 自己的信任裁定。拒绝非空扩展；JWS 编码签名、检查实际 Cookie 字节数。
4. **仅在 ReplayGuard 选择 SQL 预登记模式时**完成原子登记；成功才返回可发送 Ticket。

`verifyAndResolve()` 顺序：

1. 限制总输入长度、解码复杂度；拒绝缺失的 Cookie / 不支持的结构版本。
2. 从不可信 Claim 读取 `iss` 定位本地批准且启用的签发者；检查 `alg` 与该签发者批准的算法一致，并依次尝试其密钥，先完成验签。
3. 检查 `aud`、时间窗口、最大 Ticket 生存期、Transfer Intent、登录用户名（大小写完全一致）与 Receiver 授权策略。
4. 如启用双端独立认证比对，检查平台提供的 `VerifiedExternalIdentity`，不能以客户端自报 UUID 代替。
5. 计算 `ResolvedPlayerProfile`；失败不消耗 JTI。
6. 执行 ReplayGuard 原子消费；成功后返回身份，失败拒绝登录。

**Ticket 一旦消费，后续平台进服失败不自动恢复该票据**；需由可信上游重新签发。若 SQL 调用发生超时，不能假设核销没有发生而放行重试。

#### 统一错误码（建议）

`COOKIE_MISSING`、`MALFORMED_TICKET`、`UNSUPPORTED_VERSION`、`UNSUPPORTED_ALGORITHM`、`UNKNOWN_ISSUER`、`UNTRUSTED_KEY`、`INVALID_SIGNATURE`、`EXPIRED`、`NOT_YET_VALID`、`INVALID_LIFETIME`、`TARGET_MISMATCH`、`TRANSFER_REQUIRED`、`USERNAME_MISMATCH`、`ISSUER_POLICY_DENIED`、`EXTERNAL_IDENTITY_MISMATCH`、`PROFILE_RESOLUTION_FAILED`、`REPLAYED`、`REPLAY_UNAVAILABLE`、`TICKET_TOO_LARGE`。

预期验证失败使用可区分的业务拒绝结果/异常；密钥损坏、数据库初始化失败等基础设施错误另作报告。日志不得记录完整 Cookie、私钥、共享密钥或数据库凭据。

### 3.9 统一配置 `chorus.yml`

下例展示使用 `CONFIG` 节点目录与 `LOCAL` SQLite 防重放的 Peer。UNDECIDED（§7.2）：所有运维参数均为显式示例值，须根据部署实测调整。

```yaml
node:
  id: operator-a/sh-survival-01
  display-name: Shanghai Survival
  role: PEER

ticket:
  cookie-key: chorus:ticket
  max-payload-bytes: 4096
  max-property-bytes: 2048
  compression: ZSTD

crypto:
  algorithm: ED25519
  private-key: keys/local_ed25519.key
  public-key: keys/local_ed25519.pub

registry:
  provider: CONFIG
  config:
    servers-path: servers.yml
    issuers-path: trusted-issuers.yml

issuance:
  ttl: 15s
  allowed-auth-schemes: [MOJANG, OFFLINE_AUTH, OFFLINE_RAW]
  allowed-providers: [mojang, local]
  allowed-targets: [operator-a/jp-backend-01]

verification:
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

`servers.yml` 直接以节点 ID 为根键，`address` 合并主机与端口；IPv6 使用带方括号的形式，例如 `"[::1]:25565"`。端口必须为 1～65535 的十进制整数，加载不进行 DNS 查询。运行时 `ServerNode` 继续提供拆分后的 `host`、`port`，便于平台发送 Transfer。

```yaml
operator-a/us-gateway-01:
  address: gateway.example.net:25565
  display-name: Gateway
operator-a/sh-survival-01:
  address: sh.example.net:25565
  display-name: Shanghai Survival
operator-a/jp-backend-01:
  address: jp.example.net:25565
  display-name: Japan Backend
```

`trusted-issuers.yml` 直接以签发者 ID 为根键，表示本机批准的算法、验签密钥与身份来源权限；`allowed-auth-schemes` 使用现有 `AuthType` 的认证来源名称，不改变 Ticket 身份字段。

```yaml
operator-a/us-gateway-01:
  keys:
    - keys/gateway.pub
  allowed-auth-schemes: [MOJANG, OFFLINE_AUTH, OFFLINE_RAW]
  allowed-providers: [mojang, local]

operator-b/peer-01:
  key: keys/alliance-peer.pub
  allowed-auth-schemes: [OFFLINE_AUTH]
  allowed-providers: [operator-b/local]
```

**配置与实现状态契约**：

| 配置 | 契约含义 | 当前实现状态 |
|---|---|---|
| `crypto.algorithm: ED25519` | 本节点签发使用 `private-key / public-key`，JWS 为 `Ed25519` | 已实现；管理员提供密钥对 |
| `crypto.algorithm: HMAC_SHA256` | 本节点签发使用 `key`，JWS 为 `HS256` | 已实现；签发角色在密钥文件缺失时生成 `.key`，管理员分发同一文件 |
| 信任条目 `algorithm: ED25519 / HMAC_SHA256` | 为该签发者的全部 `key / keys` 绑定验签算法；省略为 `ED25519` | 已实现；信任加载不生成密钥，不跨算法回退 |
| `ticket.compression: NONE` | 不压缩完整 Ticket 负载 | 已实现 |
| `ticket.compression: ZSTD / GZIP` | 压缩完整签名 Ticket；省略配置时默认 `ZSTD` | 已实现；版本化帧与有界解压，接收端兼容所有三种编码 |
| `replay-guard.backend: LOCAL` | 本地 SQLite，参数位于 `local.path / max-entries / busy-timeout` | 已实现 |
| `replay-guard.backend: REGISTRY` | 复用 `registry` 配置的数据库；`CONFIG` 时无效 | TODO：共享目录数据库适配与核销尚未实现；启动拒绝 |
| `replay-guard.backend: REDIS` | 使用独立的 `redis` 配置 | TODO：连接配置与后端尚未实现；Schema 只保留空对象位置，启动拒绝 |
| `allowed-auth-schemes: YGGDRASIL / FLOODGATE` | 预留的身份来源名称 | TODO：身份流程尚未实现；即使策略允许，Engine 仍拒绝对应身份的签发与接收 |

这些枚举值描述配置契约，不代表对应运行能力均已交付。实现分支与 Schema 中保留可检索的 TODO；未实现选项在目录读取与状态库打开前明确拒绝，`REGISTRY + CONFIG` 由 Schema 拒绝。不得自动切换到其他压缩方式或核销后端。
`ticket.compression` 可省略，默认 `ZSTD`；Schema 的 `default` 为契约说明，启动装配与无压缩参数的 Java 构造器落实相同默认值。
旧版接收端仅支持 `NONE`；滚动升级时先升级接收端，发送端在接收端升级前显式保留 `NONE`。
`ticket.max-payload-bytes` 限制包含 JWS 编码、签名及压缩帧的最终 Cookie 负载，`max-property-bytes` 限制身份属性 UTF-8 字节数。
`issuance.ttl` 仅发送角色需要；`verification.max-lifetime` 与 `clock-skew` 约束接收票据。身份解析通过 `policy` 包的 `IdentityPolicy` / `IdentityResolutionPolicy` 表达。
主配置 `node.display-name` 当前仅为描述字段，Core 不消费此值；运行时节点目录使用 `servers.yml` 中的 `display-name`，省略时使用节点 ID。
文件路径相对主配置，信任密钥路径相对签发者文件。空目录或信任映射写作 `{}`，旧的 `servers` / `trusted-issuers` 包装根键和旧字段名不属于当前文件契约。

每种配置文件使用**独立、完整、可单独验证的 JSON Schema** 作为格式契约。当前契约版本为 `v1`，采用 JSON Schema Draft 2020-12：

| 配置文件 | 完整 Schema |
|---|---|
| `chorus.yml` | [chorus.schema.json](chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/chorus.schema.json) |
| `servers.yml` | [servers.schema.json](chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/servers.schema.json) |
| `trusted-issuers.yml` | [trusted-issuers.schema.json](chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/v1/trusted-issuers.schema.json) |

各 Schema 在根节点定义对应文件的全部结构，拥有独立的版本化 `$id`，复用片段放在该文件内部的 `$defs`；不以聚合 Schema 的片段入口替代三个文件契约。
源文件保留可读排版与 `$comment`（含 TODO）；构建时由 `processResources` 递归移除全部 `$comment` 并输出无多余空白的紧凑 JSON，资源路径及其余 Schema 内容保持不变。
字段、必填项、类型、枚举、值域、列表唯一性、条件规则和可声明的字符串语法优先在 Schema 中维护；
例如 `GATEWAY` / `PEER` 的密钥及签发策略、`BACKEND` / `PEER` 的防重放配置条件必填，以及时长语法和整秒精度均由主配置 Schema 定义。
文件格式演进应首先修改对应 Schema 及其契约测试，Java 适配所消费值的转换和运行时行为，不另维护一份配置字段白名单或格式校验表。

`com.networknt:json-schema-validator` 从 Core JAR 加载每个文件的完整 Schema，运行时验证不依赖网络。
YAML 经有界安全解析后转换为保留数值精度的 JSON 树；拒绝重复键、集合别名、非字符串映射键及非 JSON 类型。
Java 负责时长转换的表示范围和规范化数值、字段间关系、密钥内容与匹配、文件和目录目标引用；公开领域模型继续保护自身不变量。
完整验证先于持久化状态打开或快照发布，失败保留原有已批准快照。运维参数保持显式配置，不通过 Schema 引入未经验证的默认值。

---

## 4. 平台模块职责与跨服时序（不提前锁死具体 API）

### 4.1 `chorus-common`

- **`SingleFlightCookieReader`**：对单条连接同一 Cookie Key 的读取请求进行去重和超时控制，不跨玩家复用 Future。
- **`TransferSessionRegistry`**：本服内保存原始 `AuthenticatedIdentity` 和 `ACTIVE / TRANSFERRING` 等本地状态，阻止重复触发，管理本地冻结和超时恢复；**不是共享数据锁**。
- **平台能力描述**：`COOKIE_READ_DURING_LOGIN`、`PROFILE_REWRITE_BEFORE_LOGIN`、`TRANSFER_SEND`、`COOKIE_STORE`、`TRANSFER_INTENT`、`ASYNC_LOGIN_SUSPEND` 等。缺少必需能力时报告不支持，不静默降低安全策略。

### 4.2 标准单 Cookie 转移流程

```text
Sender                             原版客户端                         Receiver
  |                                     |                                |
  |-- [平台完成可信认证/持有可信会话]     |                                |
  |-- Core 查询已批准目标/签发 JWT      |                                |
  |-- [若选择 SQL 核销则预先登记 jti]    |                                |
  |-- StoreCookie(chorus:ticket, jwt) ->|                                |
  |-- Transfer(host,port) ------------>|                                |
  |                                     |---- Transfer Handshake ------->|
  |                                     |---- Login Start ------------->|
  |                                     |<--- Cookie Request -----------|
  |                                     |---- Cookie Response(jwt) ---->|
  |                                     |                       验签/授权/身份映射
  |                                     |                       原子防重放消费
  |                                     |<--- Login Success ------------|
  |                                     |---- Login Acknowledged ------>|
  |                                     |        [进入 Configuration]    |
  |                                     |<--- StoreCookie(empty) -------|
```

约束：

- Sender 只能在合法的协议阶段写 Cookie，保证 Store Cookie 发包先于 Transfer 发包。
- Receiver 必须在最终登录放行前验证；请求 Cookie 的网络操作不得阻塞 Netty I/O 线程。
- `StoreCookie(empty)` 在进入 Configuration 后进行，用于减少残留，不是 ReplayGuard 的替代。
- 不要求 Receiver 等待原服释放背包锁，也不需要以 QuitEvent 推断所有数据已落盘。
- 双正版连接可选择平台独立认证后比对 Ticket，但不能假定所有平台都能在相同回调点取得认证结果。

### 4.3 `chorus-paper`

负责插件生命周期、管理员命令和权限检查、受信任玩家会话、Cookie 获取、Transfer、Pre-Login 验票与 GameProfile 改写、Configuration 阶段 Cookie 覆盖。本模块要处理超时与事件线程边界。

**版本备注**：某些连接/配置阶段 API 只存在于较新的 Paper 版本；`AsyncPlayerConnectionConfigureEvent` 文档标注自 1.21.7 提供。不能仅凭新版 Javadoc 就承诺 1.20.5+ 使用同一事件。Folia 的线程约束需单独验证。

### 4.4 `chorus-velocity`

负责 Velocity 的入口认证卸载和 Sender 能力；Receiver/Peer 功能视登录阶段读取 Cookie、应用 GameProfile 的实际 API 能力决定。不要预先保证和 Paper 一样可以实现纯公共 API 的 Pre-Login Receiver。

### 4.5 `chorus-fabric`

负责服务端 Login 状态机内的 Cookie 异步获取、身份应用、Transfer 及进入 Configuration 后的清除操作。Mixin 切入点、映射名和版本适配在模块落地阶段验证；严禁在 Netty 事件线程等待自己的 Cookie Response。

---

## 5. 故障、攻击边界与运行约束

### 5.1 正常安全保证

Chorus 基础协议承诺：可信发行方签名无法随意伪造、目标 `aud` 绑定、有效期校验、授权范围校验、本目标的 JTI 原子消费、按策略恢复身份。

不承诺：原版客户端 Cookie 具备私钥持有者证明；JWT 签名提供保密性；主动中间人不能转交未使用的 Bearer Token；跨节点共享数据不会产生写入竞态。

### 5.2 故障行为

| 情况 | 处理 |
|---|---|
| 共享目录不可用，缓存仍在可信允许窗口内 | 使用缓存中已批准的地址与密钥，继续本地验签转移 |
| 共享目录不可用，缓存不存在或超过可用期限 | 拒绝依赖缺失/过期目录信息的新转移 |
| 本地 ReplayGuard 达容量上限 | 拒绝新票据，不提前删除仍有效记录 |
| SQL ReplayGuard 不可用 | 拒绝核销，不动态切到本地模式 |
| Ticket 已核销，平台后续登录失败 | 本票据作废；重新获取 Ticket |
| Sender 转移发包失败 | 本地释放转移中状态；如 Ticket 已发行，等待短期过期或按可用机制撤销 |
| Receiver 或 Sender 崩溃 | 不要求依赖跨服锁释放；共享背包一致性交由外部业务系统 |
| Receiver 正常重启 | 复用原 SQLite 及 WAL，继续拒绝已消费且未过期的票据 |
| 本地 SQLite 不可用、提交失败或结果未知 | 拒绝本次消费，不重建空库或降级 |

### 5.3 可观测性

统计 Ticket 签发、验证、拒绝、过期、重放、策略拒绝、缓存陈旧、目录同步失败。日志可记录 issuer、aud、jti 摘要和错误码，不记录完整 Cookie、私钥、共享密钥、数据库令牌。

---

## 6. 测试与实施顺序

### Phase 1：纯 Java Core + 单元与本地集成测试（已实现）

Phase 1 已交付；实际装配格式与运维示例见 README 的 Core 独立使用章节：

- 领域模型与配置校验。
- Ed25519 / HMAC-SHA256 签发与验签对象、JWS 编解码、算法与密钥信任绑定；共享密钥文件缺失生成与手动分发。
- 静态节点目录 + 可信签发者策略（不依赖共享数据库服务）。
- `ADOPT_UPSTREAM`、`FORCE_OFFLINE_UUID`；`STRICT_ONLINE_MATCH` 的核心比较逻辑可用纯对象测试验证。
- 本地 SQLite ReplayGuard 与可替换 SPI，含真实文件上的持久化、并发与重启验证；共享 SQL 核销不纳入第一阶段。
- `ChorusCoreEngine` 的纯 Java Mock Sender → Receiver 流程。

最低单测范围：

1. Gateway 正版身份 → 离线 Backend 仍使用 Mojang UUID。
2. 正版 → 旧离线 UUID 映射；离线 → 离线；双正版独立验证上下文比较。
3. 外域不可信签发者、错误 provider/target、密钥替换、过期/未来票据拒绝。
4. 同一 JTI 并发消费只有一次成功，容量满时 fail-closed，提交完成前不返回成功，重开后继续拒绝重放。
5. JWT 规格、编码长度、异常字段、未知算法、禁用签发者、已移除和未批准密钥签名、HS256 与 Ed25519 算法混淆拒绝。
6. 静态目录变更、无外部数据库服务运行；共享目录故障缓存过期验证在 Phase 2 完成。

### Phase 2：共享目录 SQL 实现

基于管理员手动维护的 SQL 表实现 `ServerNodeRegistry`、`TrustedIssuerRegistry` 的快照同步、权限边界和故障缓存。再根据需求决定管理指令的最终形式、心跳上报权限隔离和 DB 方言范围。

### Phase 3：首个平台适配

先选一条最能验证核心价值的路线：**正版 Gateway → 离线 Paper Backend 继承上游 GameProfile**。实际 Paper API 和最小兼容版本在该阶段确认。此阶段无需同时开发 Fabric / Velocity 的完整 Receiver。

### Phase 4：扩展场景和其他平台

双正版/双离线/历史 UUID 兼容测试，然后按需补 Yggdrasil、Velocity、Fabric；Floodgate 列为后续候选。SQL 票据核销与受控联盟的原始认证凭据链，应在其真实需求明确后单独排期。

---

## 7. 已确认需求与仍待决定的问题

### 7.1 已确认

1. 名称 `Chorus`，包名前缀 `me.pectics.chorus`，Java 21、Gradle Kotlin DSL、Version Catalog、No Lombok、Agent-First、VSCode。
2. 保留 `core / common / paper / velocity / fabric` 主模块结构。
3. 服务器列表由管理员维护；共享 SQL 默认倾向，但纯静态模式必须可运行。
4. 去中心化流量，不强制 IP 绑定，默认单 Cookie + 签名 Ticket。
5. 目录数据库故障允许用**已批准且仍在允许期限内的缓存**继续转移。
6. 基础版不管理全局单会话/共享背包/经济一致性；仅预留扩展可能性。
7. 同时面向私人互信集群和受控服主联盟，信任范围要可配置。
8. 正版 Gateway → 离线 Backend Profile 透传为第一等场景；随后关注双正版、双离线、旧离线 UUID 兼容；Yggdrasil 再后，Floodgate 不作完工保证。
9. 先完成可独立测试的 Core，再接平台。

### 7.2 未决定，不得擅自当作既定需求

1. **管理员管理方式**：完全静态配置、游戏内管理命令、两者并存及跨节点同步的交付范围；数据库管理员凭据如何安全分发。
2. **Ticket 核销的最终默认后端**：`LOCAL`、`REGISTRY` 还是 `REDIS`，以及何种规模/可靠性目标才需要 SQL。
3. **节点心跳**：共享数据库是否提供安全且足够细粒度的写权限，若不能则如何上报。
4. **受控联盟深度**：是否需要外域不完全可信 Peer 对上游认证结果进行无权伪造的转发；若需要，再设计双层签名 Attestation。
5. **数据库支持矩阵**：Turso/libSQL 及哪些 JDBC 方言进入首版；各自事务与核销语义需要集成测试。
6. **精确平台版本/API**：各平台的最低支持版本、Profile 改写能力、配置阶段清 Cookie 接口。
7. **运维参数**：默认 TTL、缓存最大陈旧时间、重放缓存容量、超时策略应在实测后确定。

---

## 8. 参考资料与核查说明

- [Minecraft Snapshot 24w03a：Transfer 和 Cookie 的官方说明](https://www.minecraft.net/en-us/article/minecraft-snapshot-24w03a)
- [RFC 9864：明确的 Ed25519 算法标识](https://www.rfc-editor.org/rfc/rfc9864)
- [SQLite WAL 持久化](https://www.sqlite.org/wal.html)
- [RFC 7519：JWT / NumericDate](https://www.rfc-editor.org/rfc/rfc7519)
- [SQLite RETURNING 官方文档](https://www.sqlite.org/lang_returning.html)
- [libSQL Hrana v3 协议规范](https://github.com/tursodatabase/libsql/blob/main/docs/HRANA_3_SPEC.md)
- [Paper 官方 Javadoc](https://jd.papermc.io/)
- [Velocity 官方 Javadoc](https://jd.papermc.io/velocity/)

这份文档对**Core 的结构与接口**提出具体方案，对 Minecraft 平台底层接口只约定能力和阶段。数据库具体产品版本、授权范围、平台 API 兼容性及运行时性能仍需实现阶段验证，不能将架构草案中的示例视为实测结论。
