<!--
@context
refs: ARCHITECTURE.md, sections 2, 6, and 7
related: settings.gradle.kts; .gitignore; README.md
sync: Follow section 6 to keep execution rules current; design belongs in ARCHITECTURE.md.
@endcontext
-->

# AGENTS.md — Chorus 协作与维护约定

## 1. 设计权威与接手顺序

[ARCHITECTURE.md](ARCHITECTURE.md) 是唯一架构定义。本文件负责执行约定、当前开发范围和自身维护策略，
不得借此新增架构结论。用户明确调整的设计应同步到架构文档；发现实现与架构不一致时说明差异，
不能仅修改本文件或注释就把偏差变成既定设计。

开始任务时：先读架构文档，再读本文件；检查 `git status`、目标代码和相关测试；沿文件头引用读取必要的关联文件。
保留用户已有改动，不把本地存在、曾经能编译或注释中的设想当成已经交付的能力。

## 2. 当前开发范围（随项目演进更新）

- 当前主要开发并纳入 Git 白名单 / Gradle 的模块只有 `chorus-core` 和 `chorus-common`。
- `chorus-paper`、`chorus-velocity`、`chorus-fabric` 仍是架构规划的一部分；本地可能存在暂存骨架，
  **有意被 Git 忽略且不纳入构建**。不得因为目录存在就自动启用、放行或补全平台实现。
- Core 已实现 Phase 1：`CONFIG`（YAML 配置文件）目录与信任、Ed25519 / HMAC-SHA256 Ticket、ZSTD（默认）/ GZIP / NONE 编码与有界解压、身份策略、SQLite 持久化防重放与异步 Engine；
  单测与本地集成测试覆盖核心闭环。Registry 仅负责服务器目录，SQL 已支持 PostgreSQL / MySQL / MariaDB；本地可信签发者由独立 `trust` 模块管理，路径位于 `verification.trusted-issuers-path`，不自动分发信任或密钥。Common 仍只有包职责说明，平台接入尚未实现。
- 实施顺序遵循架构 §6：Core 与 SQL 服务器目录完成验证后，只推进一条
  **正版 Gateway → 离线 Paper Backend** 路线；其他平台与扩展场景按后续阶段推进。

## 3. 构建、验证与 Git

```bash
./gradlew :chorus-core:test      # Core 快速单测
./gradlew :chorus-core:integrationTest # 本地 SQLite 与完整配置集成验证
./gradlew :chorus-core:sqlIntegrationTest # 独立三库与 TLS 验证；需本地 Docker
./gradlew :chorus-common:test    # common 测试（有测试后执行）
./gradlew spotlessApply         # 应用格式化
./gradlew spotlessCheck         # 只检查格式
./gradlew spotbugs              # 当前启用模块全部 source set 的静态分析，不执行测试
./gradlew spotbugsMain          # 仅分析当前启用模块的生产代码
./gradlew build                 # 当前启用模块的格式检查、静态分析、构建与测试
./gradlew projects              # 核对实际启用的模块
```

- Java 21 Toolchain；使用仓库内的 Wrapper，不依赖特定 IDE。
- 普通单测、本地 SQLite 集成测试与 `check` / `build` 不依赖外部数据库服务；SQLite 使用临时文件。
  独立 `sqlIntegrationTest` 使用本地 Docker，不纳入普通 `check` / `build`；首次需要拉取数据库与 Testcontainers 镜像，固定镜像版本也从 Version Catalog 读取。
  首次准备 Wrapper、Toolchain 和依赖可能需要下载，缓存齐备后可加 `--offline`。
- 优先运行改动模块的相关测试；修改共享构建配置时运行 `build` 并核对 `projects`。
  SQL、网络故障与数据库方言验证放独立集成测试任务。脚手架测试通过不等于业务功能通过。
