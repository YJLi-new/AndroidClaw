# SCHEDULER

> 本文件定义 AndroidClaw 的调度系统：语义、约束、数据模型、状态机、后台执行策略、重试策略、UI 诊断要求。  
> 任何对 `once / interval / cron` 语义、run history、execution mode、后台唤醒路径的修改，都必须更新本文件。

---

## 1. 目标与范围

AndroidClaw 的 scheduler 要保留 OpenClaw / NanoClaw 用户最在意的体验：

- `once`
- `interval`
- `cron`
- `MAIN_SESSION`
- `ISOLATED_SESSION`
- `Run now`
- durable task history
- durable run history
- retry with backoff
- GUI 可见的 next run / last run / failure reason

但实现必须完全 Android-native。  
**不允许**用桌面 daemon 心智把手机当常驻服务器。

---

## 2. 关键事实（决定实现边界）

- Android 进程寿命不由应用自己控制；系统会在内存压力和生命周期变化时终止进程。
- WorkManager 是 Android 推荐的持久后台工作机制，适合“必须最终运行”的 deferrable work。
- WorkManager 的 periodic / flex 粒度有下限，15 分钟是重要平台界线。
- exact alarm 在 Android 14+ 对大多数新装应用默认不是预授予；只有真正需要精确时点的用户可感知行为才应走 exact。
- exact alarm 权限变化会取消未来 exact alarms，必须响应权限变化。
- Android 14+ 对从后台启动需要 while-in-use 权限的前台服务有硬限制；camera / microphone / location 相关能力不能假设“后台照样跑”。
- App Standby Buckets、Doze、OEM 电池策略都会影响 jobs 和 alarms 的实际触发时机。
- OpenClaw 的 cron 语义是：scheduler 持久化 + main vs isolated run + run history + delivery；NanoClaw 也支持 scheduled tasks 并把它当核心功能。

因此，AndroidClaw 的 scheduler 要追求的是：

1. **durability**
2. **correctness**
3. **visibility**
4. **lightweight implementation**

不是“隐身常驻”或“伪无限保活”。

---

## 3. 非目标

以下内容不是 v1 scheduler 的目标：

- 不做独立 heartbeat 子系统
- 不做自然语言 schedule parser
- 不承诺所有 OEM 上秒级精确
- 不做常驻 polling loop / while(true)
- 不用常驻 foreground service 模拟 daemon
- 不自动绕过平台后台限制
- 不让后台任务偷偷启动 camera / mic / screen 功能

---

## 4. 用户可见语义

## 4.1 Schedule kinds

### `once`
在某个未来时点执行一次。  
适合提醒、一次性抓取、一次性汇总。

### `interval`
按固定间隔重复执行。  
v1 背景最小间隔为 **15 分钟**。  
如果用户请求更小值，UI 应拒绝或明确标记“仅前台/不支持”。

### `cron`
v1 支持：

- 5-field cron：`minute hour day-of-month month day-of-week`
- macros：
  - `@hourly`
  - `@daily`
  - `@weekly`
  - `@monthly`

v1 不支持：

- 6-field seconds cron
- Quartz 扩展语法
- `L`, `W`, `#` 等高级别名

这是有意的轻量化决策。

---

## 4.2 Execution modes

### `MAIN_SESSION`
任务直接在目标会话上下文中运行。  
结果直接进入目标 session 的 message history。

适合：

- 提醒
- 日常续写
- 依赖当前会话上下文的整理
- 轻量通知或 follow-up

### `ISOLATED_SESSION`
每次运行都创建独立 runtime context 和独立 workspace root。  
最终结果（摘要 / 最终回复）回投到目标 session；完整过程保留在 `TaskRun` 里。

适合：

- 噪声较大的定期分析
- 不希望污染主会话上下文的任务
- 未来 remote bridge 的候选任务

**不等于容器隔离。**  
v1 的 isolated 只表示“上下文和文件根分离”。

---

## 4.3 Precision modes

建议在数据模型中显式表示：

- `APPROXIMATE`
- `PRECISE_USER_VISIBLE`

### `APPROXIMATE`
默认模式。  
走 WorkManager；接受平台延迟。

### `PRECISE_USER_VISIBLE`
仅用于用户明确需要准点的提醒 / 通知。  
优先尝试 exact alarm；如果权限不可用，则回退到 WorkManager，并在 UI 中显示“已降级为近似触发”。

---

## 4.4 Run now
每个 task 必须支持 `Run now`。  
这是调试、回归、演示和自修复的核心能力，不是辅助功能。

`Run now`：

