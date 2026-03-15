# ARCHITECTURE

> 本文件是 AndroidClaw 的架构级真相源。  
> `AGENTS.md` 只负责导航和硬规则；`PLANv5.md` 负责顶层执行计划；本文件负责定义系统分层、模块边界、关键数据流、稳定约束和非目标。  
> 任何影响分层、核心数据模型、运行时职责、依赖方向的改动，都必须同步更新本文件。

---

## 1. 项目定义

AndroidClaw 是一个 **Android 原生、本地优先、单 APK、轻量优先** 的 AI assistant。

它不是把 NanoClaw / OpenClaw 的桌面运行时硬塞进 Android，而是：

1. 用 **Android 原生宿主** 重建一个轻量本地运行时。
2. 用 **OpenClaw 兼容语义层** 保留用户最关心的行为：sessions、skills、tools、cron / once / interval、memory、GUI。
3. 用 **可选 remote bridge** 兜底少数本地不适合做的能力；bridge 不是基线依赖，也不是首发阻塞项。

**兼容的是语义，不是源码，不是协议细节，不是桌面依赖。**

---

## 2. 外部事实基线（写给 Codex）

这些不是“建议”，而是决定架构边界的事实：

- OpenAI 在《Harness engineering》里明确建议：`AGENTS.md` 保持短小，仓库知识放进 `docs/` 作为 system of record，复杂任务用 versioned plans 管理。这是本仓库文档组织方式的直接依据。  
- OpenClaw 当前官方把 Android 定义为 **companion node app**，不是 Gateway host；Android 侧默认是连接 Gateway 的节点，而不是宿主。  
- OpenClaw 的 skills 是运行时加载的 `SKILL.md` 目录体系，支持 bundled / local / workspace 多来源与优先级覆盖。  
- OpenClaw 的 tools 已经明确走 **typed tools, no shelling** 的方向。  
- NanoClaw 当前是 **单 Node.js 进程 + SQLite + task scheduler + 容器隔离** 的轻量桌面架构，要求 Node 20+，并依赖 Docker / Apple Container。  
- Android 进程生命周期不由应用自己决定；后台任务、前台服务、exact alarms、Doze、App Standby 都有平台限制。  
- WorkManager 是 Android 官方推荐的持久后台工作主路径；exact alarm 只应保留给真正需要精确时刻的用户可感知行为。  
- Baseline Profiles 是轻量 Android App 的重要性能手段，适合尽早纳入。

这些事实意味着：**AndroidClaw 必须是 Kotlin-first、Android-native、typed-tool-first、DB-backed、honest-about-background 的系统。**

---

## 3. 设计原则

### 3.1 语义兼容优先于实现兼容
只要用户看到的核心行为相同或近似相同，就不需要复制 OpenClaw / NanoClaw 的桌面实现细节。

### 3.2 轻量优先于“像桌面版”
如果某条路更像桌面 OpenClaw，但会把 APK、启动、内存、后台脆弱性变差，就不选。

### 3.3 Android 原生优先于嵌入式桌面运行时
禁止在基线包里引入 Node、Docker、Chromium、Playwright、Puppeteer、Electron 风格重型运行时。

### 3.4 持久化优先于内存真相
任务、会话、消息、技能状态、运行历史都必须落库。  
内存只能是缓存，不能是唯一真相源。

### 3.5 Typed tools 优先于 raw shell
工具必须有明确 schema、权限要求、前台要求、错误码。  
`exec` / shell 不属于 v1 基线。

### 3.6 Honest background
不承诺 Android 做不到的后台可靠性。  
一切延迟、权限缺失、前台要求、OEM 电池策略，都要在 UI 和日志中显式呈现。

### 3.7 Agent-legible repository
仓库结构、命名、文档、测试、fakes、日志格式，都要优先让 Codex 可理解、可修改、可验证。

---

## 4. 系统总体形态

AndroidClaw = **Local Gateway + Local Node + Native GUI**

