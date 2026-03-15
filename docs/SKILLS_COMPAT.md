# SKILLS_COMPAT

> 本文件定义 AndroidClaw 对 skills 的兼容策略、支持范围、解析规则、eligibility 规则、导入模型、slash invocation 语义，以及哪些能力明确不支持。  
> 任何对 `SKILL.md` 支持范围、前置要求、导入路径、slash command、tool dispatch 的变更，都必须更新本文件。

---

## 1. 我们对“技能”的定义

AndroidClaw 中的 skill，**以 OpenClaw 的运行时 `SKILL.md` 模型为准**：

- 一个 skill 是一个目录
- 目录里至少包含 `SKILL.md`
- 可附带静态资源
- 可来自 bundled / local / workspace
- 可被 enable / disable
- 可通过 slash command 调用
- 可为模型提供额外 instructions
- 可直接 dispatch 到 tool

这和 NanoClaw 的 `.claude/skills/*` 是不同概念。  
NanoClaw 的技能核心是“让 Claude Code 改写你的代码库”的 **开发时 / 安装时 skill**；  
AndroidClaw 要的是“装进 APK 后在手机上运行”的 **运行时 skill**。

### 结论
AndroidClaw 兼容的重点是：

- **OpenClaw runtime skills**
- **不是 NanoClaw compile-time code-mod skills**

---

## 2. 兼容目标与非目标

## 2.1 兼容目标
v1 必须尽量兼容以下行为：

- 目录 + `SKILL.md`
- YAML frontmatter + Markdown body
- bundled / local / workspace 三类来源
- precedence：workspace > local > bundled
- enabled / disabled
- eligibility / missing requirements
- slash command
- `disable-model-invocation`
- `command-dispatch: tool`
- `command-tool`
- `skills.entries.<skill>.env / apiKey` 的语义替代
- skill 资源文件与 `{baseDir}`

## 2.2 明确非目标
v1 不支持或不优先支持：

- NanoClaw 风格的 Claude Code 改代码 skill
- 依赖 `npm` / `pnpm` / `yarn` / `bun` / `brew` / `go` / `uv` 的安装型技能
- arbitrary shell / `exec` 作为 skill 基础能力
- desktop browser automation 依赖型 skill
- 插件系统自动装载 plugin-shipped skills
- ClawHub 原生同步 / 更新协议
- 重型文件系统 watcher
- 自动从外部共享目录扫描 skills

---

## 3. 兼容立场：语义兼容，不是逐字段全量兼容

本项目的目标是让用户能说：

- “我导入了一个 OpenClaw 风格 skill”
- “它能被识别、启用、禁用、调用”
- “它能显示缺什么权限、缺什么工具”
- “能跑的就本地跑，不能跑的明确告诉我为什么”

而不是让所有桌面 OpenClaw skill 在手机上零改动跑通。

---

## 4. Skill 来源与优先级

## 4.1 三类来源

### Bundled
路径：

```text
app/src/main/assets/skills/<skill-id>/
```

用途：

- 首发 demo skills
- 官方内置核心技能
- 离线可用能力

### Local
路径：

```text
files/skills/local/<skill-id>/
```

用途：

- 用户导入的共享技能
- 对 bundled skill 的本地覆盖

### Workspace
路径：

```text
files/workspaces/<session-id>/skills/<skill-id>/
```

用途：

- 仅在当前 session / workspace 生效的专用 skill
- 多会话差异化配置

## 4.2 优先级
若 skill `name` 冲突，优先级固定为：

1. workspace
2. local
3. bundled

这是语义兼容要求，不可改。

---

## 5. 文件格式

## 5.1 基本结构
每个 skill 至少包含：

```text
<skill-dir>/
  SKILL.md
  ...optional resources...
```

## 5.2 `SKILL.md`
由两部分构成：

1. YAML frontmatter
2. Markdown body（技能说明 / 指令）

### 最小示例

```md
---
name: hello_world
description: A simple greeting skill.
---

When the user asks for a greeting, respond with a short hello.
```

---

## 6. 解析规则

## 6.1 必需字段
v1 视为必需：

- `name`
- `description`

缺失则 skill 进入 `INVALID` 状态。

## 6.2 v1 主动支持的 frontmatter 字段

- `name`
- `description`
- `homepage`
- `user-invocable`
- `disable-model-invocation`
- `command-dispatch`
- `command-tool`
- `command-arg-mode`
- `metadata`

## 6.3 未知字段
未知字段必须：

- 保留在 parsed model 中
- 在详情页中可见
- 不因未知字段而直接判 invalid

除非：
- frontmatter 整体无法解析
- 字段类型明显破坏核心结构

## 6.4 关于 `metadata`
OpenClaw 官方文档强调其常见格式是单行 JSON blob。  
AndroidClaw v1 的 parser 必须：