- 不改写 schedule spec
- 生成独立 `TaskRun`
- 默认 `triggerKind = MANUAL`
- 结束后仍按原 schedule 计算下一次触发

## 4.5 Typed task tools
调度合同不只存在于 GUI，也存在于 runtime tools。  
v5 的最小内建 task tool surface 为：

- `tasks.list`
- `tasks.get`
- `tasks.create`
- `tasks.update`
- `tasks.enable`
- `tasks.disable`
- `tasks.delete`
- `tasks.run_now`

这些工具的约束：

- 输入必须是 typed JSON 字段，不做自然语言 schedule parser
- `tasks.create` / `tasks.update` 只接受当前数据模型已经支持的 schedule 语义：
  - `once`
  - `interval`
  - `cron`
- `targetSessionAlias=current` 只能从当前 `ToolExecutionContext.sessionId` 解析
- `targetSessionAlias=main` 解析到 main session
- `once` 必须是未来时间
- `interval` 必须满足后台最小间隔
- `cron` 必须 parse 成功且能算出下一次运行
- `precise=true` 只是用户请求，返回 payload 必须诚实反映最终是 exact 还是 degraded approximate
- `tasks.run_now` 必须返回明确 queued 结果，不能是 silent no-op
- `tasks.delete` 必须先取消未来 work，再删除 task，避免 orphaned scheduled work

tool 返回的 task payload 至少要包含：

- `scheduleKind`
- canonical `schedule` object
- `executionMode`
- target session / resolved target session
- `enabled`
- `nextRunAt`
- `preciseRequested`
- effective scheduling path
- precision warnings
- latest run summary（如果存在）

---

## 5. Android 约束如何映射为实现策略

## 5.1 核心选择
**所有 schedule kinds 的主调度原语都使用 OneTimeWorkRequest + 自己计算下一次 due time。**

理由：

- `cron` 天然不是固定 periodic work；
- `interval` 也需要 run history、backoff、pause/resume、manual run 等统一语义；
- WorkManager unique work 很适合“每个 task 只保留一个下一次唤醒”；
- 统一实现更轻、更可测。

### 结论
**不要把 `PeriodicWorkRequest` 当核心语义实现。**

它可以作为实验手段，但不是主设计。

---

## 5.2 WorkManager 是主路径
对于所有非精确任务：

- 持久化 next due time 到 DB
- 计算 `initialDelay`
- `enqueueUniqueWork("task-next:<taskId>", REPLACE, oneTimeWorkRequest)`

### 唯一命名原则
每个 task 在任一时刻最多只有一个“下一次唤醒 work”。

命名建议：

- `task-next:<taskId>` — 下一次计划唤醒
- `task-run-now:<taskId>` — 手动运行（可选独立名）
- `task-maintenance:reschedule-all` — 全局重排（可选）

---

## 5.3 Exact alarm 只是一条有限增强路径
使用 AlarmManager exact alarm 的条件必须全部满足：

1. 任务标记为 `PRECISE_USER_VISIBLE`
2. 行为对用户是明确的准点提醒 / 提示
3. exact alarm 权限可用
4. 该任务不依赖后台 camera / microphone / screen 等前台受限能力

如果不满足上述条件，**不走 exact alarm**。

---

## 5.4 Exact alarm 与 worker 的关系
AlarmReceiver 不做真正的 agent work。  
它只做极小工作：

1. 校验 `taskId`
2. 将一次实际执行委托给 WorkManager 或直接启动轻量 execution path
3. 立即返回

推荐路径：

```text
Exact alarm
  -> BroadcastReceiver
  -> enqueue unique one-time execution work
  -> Worker executes task
```

这样可以：

- 保持幂等
- 统一 run history
- 让日志与错误处理走同一套路径
- 避免 receiver 变成复杂执行环境

---

## 5.5 后台前台边界
如果某个计划任务在后台被唤醒，但需要：

- `camera`
- `microphone`
- `screen`
- 仅 while-in-use 可用的 location

则 scheduler 必须：

- 不要偷偷从后台启动受限 foreground service
- 直接以结构化错误失败
- 给出明确恢复建议（例如“打开 App 后重试”）

---

## 6. 数据模型（建议）

## 6.1 `TaskEntity`

建议字段：

- `id: String`
- `name: String`
- `enabled: Boolean`
- `paused: Boolean`
- `scheduleType: ONCE | INTERVAL | CRON`
- `scheduleSpecJson: String`
- `timezoneId: String`
- `executionMode: MAIN_SESSION | ISOLATED_SESSION`
- `precisionMode: APPROXIMATE | PRECISE_USER_VISIBLE`
- `prompt: String`
- `targetSessionId: String`
- `nextRunAtEpochMs: Long?`
- `lastRunAtEpochMs: Long?`
- `lastRunStatus: String?`
- `backoffUntilEpochMs: Long?`
- `consecutiveFailureCount: Int`
- `createdAtEpochMs`
- `updatedAtEpochMs`
- `deletedAtEpochMs?`