```text
┌─────────────────────────────────────────────────────────────┐
│                        AndroidClaw                         │
├─────────────────────────────────────────────────────────────┤
│  Native GUI (Compose)                                      │
│  - Chat                                                    │
│  - Tasks                                                   │
│  - Skills                                                  │
│  - Settings                                                │
│  - Health                                                  │
├─────────────────────────────────────────────────────────────┤
│  Local Gateway / Runtime                                   │
│  - AgentRunner                                             │
│  - SessionManager                                          │
│  - SkillManager                                            │
│  - ToolRegistry                                            │
│  - SchedulerCoordinator                                    │
│  - ModelProvider adapter                                   │
├─────────────────────────────────────────────────────────────┤
│  Local Node / Platform adapters                            │
│  - Notifications                                           │
│  - Contacts                                                │
│  - Calendar                                                │
│  - Files                                                   │
│  - HTTP fetch                                              │
│  - Camera / Screen / Location / Voice (foreground-gated)   │
├─────────────────────────────────────────────────────────────┤
│  Persistence                                               │
│  - Room / SQLite                                           │
│  - DataStore                                               │
│  - Keystore                                                │
│  - app-private files/                                      │
├─────────────────────────────────────────────────────────────┤
│  Android system                                            │
│  - WorkManager                                             │
│  - AlarmManager (limited precise path)                     │
│  - Runtime permissions                                     │
│  - OEM battery restrictions                                │
└─────────────────────────────────────────────────────────────┘
```

对比 OpenClaw：

- OpenClaw 里 **Gateway** 和 **Android node** 是分开的。
- AndroidClaw 里它们被合并成本地应用内部两个职责域：
  - `Local Gateway`：编排、会话、skills、scheduler、provider。
  - `Local Node`：本地设备能力，以 typed tools 暴露。

---

## 5. 初始仓库结构（推荐给 Codex）

首发阶段保持 **单 `:app` 模块**，避免多模块带来的构建和导航成本。  
只有在出现真实维护痛点时，才拆模块。

推荐包结构：

```text
app/src/main/java/com/androidclaw/
  app/
    AndroidClawApplication.kt
    MainActivity.kt

  nav/
    AppNavHost.kt
    TopLevelDestination.kt

  ui/
    common/
    chat/
    tasks/
    skills/
    settings/
    health/

  runtime/
    orchestrator/
      AgentRunner.kt
      TurnCoordinator.kt
      RuntimeContext.kt
    providers/
      ModelProvider.kt
      FakeProvider.kt
      OpenAiCompatibleProvider.kt
    tools/
      ToolRegistry.kt
      ToolDescriptor.kt
      ToolResult.kt
      builtins/
    skills/
      SkillManager.kt
      SkillParser.kt
      SkillEligibility.kt
      SkillSnapshot.kt
    scheduler/
      SchedulerCoordinator.kt
      TaskPlanner.kt
      CronParser.kt
      NextRunCalculator.kt
      workers/
      alarms/

  data/
    db/
      AndroidClawDatabase.kt
      dao/
      entity/
      migration/
    repository/
    model/

  platform/
    permissions/
    notifications/
    contacts/
    calendar/
    files/
    device/
    health/
    alarms/
    work/

  testing/
    fakes/
    fixtures/
    helpers/
```

### 为什么先单模块
- 更轻；
- 编译配置更少；
- 对 Codex 更容易全局理解；
- 对小团队和高速 agent 迭代更友好。

---

## 6. 分层规则

### 6.1 层级

```text
types / model
   ↑
data (db + repository)
   ↑
platform adapters
   ↑
runtime services
   ↑
ui / navigation
```

更准确地说：

- `data`：持久化与 repository。
- `platform`：Android SDK 包装层。
- `runtime`：业务运行时（turn、skills、tools、scheduler、providers）。
- `ui`：Compose 界面与 view models。

### 6.2 允许的依赖方向

- `data` 只能依赖基础 model / serialization / Room。
- `platform` 可以依赖 Android SDK、基础 model，但不能依赖 UI。
- `runtime` 可以依赖 `data` 和 `platform`，但不能依赖具体 Compose UI 组件。
- `ui` 可以依赖 `runtime` 暴露出的 use cases / state models，但不能直接操纵 DAO。
- `testing` 可以依赖所有层。