- 格式由 Spotless 统一：Java 使用 google-java-format 的 AOSP 风格（4 空格），Gradle Kotlin DSL 使用 ktfmt 的 Kotlin 风格。
  `check` / `build` 包含格式检查，修改后运行 `spotlessApply`，最终提交序列交付审计前运行适用的 `spotlessCheck` 与测试；普通构建不自动改写源码。
  本地检查点允许先提交再验证，具体流程见 §3.1。
  格式化仅覆盖当前启用模块与显式列出的手写构建/配置文件，不扫描被忽略的平台、生成文件或 Markdown 文档。
- SpotBugs 分析当前启用模块的生产代码与测试代码，使用最大分析深度和中/高置信度告警；发现告警时使 `check` / `build` 失败。
  `spotbugs` 仅编译并分析各 source set，包含 SQL 集成测试源码但不启动数据库或执行测试；HTML / XML 报告位于各模块 `build/reports/spotbugs/`。
  先修复实际问题；确认为误报或有意契约时，在 `gradle/spotbugs-exclude.xml` 中仅对具体告警和成员排除并写明理由，不用全量 baseline 或忽略失败绕过检查。
- 依赖、平台与插件版本从 `gradle/libs.versions.toml` 读取，模块脚本不硬编码版本；
  foojay-resolver 例外写在 `settings.gradle.kts`，因为其 `plugins {}` 无法读取 catalog。
  项目发布版本目前在 `gradle.properties`，Wrapper 发行包版本在 `gradle/wrapper/gradle-wrapper.properties`，不要在模块中复制。
  工具首次引入或升级时核对官方最新稳定版并验证 Java 21、Gradle 与格式化器适配兼容性，再固定具体版本；不使用动态版本或预发布版本。
- 依赖仓库只在 `settings.gradle.kts` 声明，模块内不写 `repositories {}`；保留 `PREFER_SETTINGS`。
  当前只启用 Java 模块，不加载 Loom 或平台仓库；平台接入时再验证仓库与 API 兼容性。
- `.gitignore` 始终采用**白名单**：根目录默认忽略，再显式放行；当前模块内部仅放行构建脚本与 `src/`，
  不放行 `build/`、缓存或本地运行目录。新增需收录的文件或模块时同步检查白名单与构建范围。
  提交前用 `git status --short --untracked-files=all` / `git check-ignore -v <路径>` 核对，不用 `git add -f` 绕过约定。

### 3.1 Git 是本地开发草稿与审计工具

- Agent 应主动使用本地提交管理工作，不必等到整个功能完成或所有测试通过才提交。
  **当前任务相关、尚未推送的历史属于可整理的草稿**：可自行提交、`amend`、`rebase`、重排或重放，无需逐次请求人类审批。
  操作前核对分支、提交范围和工作区，保留用户无关改动；已推送或共享的历史不按本地草稿任意改写。
- 较大功能先规划有明确边界的阶段提交，例如 A / B / C 三个 `feat`；小任务用一条提交即可，不机械凑数量。
  每个阶段编辑完成后及时提交，再运行针对性的小范围验证；发现问题补 `fix`。
  若后续才发现早期阶段的问题，可将修复插入对应阶段之后，并重放后续提交。
- 保留具有审计价值的阶段边界与修复关系，让人类能看见“A 引入了什么、B 又如何修改 A”。
  可以整理零碎提交，但**不得默认将整个功能压成单个最终 diff**，也不要求每条中间草稿提交都处于最终可发布状态。
- 本地提交不等于验证通过。最终交付时如实说明已执行的检查、结果与尚未解决的问题；纯文档调整使用文档检查，无需为提交形式重复运行无关构建。
- **人类审计放在最终 push 之前**：先提供分阶段的提交序列、可审阅的差异和必要验证结果，
  经人类审计并取得本次推送授权后再 push。审计后如有实质变更，重新展示相关差异；不将本地提交或自动检查通过视为推送授权。

阶段序列示例（第二条是 A 阶段的修复）：