### 约束
- main session 任务和 isolated 任务都共用一张 task 表。
- schedule spec 以结构化 JSON 存，不用字符串拼协议。
- `nextRunAtEpochMs` 必须可以由纯函数重算。

## 6.2 `TaskRunEntity`

建议字段：

- `id: String`
- `taskId: String`
- `triggerKind: SCHEDULED | MANUAL | ALARM`
- `scheduledForEpochMs: Long?`
- `startedAtEpochMs: Long`
- `finishedAtEpochMs: Long?`
- `status: RUNNING | SUCCESS | FAILED_RETRYABLE | FAILED_TERMINAL | SKIPPED | CANCELLED`
- `attemptNumber: Int`
- `errorCode: String?`
- `errorMessage: String?`
- `recoveryHint: String?`
- `resultSummary: String?`
- `outputMessageId: String?`
- `isolatedSessionId: String?`
- `workspacePath: String?`

### 约束
- 每次真实执行都必须写一条 `TaskRun`。
- 不允许“任务执行了但历史里没有记录”。

## 6.3 计算纯函数
以下逻辑必须纯函数化，便于单测：

- `parseCronExpression()`
- `computeNextRun()`
- `computeNextRetry()`
- `qualifiesForExactAlarm()`
- `resolveTaskAvailability()`

---

## 7. 状态机

## 7.1 Task 状态

```text
ACTIVE ──pause──> PAUSED
ACTIVE ──disable──> DISABLED
PAUSED ──resume──> ACTIVE
DISABLED ──enable──> ACTIVE
(any) ──delete──> DELETED
```

约束：

- `PAUSED` / `DISABLED` 均不应继续保留有效的下一次 work / alarm。
- `DELETED` task 不应再被重排。

## 7.2 Run 状态

```text
PENDING -> RUNNING -> SUCCESS
                 \-> FAILED_RETRYABLE
                 \-> FAILED_TERMINAL
                 \-> SKIPPED
                 \-> CANCELLED
```

### `FAILED_RETRYABLE`
例如：

- 网络临时失败
- provider 5xx / rate limit
- 可重试的远程 API 错误
- Work interrupted

### `FAILED_TERMINAL`
例如：

- 权限永久缺失
- 需要前台但当前后台运行
- skill / tool 配置缺失
- 无效 cron / task spec
- 本地明确 unsupported

### `SKIPPED`
例如：

- task 在 worker 执行前已经被禁用
- 旧 due token 被新计划替换
- 任务被判定为重复执行

---

## 8. 调度算法（标准路径）

## 8.1 Create / Update / Resume 时

1. 读 task
2. 校验 spec
3. 计算 `nextRunAt`
4. 更新 DB
5. 取消旧的 unique work / old alarm
6. 根据 precision 选择 backend：
   - exact alarm path
   - WorkManager path
7. 记录 event log

## 8.2 Worker 被唤醒时

1. 根据 `taskId` 读 task
2. 如果 task 不存在 / 已删除 / 已停用 → 写 `SKIPPED`
3. 如果 due time 未到（例如早唤醒）→ 重新安排并退出
4. 插入 `TaskRun(status=RUNNING)`
5. 调用统一执行路径（复用 `AgentRunner`）
6. 根据结果更新 `TaskRun`
7. 计算下一次 run / retry
8. 更新 task + enqueue 下一次

## 8.3 幂等规则

Worker 必须以 `(taskId, scheduledForEpochMs, triggerKind)` 近似定义一次执行实例。  
若相同实例已存在完成记录，则后续重复唤醒直接 `SKIP_DUPLICATE`。

---

## 9. Main vs Isolated 执行细节

## 9.1 MAIN_SESSION
- 直接读取目标 session 历史
- 使用目标 session 的 workspace
- 最终 assistant / tool 结果进入该 session
- 最适合提醒、轻整理、续上下文

## 9.2 ISOLATED_SESSION
- 创建 `isolatedSessionId`
- 创建独立 workspace root
- 任务在 isolated context 内完整运行
- 最终只把摘要 / 最终答复投递到目标 session
- 全过程细节保留在 `TaskRun`

### 注意
isolated run 的完整对话不必全部合并到主会话。  
这是减少主上下文污染的关键设计。

---

## 10. 时间、时区与系统事件