1. 至少兼容 OpenClaw 常见写法
2. 尽量兼容普通 YAML map
3. 在归一化时保留原始文本或可回显结构

也就是说：  
**兼容更宽松可以，但不能比 OpenClaw 常见格式更苛刻。**

---

## 7. 支持的语义

## 7.1 `user-invocable`
- 默认 `true`
- 为 `true` 时可作为 slash command 使用
- 为 `false` 时不在 slash command UI 里暴露，但仍可参与模型提示装配（除非被禁用）

## 7.2 `disable-model-invocation`
- 默认 `false`
- 为 `true` 时：
  - skill 不自动进入普通模型 prompt
  - 但用户仍可手动 slash 调用

## 7.3 `command-dispatch: tool`
- slash 命令直接绕过模型
- 由 runtime 直接调用 `command-tool`

## 7.4 `command-tool`
- 指向一个已注册 typed tool
- 如果目标 tool 不存在，则 skill 处于 `MISSING_TOOL` / `INELIGIBLE`

## 7.5 `command-arg-mode`
v1 仅支持：

- `raw`（默认）

行为：

```json
{
  "command": "<raw args>",
  "commandName": "<slash command>",
  "skillName": "<skill name>"
}
```

传给目标 tool。  
v1 不做额外核心参数解析。

---

## 8. `metadata` 支持策略

## 8.1 `metadata.openclaw`
v1 计划支持或识别以下字段：

- `skillKey`
- `homepage`
- `primaryEnv`
- `requires.env`
- `requires.config`
- `requires.bins`
- `requires.anyBins`

### 说明

#### `skillKey`
用于配置覆盖键；如果不存在，则默认使用 `name`。

#### `primaryEnv`
用于把 `apiKey` 映射到主环境变量名。

#### `requires.env`
要求该 skill 拥有某些环境变量。  
AndroidClaw 用本地 config + Keystore 实现这一语义，而不是 host process `process.env`。

#### `requires.config`
v5 当前把声明出来的 config path 当作 **per-skill string field** 处理。  
存储键使用 `skillKey + config path`，eligibility 只检查该值是否为非空字符串。  
当前不做 typed mapping、host-level config bridge、也不做自动 provider/tool 注入。

#### `requires.bins` / `requires.anyBins`
在 AndroidClaw v1 中，默认视为 **本地不支持**。  
因为基线包不提供通用 shell / package manager / PATH 语义。

这类 skill 应显示为：

- `UNSUPPORTED_ON_ANDROID`  
或
- `BRIDGE_ONLY`（未来 bridge 模式）

### `metadata.openclaw.os`
v1 **不作为硬性 gate**。  
原因：OpenClaw 当前主要用它表达桌面 host OS，不能可靠表达 Android。  
Android 特定 eligibility 应写在 `metadata.android` 下。

---

## 8.2 AndroidClaw 自定义扩展：`metadata.android`
为了把“手机本地能不能跑”表达清楚，AndroidClaw 定义以下扩展字段：

```yaml
metadata:
  android:
    permissions: ["POST_NOTIFICATIONS", "READ_CALENDAR"]
    foregroundRequired: false
    requiresTools: ["tasks.list", "calendar.events"]
    bridgeOnly: false
```

### v1 支持字段

#### `permissions`
列出此 skill 运行所需的 Android 运行时权限或特权能力。

#### `foregroundRequired`
为 `true` 时，表示 skill 只能在 App 位于前台时执行。  
适用于 camera / screen / microphone 一类能力。

#### `requiresTools`
列出此 skill 依赖的 native tool 名称。  
缺失时该 skill 标记为 `MISSING_TOOL`。

#### `bridgeOnly`
为 `true` 时：
- skill 可被导入和展示
- 本地不执行
- UI 显示“需要未来 remote bridge”

### 不支持的扩展字段
未知 `metadata.android.*` 需要保留，但 v1 可不生效。

---

## 9. Eligibility 模型

Skill 在运行时必须有一个结构化 eligibility 结果，而不是只有“能用/不能用”。

当前实现的 eligibility 状态较小：

- `ELIGIBLE`
- `INVALID`
- `MISSING_TOOL`
- `BRIDGE_ONLY`

### v5 说明
- enabled / disabled 仍然是独立的 skill 开关，不编码进 eligibility enum
- 缺失 `requires.env`
- 缺失 `requires.config`
- 缺失 required tool
- tool 权限/能力受阻

当前都会落到 `MISSING_TOOL`，再通过：

- eligibility reasons
- `secretStatuses`
- `configStatuses`

把具体缺失项展示给 UI 和 `skills.list`。

### 设计原则
- 一条 skill 可以同时有多个 missing requirements。
- UI 必须把原因列出来。
- 不能 silent fail。
- 解析成功 ≠ 可执行；eligible 是第二层判定。

---