```text
feat(core): Add ticket claim models
fix(core): Validate identity provider identifiers
feat(core): Implement ticket signing
feat(core): Add the verification pipeline
```

### 3.2 提交消息

遵循 [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)，并采用本项目的大小写约定：

- 标题格式为 `type(scope): Description`；`scope` 可省略，破坏性变更在冒号前加 `!` 或使用 `BREAKING CHANGE:` footer。
- `type` 与可选 `scope` 使用小写；说明文案使用**英语，首字母大写**。正文与 footer 的说明文字也使用英语。
- 常用类型为 `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`ci`、`style`、`perf`、`chore`、`revert`；按变更意图选择。
- 描述具体动作与结果，例如 `docs: Define the local Git draft workflow`，不要用 `Update`、`Fix bugs` 这类缺少上下文的笼统标题。
- 正文包含多项改动或验证内容时，使用 `- ` 无序列表，每项围绕一个动作或主题；不要把多个独立事项挤进长句或段落。
  紧密相关的细节可同项描述，单一事项可用简短段落，也不强制补写正文。
- 标题、正文与 footer 之间保留空行。`BREAKING CHANGE:` 保留为独立 footer；包含多项迁移要求时，
  在其摘要后的续行中使用列表，与普通正文分开。

## 4. 实现约束

- 实现收敛到架构已确认的当前阶段；用满足现有契约的最小实现，不主动设计架构范围外的能力。
- Java 21，优先 `record` / `sealed interface` / `switch`，禁止 Lombok。
- 密码学范围固定为 Ed25519 与 HMAC-SHA256；按架构 §3.7 的实际能力组织实现，不为其他算法增加扩展层。
- 依赖方向为平台 → common → core；Core 不持有 `Player`、`GameProfile`、Netty `Channel` 等平台对象。
  数据库 I/O 与定时调度通过接口封装。
- Core 的签发、验证及可能触达 SQL 的对外操作使用 `CompletionStage`；平台层不得在 Netty EventLoop 中 `.join()` 或无超时阻塞。
- 公开 API 的输入约束、线程约束与错误语义随接口提交；可变字节数组防御性复制，集合采用不可变快照。
- 每个配置文件各有独立、完整、版本化的 Schema，位于 `chorus-core/src/main/resources/me/pectics/chorus/core/config/schema/`。
  文件格式规则优先维护在对应 Schema 与契约测试中；Java 负责值转换和运行时逻辑，不重复维护字段白名单或格式规则表。
  修改格式时同步核对装配所消费的值、公共领域不变量和引用，具体边界见架构 §3.9。
  Schema 中预留但尚未实现的选项须保留可检索的 `TODO`，在架构契约记录实现状态，并由运行时明确拒绝。
  源 Schema 保留可读排版和 `$comment`；`processResources` 负责最小化产物并递归移除全部 `$comment`，不改写源文件。
- 平台 API 使用 `compileOnly` / `modImplementation`，不打入产物；具体登录接口和最低兼容版本须实测。
- 日志不得记录完整 Cookie、私钥、共享密钥或数据库凭据。
- 防重放实现按后端职责命名：`replay.local.LocalReplayGuard` 对应 `LOCAL`，`replay.registry` 对应 `REGISTRY`，`replay.redis` 对应 `REDIS`。
  本地后端当前使用 SQLite，持久化提交确认后才返回成功；保留原数据库及 WAL，故障时拒绝，不回退内存。
- 架构 §7.2 的管理方式、核销默认后端、心跳权限、联盟 Attestation、后续数据库扩展、平台最低版本和运维默认参数仍未决定。
  SQL 运行账号权限与未来写入所需凭据配置、PostgreSQL `sql.schema` 必须保留 TODO；当前仅做服务器目录读取，不借此引入管理写接口或密钥同步。
  实现所需临时选择保持可替换，并标注 `UNDECIDED`（未决）及设计出处，不将示例值写成产品承诺。

## 5. 注释与文件头引用区

- 除独立 `.md` 文档外，所有行内注释、文件头、Javadoc、包文档及配置注释**完全使用英语**；引用区内的说明也遵循英语约定。
- 正常代码的注释密度以 **5～10%** 为指导：优先通过命名、结构和类型使代码可读，仅补充代码本身不能快速体现的语义。
  这是维护时的判断尺度，不是逐文件的行数配额或 CI 百分比门槛；不要补注释凑比例，也不要因比例删掉必要的公共契约、线程约束或安全说明。
- 注释解释设计原因、约束、边界和跨文件联动；删除复述代码、重复架构、陈旧设想与无依据的验证结论。
  包文档保持简短职责说明；细节归权威架构或对应 API，不在骨架中展开尚不存在的实现清单。

有跨文件约束、复杂生命周期或容易遗漏的联动关系时，在支持注释的手写文件头部使用统一引用区。
新增或实质修改文件时检查是否需要；已有文件随任务逐步补齐，不为覆盖率批量添加空块。
引用区用于导航与说明维护关系，设计依据、API 契约和细节仍留在各自原位置。

Java / Kotlin 示例（普通块注释，避免把标记当作 Javadoc 标签）：

```java
/*
 * @context
 * refs: ARCHITECTURE.md, sections 2.1 and 6
 * related: .gitignore; README.md
 * sync: Keep enabled modules, the Git whitelist, and build instructions aligned.
 * @endcontext
 */