## 10.1 时区存储
- `once`：最终以 UTC 时间戳存储
- `interval`：记录 interval 本身，不依赖本地时区
- `cron`：必须持久化 IANA `timezoneId`

默认时区：**创建 task 时的设备时区**。

## 10.2 时区变化
收到以下系统变化时，应触发全局重排：

- `ACTION_TIMEZONE_CHANGED`
- `ACTION_TIME_CHANGED`
- `BOOT_COMPLETED`
- `MY_PACKAGE_REPLACED`

### 重排策略
不要在广播接收器里做复杂计算。  
当前实现保持 receiver 很薄：只异步触发 `schedulerCoordinator.rescheduleAll()`，让 task 表重新成为唯一事实来源。

---

## 11. 重试策略

## 11.1 默认退避
建议采用离散退避序列：

- 1 分钟
- 5 分钟
- 15 分钟
- 60 分钟
- 3 小时
- 6 小时（上限）

### 原则
- 退避是针对一次 `TaskRun` 的补救，不应无限增长。
- recurring task 如果下一次正常 schedule 比 retry 更早，则优先下一次正常 schedule。
- one-shot task 允许有限次 retry，之后终态失败。

## 11.2 错误分级建议

### Retryable
- `NETWORK_UNAVAILABLE`
- `PROVIDER_RATE_LIMITED`
- `PROVIDER_TEMPORARY_FAILURE`
- `HTTP_5XX`
- `WORK_INTERRUPTED`

### Terminal
- `TASK_DISABLED`
- `INVALID_SCHEDULE`
- `MISSING_API_KEY`
- `MISSING_PERMISSION`
- `FOREGROUND_REQUIRED`
- `UNSUPPORTED_ON_ANDROID`
- `SKILL_INVALID`
- `TOOL_NOT_AVAILABLE`

---

## 12. 与 skills / tools 的关系

Scheduler 不直接理解业务 prompt。  
它只负责：

- 触发时间
- 执行模式
- 重试
- 结果落库
- 下一次重排

真正的任务内容仍交给：

- `SkillManager`
- `ToolRegistry`
- `AgentRunner`

### 例外
scheduler 必须在执行前做最小可用性检查，例如：

- provider 是否配置
- target session 是否存在
- precise task 是否具备 exact path 条件
- foreground-only tool 是否显然无法后台运行

---

## 13. GUI / observability 要求

## 13.1 Tasks 页面必须展示
- name
- enabled / paused
- schedule type
- execution mode
- target session
- next run
- last run
- last status
- run now
- precise / approximate 状态
- degraded 标识（如 exact alarm 不可用）

## 13.2 Health 页面必须展示
- scheduler enabled / disabled
- last scheduler wake
- last automation result
- exact alarm permission
- notification permission
- app notifications enabled / disabled
- precise reminder may execute but not visibly notify 的警告
- battery optimization status（能读到就读）
- standby bucket（能读到就读）
- OEM 说明入口

## 13.3 日志
每次关键事件都应写结构化 event log：

- task created
- task updated
- task paused / resumed
- due computed
- worker started
- worker skipped
- run success
- run failure
- next run scheduled
- exact alarm degraded

---

## 14. OEM 与用户预期管理

对 OPPO / vivo / 小米等机型，要直面现实：

- 后台任务可能被延迟
- 精确性可能受到系统策略影响
- 自启动 / 电池优化 / 最近任务锁定等设置可能影响可靠性

产品必须提供：

- 明确说明
- 可执行的修复路径
- 最近失败原因
- 下一次计划时间
- “现在手动运行” 入口

---

## 15. 调试 / 手工 QA 命令

以下命令用于 debug 构建或开发设备上的 scheduler 诊断：

### 15.1 App Standby Bucket

查看当前 bucket：

```bash
adb shell am get-standby-bucket ai.androidclaw.app
```

强制切换 bucket（Android 13+ / 16 文档仍推荐此路径做配额测试）：

```bash
adb shell am set-standby-bucket ai.androidclaw.app active
adb shell am set-standby-bucket ai.androidclaw.app working_set
adb shell am set-standby-bucket ai.androidclaw.app frequent
adb shell am set-standby-bucket ai.androidclaw.app rare
adb shell am set-standby-bucket ai.androidclaw.app restricted
```

### 15.2 JobScheduler / WorkManager

查看系统侧 JobScheduler 状态：

```bash
adb shell dumpsys jobscheduler ai.androidclaw.app
```

请求 WorkManager 诊断广播（debug builds）：

```bash
adb shell am broadcast -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" -p "ai.androidclaw.app"
```

### 15.3 AlarmManager

查看 alarm 状态：

```bash
adb shell dumpsys alarm | grep ai.androidclaw.app
```