### 6.3 明确禁止

- UI 直接访问 DAO。
- Runtime 直接操纵具体 Activity / Composable。
- Platform adapter 直接写业务决策。
- Room Entity 直接泄漏到 UI。
- Provider 响应 shape 不经解析直接在系统内传播。
- “先写巨型 util，后面都往里塞”。

### 6.4 边界校验原则

所有跨边界数据都必须在边界处做解析和归一化：

- 网络 → Provider DTO → internal model
- Skill file → parser result → internal skill model
- Tool input JSON → validated request model
- Android API result → normalized result model

---

## 7. 四个 compatibility contract（此处只定义架构落点）

完整定义以 `PLANv5.md` 为准，这里只写实现上的落点。

### 7.1 Session Contract
落点：

- `data/entity/SessionEntity`
- `data/entity/MessageEntity`
- `runtime/orchestrator/AgentRunner`
- `ui/chat/*`

要求：

- main session 永远存在。
- 每次用户 turn、tool turn、assistant turn 都要可回放。
- isolated automation run 必须能把结果回投到目标 session。

### 7.2 Automation Contract
落点：

- `data/entity/TaskEntity`
- `data/entity/TaskRunEntity`
- `runtime/scheduler/*`
- `ui/tasks/*`
- `ui/health/*`

要求：

- 支持 `once / interval / cron`
- 支持 `MAIN_SESSION / ISOLATED_SESSION`
- 支持 `run now`
- 支持 `next run / last run / history / retry`

### 7.3 Skill Contract
落点：

- `runtime/skills/*`
- `ui/skills/*`
- `app/src/main/assets/skills/`
- `files/skills/local/`
- `files/workspaces/<session-id>/skills/`

要求：

- 目录 + `SKILL.md`
- frontmatter + Markdown body
- bundled / local / workspace
- 优先级覆盖
- eligibility / missing requirements
- slash command / tool dispatch

### 7.4 Tool Contract
落点：

- `runtime/tools/*`
- `platform/*`
- `testing/fakes/tools/*`

要求：

- typed native tools
- 明确 schema、permissions、foreground requirement、error codes
- 可 fake、可 mock、可日志化

---

## 8. 核心数据模型（v1）

这里只定义架构职责，不锁死最终字段命名。

### 8.1 AppSettings
职责：

- provider 配置
- feature flags
- 设备 / 权限状态快照
- skill overrides
- performance / debug flags

存储：

- `DataStore`
- 敏感凭据进 `Keystore`

### 8.2 Session
职责：

- 会话 identity
- 标题 / 状态
- workspace root
- summary / compacted context pointer
- archived state

### 8.3 Message
职责：

- user / assistant / tool / system message
- session 归属
- provider metadata
- tool call metadata
- scheduled run 归属（可选）

### 8.4 Task
职责：

- schedule spec
- execution mode
- target session
- enabled / paused
- precise flag
- next run / last run
- backoff state
- failure counters

### 8.5 TaskRun
职责：

- 每次实际执行的完整历史
- scheduledAt / startedAt / finishedAt
- status / errorCode / errorMessage
- result summary
- output message id
- workspace path（isolated run）

### 8.6 SkillRecord
职责：

- source type（bundled / local / workspace）
- enabled state
- parsed metadata snapshot
- eligibility snapshot
- missing requirements
- import provenance

### 8.7 EventLog
职责：

- 结构化事件
- debug / health / scheduler / provider / tool traces
- 有上限，不允许无限增长

---

## 9. 文件与 workspace 模型

### 9.1 根原则
一切文件访问默认都在 app-private storage 下完成。  
不以外部共享存储为基线，不要求 SAF 长期开权。

### 9.2 目录约定

```text
files/
  workspaces/
    main/
    <session-id>/
    isolated/
      <task-id>/
        <run-id>/
  skills/
    local/
      <skill-id>/
  exports/
  imports/
  logs/
```

### 9.3 约束

- main session 必有一个 workspace。
- 每个普通 session 都有独立 workspace root。
- `ISOLATED_SESSION` run 必须拥有独立临时 workspace。
- isolated 不是容器隔离，只是 **runtime context + file root 分离**。
- `files.*` 工具默认只允许访问当前允许的 workspace root。