```

- `refs`：设计依据或契约位置；`related`：少量关键关联文件；`sync`：何时需要联动、为什么。
  字段按实际需要填写，优先能帮助定位约束的引用，不重复罗列 imports 或整张依赖图。
- 路径以**仓库根目录为基准**；按需加稳定的类/方法名或文档节号，不写本机绝对路径、易漂移的行号或不存在的未来文件。
- `.gitignore`、TOML、properties、Shell 使用 `#`；Markdown 使用 HTML 注释；Java / Kotlin 使用 `/* ... */`。
  保留 shebang、许可证与格式要求的位置，引用区放在其后。严格 JSON 和二进制文件不插入注释，改在关联文档中记录。
- 生成文件、第三方文件与 Gradle Wrapper 脚本不为此改写；架构文档也不为补引用区单独改动。
- 修改或移动锚点、文件、契约时，用 `rg` 搜索受影响引用，修复相关头部与文档。只维护必要关联，不强制逐文件双向链接。

## 6. 本文件的维护策略

**每次任务结束前检查本文件及受影响引用是否仍然准确；有变化就随同任务修改，未变化不做形式化改写。**
以下变化应触发维护：用户明确改变长期协作偏好；模块范围或实施阶段变化；构建/测试命令、前置条件、
Git 收录规则变化；出现经过验证且后续任务可复用的执行约束；相关文件或引用锚点迁移。

维护时遵守：

1. 先核对证据：用户明确要求、当前架构、实际配置/代码与验证结果。注释、旧日志和一次性报错不能单独成为长期规则。
2. 架构决策归 `ARCHITECTURE.md`；执行规则归本文件；使用说明归 `README.md`；局部联动归文件头。
   保持入口短而可检索，不复制整份架构、任务流水账、临时调试输出或失效的 TODO。
3. **原地修订并删除失效内容**，不要不断追加相互矛盾的补充。状态描述只写当前事实，未验证事项明确保留为未决。
4. 涉及模块启用或收录时，同时核对 `settings.gradle.kts`、`.gitignore` 与 `README.md`；
   涉及公共契约时核对实现、测试和受影响引用。不得为修正文档悄悄扩大用户授权的实现范围。
5. 本维护策略本身也适用上述流程；用户调整引用格式或协作方式时，同步更新此处与相关示例。
   完成说明中简述本次改变的长期规则及验证结果；不为无关文件批量重写引用区。
