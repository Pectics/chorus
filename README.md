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

> **当前状态：仅有工程骨架，主要开发 `chorus-core` 与 `chorus-common`。** 尚未实现任何协议逻辑；
> [ARCHITECTURE.md](ARCHITECTURE.md) 是唯一架构定义，协作与维护约定见 [AGENTS.md](AGENTS.md)。

## 模块

| 模块 | 职责 | 当前范围 |
|---|---|---|
| `chorus-core` | 纯 Java：节点目录快照、受信任签发者、Ticket 签发与验签、身份解析策略、防重放 | 已纳入 Git 白名单与构建 |
| `chorus-common` | 平台无关的 Minecraft 生命周期支撑：Cookie 单飞读取、本节点转移会话、平台 SPI | 已纳入 Git 白名单与构建 |
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
- 单元测试须能在无网络、无数据库时运行；SQL 与网络故障测试放独立集成测试任务
- 实现顺序见 ARCHITECTURE.md §6：先让 `chorus-core` 测试通过，再推进平台接入
- Git 使用白名单，只收录显式放行内容；模块内部的构建产物与缓存不收录
- 跨文件维护关系采用文件头 `@context` 引用区，格式与持续更新策略见 `AGENTS.md` §5–§6

## 许可

MIT，见 [LICENSE](LICENSE)。