## 10. Import 模型

## 10.1 v1 导入方式
仅支持：

- Document Picker 选择 zip
- 导入到 `files/skills/local/`

## 10.2 zip 内容约定
zip 可以包含：

- 单个 skill 目录
- 多个 skill 目录

每个目录都必须至少包含 `SKILL.md`。

## 10.3 安装策略
建议按 skill `name` 归档到 local skill root：

```text
files/skills/local/<skill-name>/
```

如果同名 local skill 已存在：

- 默认替换同名 local skill
- 更新 snapshot
- 保留 import provenance

### 为什么不拒绝同名
因为 OpenClaw skill 体系本来就依赖“同名覆盖”的 precedence 语义。  
完全禁止冲突会破坏用户心智。

## 10.4 安全与范围
导入时必须做：

- zip path traversal 防护
- realpath 限定在 app-private root
- 文件大小与总解压大小上限
- 仅允许 skill root 与资源文件落在目标目录下

---

## 11. 加载与快照

## 11.1 加载时机
v1 在这些时机刷新 skill snapshot：

- App 启动
- skill 导入后
- enable / disable 后
- workspace skill 变化后
- 用户手动刷新

## 11.2 不做 watcher
v1 不实现常驻文件 watcher。  
这是有意的轻量化决定。

## 11.3 Skill snapshot 内容
建议持久化以下信息：

- effective source
- parsed frontmatter
- body summary
- enabled state
- eligibility
- missing requirements
- resolved baseDir
- updatedAt

这样 UI 不必每次重新扫描所有文件。

---

## 12. Slash invocation 语义

## 12.1 Slash 名称
默认使用 `name` 作为 slash command 名称：

```text
/<name>
```

## 12.2 Tool-dispatch skill
若 skill 指定：

```yaml
command-dispatch: tool
command-tool: tasks.list
```

则：

1. 用户输入 `/skillName raw args`
2. 不调用模型
3. 直接调用目标 tool
4. 将结果作为普通 assistant/tool 输出落库

## 12.3 Prompt skill
若 skill 没有 `command-dispatch: tool`，则 slash 调用表示：

- 本次 turn 强制显式启用该 skill
- `raw args` 作为该 skill 的输入提示
- 进入普通 `AgentRunner`

### 实现建议
Prompt skill 的 slash 调用不要绕开会话系统；  
它仍然是一种普通对话 turn，只是带了明确 skill 约束。

---

## 13. 普通聊天中的 skill prompt 装配

对于非 slash 的普通 turn：

1. 取当前 session 可见 skills（workspace + local + bundled）
2. 应用 enabled state
3. 应用 eligibility
4. 过滤掉 `disable-model-invocation = true`
5. 把剩余 skills 的 body 组装进 system instruction / skill block

### 约束
- 不要把所有 skill 原文无脑塞进 prompt。
- 应使用有序 skill block，必要时裁剪。
- 可优先包含用户最近用过或明确启用的 skill。
- 组装策略必须可测、可观察。

---

## 14. 配置与 secrets

## 14.1 AndroidClaw 的等价物
OpenClaw 里的：

- `skills.entries.<skill>.enabled`
- `skills.entries.<skill>.env`
- `skills.entries.<skill>.apiKey`

在 AndroidClaw 中映射为：

- Room 里的 enabled state / skill inventory
- `DataStore` 中按 `skillKey` 保存的非敏感 config path 字符串值
- `Keystore`-backed encrypted storage 中按 `skillKey + envName` 保存的 secret
- Skills 页面里的 per-skill Configure 对话框

## 14.2 注入原则
- secret 不进 prompt
- secret 不进 message history
- secret 不进普通 event log
- v5 当前用这些值驱动 eligibility 和 Skills UI
- v5 当前 **不**承诺 generic tool/provider env injection 已经落地

## 14.3 `primaryEnv`
若 skill 指定 `metadata.openclaw.primaryEnv`，Skills 页面会把它显示为对应 env 名的 secret field。

### v5 行为
- 保存后只显示 `Configured` / `Not configured`
- 不把 secret 明文重新显示给用户
- 清除后 eligibility 会重新计算

## 14.4 v5 当前支持的最小配置面

当前已落地：

- 每个 effective skill 的 `enabled`
- `skillKey` 作为 config / secret 的稳定键
- `primaryEnv` / `requires.env` 对应的 secret fields
- `requires.config` 对应的 string config fields
- 保存/清除后即时刷新 eligibility、`secretStatuses`、`configStatuses`

当前仍未落地：

- generic host-process env injection
- arbitrary runtime secret injection into every tool/provider
- typed config editors
- `requires.bins` / `requires.anyBins` 本地支持

---

## 15. 资源文件与 `{baseDir}`

Skill body 允许引用 `{baseDir}`。  
AndroidClaw 在运行时应把它解析为：