---

## 10. 运行时主流程

### 10.1 交互式聊天 turn

1. UI 把用户消息写入 DB。
2. `AgentRunner` 读取当前 session 状态、历史摘要、最近消息。
3. `SkillManager` 计算该 turn 可见的 enabled + eligible skills。
4. `ToolRegistry` 输出当前可用 typed tool schemas。
5. `ModelProvider` 发起一次模型调用。
6. 若模型请求 tool：
   - 校验 request
   - 执行 tool
   - 落库 tool message / result
   - 回到 provider
7. 得到最终 assistant message 后落库。
8. UI 订阅 DB / state flow，刷新展示。

**规则：**
- 最大 tool-call loop 次数要小（例如 6）。
- 任何中途失败都要落 event log。
- 最终 reply 不可只存在于内存。

### 10.2 定时任务 turn

1. Scheduler 唤醒 worker。
2. 读取 Task + schedule state。
3. 创建 `TaskRun` 记录，状态置为 `RUNNING`。
4. 根据 execution mode 创建 runtime context：
   - `MAIN_SESSION`
   - `ISOLATED_SESSION`
5. 复用 `AgentRunner` 执行任务 prompt。
6. 持久化结果：
   - 写入 `TaskRun`
   - 必要时投递到目标 session
7. 计算下一次运行时间。
8. 重新 enqueue 唯一 work。

### 10.3 Slash command turn

- `/skillName args`
- 若 skill 是 `command-dispatch: tool`
  - 直接走 `ToolRegistry`
- 否则
  - 把该 skill 作为强约束注入本次 turn
  - 走普通 `AgentRunner`

### 10.4 Skill refresh

触发时机：

- app cold start
- 导入 skill 后
- 启用 / 禁用 skill 后
- workspace skill 变更后
- 显式“刷新技能”

v1 不做重型文件系统 watcher。

---

## 11. Provider 层

### 11.1 统一接口

`ModelProvider` 必须抽象出统一的请求对象，至少包含：

- prior messages
- system instruction
- enabled skill instructions
- tool schemas
- metadata（session id、interactive vs scheduled、debug tags）

### 11.2 首发实现

#### FakeProvider
用途：

- debug builds
- 单元测试
- 集成测试
- 离线开发

要求：

- 确定性
- 支持普通回复
- 支持简单 tool-call 流程
- 不依赖网络

#### OpenAiCompatibleProvider
用途：

- v0 / v1 第一条真实 provider 路径

要求：

- 小适配层
- OkHttp + kotlinx.serialization 即可
- 不引入重型自动生成 SDK
- 所有 provider 返回结构都在边界解析

---

## 12. Tool 层

### 12.1 工具不是“函数列表”，而是产品边界
每个工具必须带着这些元信息进入运行时：

- `name`
- `description`
- `inputSchema`
- `outputSchema`
- `requiredPermissions`
- `foregroundRequired`
- `availability`
- `stableErrorCodes`

### 12.2 v0 工具建议清单
优先顺序：

1. `sessions.*`
2. `tasks.*`
3. `skills.*`
4. `device.info`
5. `device.permissions`
6. `health.status`
7. `notifications.post`
8. `files.*`
9. `http.fetch`
10. `contacts.search`
11. `calendar.events`

### 12.3 前台能力规则
以下能力必须显式标记 `foregroundRequired = true`：

- camera
- screen capture / record
- microphone
- 部分 location 模式

后台不满足条件时，必须返回结构化错误，而不是假装能运行。

---

## 13. UI 结构

v0 / v1 顶层导航：

- Chat
- Tasks
- Skills
- Settings
- Health

### 13.1 Chat
- session selector
- message list
- composer
- slash command path
- new session action

### 13.2 Tasks
- task list
- next run
- last status
- create / edit / pause / resume / run now

### 13.3 Skills
- skills list
- source type
- enabled state
- missing requirements
- import action
- skill details

### 13.4 Settings
- provider config
- API key config
- permissions summary
- exact alarm status
- export / clear actions