从 WSL 触发 Windows AVD 的标准 smoke：

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/check_host_prereqs.sh --required-avd AndroidClawApi34
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.app.MainActivitySmokeTest
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_windows_android_test.sh --avd AndroidClawApi34 --test-class ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest
```

WSL 包装脚本会按以下顺序解析 Java：

1. `ANDROIDCLAW_JAVA_HOME`
2. `JAVA_HOME`
3. `PATH` 上的 `java`（版本必须 >= 17）

如果找不到 Java 17+，会在真正构建前直接失败。

### 15.4 Exact alarm 权限

在 Android 12+ 上跳转到 exact alarm special access：

```bash
adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:ai.androidclaw.app
```

Windows 侧标准准备脚本：

```bash
./scripts/setup_windows_android_emulator.sh --install-android-studio
```

标准 AVD 命名：

- `AndroidClawApi34`
- `AndroidClawApi31`

标准 exact-alarm 回归脚本：

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/check_host_prereqs.sh --required-avd AndroidClawApi34 --required-avd AndroidClawApi31
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 ./scripts/run_exact_alarm_regression.sh --api34-avd AndroidClawApi34 --api31-avd AndroidClawApi31
```

当前仓库的标准回归路径不再依赖 LDPlayer。API 34 fresh-install 用于 deny/degrade；API 31 用于 special-access revoke/grant/revoke 回归。

如果设备支持 app-compat overrides，可用以下命令测试 exact-alarm 权限要求的兼容变化：

```bash
adb shell am compat disable REQUIRE_EXACT_ALARM_PERMISSION ai.androidclaw.app
adb shell am compat enable REQUIRE_EXACT_ALARM_PERMISSION ai.androidclaw.app
```

产品不应承诺“零设置 100% 准点”。

---

## 15. 测试要求

## 15.1 单元测试
必须覆盖：

- cron parser
- next run calculator
- interval math
- timezone handling
- retry policy
- exact qualification logic
- pause / resume state changes

## 15.2 集成测试
必须覆盖：

- task create → enqueue unique work
- run now → TaskRun 写入
- scheduled run → session receives output
- isolated run → result回投主 session
- duplicate wake → skip duplicate
- disable task → no future work remains

## 15.3 Android 测试
建议覆盖：

- WorkManager test driver
- app restart / process recreation 后的持久状态
- boot/timezone changed 后的 reschedule
- exact alarm unavailable 时的 degrade path

### 禁止
- 用真实等待几分钟的方式测 scheduler
- 依赖真实 wall clock
- 只靠手点验证

---

## 16. 初始决策记录

### S-001
DB task state 是 scheduler 唯一真相源；内存只做临时缓存。

### S-002
所有 schedule kinds 的主原语使用 OneTimeWorkRequest + 自算 next due。

### S-003
exact alarm 只服务于 `PRECISE_USER_VISIBLE` 任务。

### S-004
AlarmReceiver 只负责委托，不负责真正 agent 执行。

### S-005
`Run now` 是必选能力。

### S-006
每次实际执行都必须写入 `TaskRun`。

### S-007
前台受限能力在后台运行时必须显式失败，而不是偷偷拉起复杂前台流程。

### S-008
v1 不实现 heartbeat 子系统。

---

## 17. 何时必须更新本文件

- schedule grammar 变化
- retry policy 变化
- main / isolated 语义变化
- exact alarm 使用策略变化
- worker / receiver 架构变化
- `TaskEntity` / `TaskRunEntity` 关键字段变化
- 引入新的后台执行原语
- UI 诊断字段变化

---

## 18. 外部参考

- OpenClaw Cron Jobs  
  https://docs.openclaw.ai/automation/cron-jobs

- OpenClaw Cron vs Heartbeat  
  https://docs.openclaw.ai/automation/cron-vs-heartbeat

- OpenClaw Session Management  
  https://docs.openclaw.ai/concepts/session

- NanoClaw README  
  https://github.com/qwibitai/nanoclaw

- Android Task scheduling / WorkManager  
  https://developer.android.com/develop/background-work/background-tasks/persistent

- Android Managing Work / unique work  
  https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work

- Android exact alarms  
  https://developer.android.com/about/versions/14/changes/schedule-exact-alarms

- Android schedule alarms  
  https://developer.android.com/develop/background-work/services/alarms

- Android foreground service restrictions  
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

- Android foreground service types  
  https://developer.android.com/develop/background-work/services/fgs/service-types

- Android App Standby Buckets  
  https://developer.android.com/topic/performance/appstandby

- Android process lifecycle  
  https://developer.android.com/guide/components/activities/process-lifecycle