- 当前 effective skill root 的 app-private absolute path

### 约束
- 只能指向 skill 自己目录
- 不能逃逸到外部目录
- `files.*` 工具访问也必须受 workspace / skill root 规则约束

---

## 16. 与 tools 的关系

AndroidClaw skill 的核心依赖不是 shell，而是 `ToolRegistry`。

### 原则
- skill 可以教模型“什么时候用哪个 tool”
- skill 也可以通过 slash 直接 dispatch 到 tool
- 但 skill 不能默认获得“任意命令执行”

### 结果
为了兼容少数桌面 skill，不允许把整个 App 变成重型 shell host。

---

## 17. 错误码与诊断建议

建议为 skill 系统定义稳定错误码：

- `SKILL_NOT_FOUND`
- `SKILL_DISABLED`
- `SKILL_INVALID_FRONTMATTER`
- `SKILL_MISSING_SKILL_MD`
- `SKILL_UNSUPPORTED_FEATURE`
- `SKILL_MISSING_PERMISSION`
- `SKILL_MISSING_TOOL`
- `SKILL_MISSING_ENV`
- `SKILL_MISSING_CONFIG`
- `SKILL_FOREGROUND_REQUIRED`
- `SKILL_UNSUPPORTED_ON_ANDROID`
- `SKILL_BRIDGE_ONLY`

### UI 要求
Skills 页面必须能看到：

- source type
- enabled state
- missing requirements
- invalid reason
- tool dispatch target（如有）
- body / metadata 预览

---

## 18. 示例

## 18.1 最小 prompt skill

```md
---
name: summarize_session
description: Summarize the current session.
user-invocable: true
disable-model-invocation: false
---

When asked, summarize the current session in short bullet points and finish with one suggested next action.
```

## 18.2 最小 tool-dispatch skill

```md
---
name: list_tasks
description: List current scheduled tasks.
user-invocable: true
command-dispatch: tool
command-tool: tasks.list
command-arg-mode: raw
metadata:
  android:
    requiresTools: ["tasks.list"]
---

List scheduled tasks for the user.
```

---

## 19. 测试要求

## 19.1 Parser tests
必须覆盖：

- 最小合法 skill
- 缺失 `name`
- 缺失 `description`
- 无效 frontmatter
- `metadata` JSON blob
- 普通 YAML `metadata`
- 未知字段保留

## 19.2 Precedence tests
必须覆盖：

- bundled only
- local 覆盖 bundled
- workspace 覆盖 local
- 同名 skill 的有效解析

## 19.3 Eligibility tests
必须覆盖：

- missing tool
- missing permission
- missing env
- unsupported bins
- bridgeOnly
- foregroundRequired

## 19.4 Invocation tests
必须覆盖：

- slash -> prompt skill
- slash -> tool dispatch skill
- disable-model-invocation 的过滤
- enabled / disabled 切换后的行为

## 19.5 Import tests
必须覆盖：

- zip 单 skill
- zip 多 skill
- path traversal 防护
- 同名 local 替换
- import 后 snapshot 更新

---

## 20. 初始决策记录

### K-001
AndroidClaw 的 skill 兼容目标是 OpenClaw runtime skills，不是 NanoClaw 的 Claude Code code-mod skills。

### K-002
skill 的基本单位是目录 + `SKILL.md` + 可选资源。

### K-003
precedence 固定为 workspace > local > bundled。

### K-004
v5 支持 slash、tool dispatch、eligibility，以及最小 per-skill config/env/apiKey 存储语义；generic runtime injection 仍是后续工作。

### K-005
v1 不支持 installer / package-manager / arbitrary shell 型 skill。

### K-006
`requires.bins` / `requires.anyBins` 在本地默认视为 unsupported 或 bridge-only。

### K-007
v1 不做重型 file watcher；只做显式刷新。

---

## 21. 何时必须更新本文件

- 新增支持的 frontmatter 字段
- `metadata.openclaw` 解析策略变化
- `metadata.android` 扩展变化
- precedence 变化
- import 策略变化
- slash invocation 语义变化
- tool dispatch 参数 shape 变化
- skill config / secret 注入方式变化

---

## 22. 外部参考

- OpenClaw Skills  
  https://docs.openclaw.ai/tools/skills

- OpenClaw Creating Skills  
  https://docs.openclaw.ai/tools/creating-skills

- OpenClaw Skills Config  
  https://docs.openclaw.ai/tools/skills-config

- OpenClaw Tools  
  https://docs.openclaw.ai/tools

- OpenClaw Android platform docs  
  https://docs.openclaw.ai/platforms/android

- OpenClaw Nodes / troubleshooting  
  https://docs.openclaw.ai/nodes  
  https://docs.openclaw.ai/nodes/troubleshooting

- NanoClaw README  
  https://github.com/qwibitai/nanoclaw