### 13.5 Health
- provider configured or not
- selected session
- next scheduled task
- last scheduler wake
- battery / background reliability status
- OEM guidance

---

## 14. 性能与轻量基线

### 14.1 必须做到

- manual DI，避免大 DI 框架
- 无 idle 常驻 foreground service
- 启动时不 eager-load 全量历史
- 列表与消息流惰性加载
- event log 有上限或定期裁剪
- 内存缓存有上限
- provider / tools / skills 可按需初始化
- Room / WorkManager / OkHttp / Compose 之外，不要引入大依赖簇

### 14.2 应尽早加入

- Baseline Profiles
- 启动与关键页面 benchmark
- APK size report
- 低端机冷启动与滚动回归

### 14.3 WorkManager 初始化
当 scheduler 成形后，优先考虑 **on-demand initialization**，避免无条件把 WorkManager 放进冷启动关键路径。

---

## 15. 安全边界（注意：不是最高优先级，但仍需“够用”）

本项目不追求 NanoClaw 的容器隔离。  
v1 采用：

- Android app sandbox
- app-private storage
- runtime permission gate
- typed tools
- Keystore 存放密钥
- foreground requirement 显式化
- 结构化日志和错误码

**不做：**

- Docker / container runtime
- 复杂 allowlist 平台
- 节点审批系统
- arbitrary shell 默认开放

---

## 16. 明确非目标

以下内容不是首发目标，任何实现都不应偷偷把它们拉进来：

- 内嵌 Node.js runtime
- 内嵌 Chromium / browser automation runtime
- Termux / root / ADB 依赖
- 任意 shell 作为基础能力
- 完整复刻 OpenClaw Gateway 协议
- 完整复刻 NanoClaw 容器安全模型
- 多模块复杂 Gradle 拆分
- “后台永不掉任务”的保活工程
- 重型 plugin / installer 体系

---

## 17. 架构决策记录（初始）

### A-001
AndroidClaw 是 **Android-native semantic reimplementation**，不是 OpenClaw / NanoClaw 桌面 runtime 移植。

### A-002
基线 APK 禁止 Node / Docker / Chromium / arbitrary shell。

### A-003
系统角色定义为 **Local Gateway + Local Node + Native GUI**。

### A-004
仓库初期保持单 `:app` 模块，优先 package 分层而不是 Gradle 多模块。

### A-005
所有核心状态必须落库；内存不是唯一真相源。

### A-006
工具体系以 typed native tools 为核心，不把 shell 当基础设施。

### A-007
FakeProvider 是一等公民，不是测试补丁。

### A-008
前台受限能力必须显式建模为 `foregroundRequired`。

---

## 18. 何时必须更新本文件

出现以下任一情况，必须更新：

- 包结构变动
- 新增或移除核心层
- Provider 抽象变动
- Tool contract 变动
- Session / Task / Skill 的核心数据模型变动
- scheduler 执行路径变动
- 引入重要依赖（尤其是影响 APK / startup / RAM 的）
- remote bridge 从“可选增强”升级为基线能力

---

## 19. 外部参考（供人和 Codex 核对）

- OpenAI Harness engineering  
  https://openai.com/index/harness-engineering/

- OpenClaw Android  
  https://docs.openclaw.ai/platforms/android

- OpenClaw Skills  
  https://docs.openclaw.ai/tools/skills

- OpenClaw Creating Skills  
  https://docs.openclaw.ai/tools/creating-skills

- OpenClaw Tools  
  https://docs.openclaw.ai/tools

- OpenClaw Cron Jobs  
  https://docs.openclaw.ai/automation/cron-jobs

- NanoClaw README  
  https://github.com/qwibitai/nanoclaw

- Android WorkManager / background tasks  
  https://developer.android.com/develop/background-work/background-tasks/persistent

- Android exact alarms  
  https://developer.android.com/about/versions/14/changes/schedule-exact-alarms

- Android foreground service restrictions  
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

- Android App Standby Buckets  
  https://developer.android.com/topic/performance/appstandby

- Android Baseline Profiles  
  https://developer.android.com/topic/performance/baselineprofiles/overview
