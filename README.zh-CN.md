<p align="center"><a href="README.md">English</a> · <b>简体中文</b></p>

<a href="https://androidclaw.top/zh/">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="site/assets/readme/banner-zh-dark.png">
    <img src="site/assets/readme/banner-zh-light.png" alt="AndroidClaw：一个 APK，手机就是宿主。旁边的手机显示聊天界面。" width="100%">
  </picture>
</a>

<p align="center"><b>小巧的原生安卓 AI 助手宿主。一个 APK，手机就是宿主。</b></p>

<p align="center">
  <a href="https://github.com/YJLi-new/AndroidClaw/releases/latest"><img src="https://img.shields.io/github/v/release/YJLi-new/AndroidClaw?label=release&color=78C92A" alt="最新版本"></a>
  <a href="https://github.com/YJLi-new/AndroidClaw/actions/workflows/android.yml"><img src="https://github.com/YJLi-new/AndroidClaw/actions/workflows/android.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-21313A" alt="许可证：Apache-2.0"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-78C92A?logo=android&logoColor=white" alt="Android 8.0 及以上">
</p>

<p align="center">
  <a href="https://androidclaw.top/zh/">官网</a> ·
  <a href="https://github.com/YJLi-new/AndroidClaw/releases/latest/download/AndroidClaw-latest.apk">下载 APK</a> ·
  <a href="CHANGELOG.md">更新日志</a> ·
  <a href="README.md">English</a>
</p>

| 聊天 | 工具卡片 | 发送照片 | 联网搜索 | 编辑任务 |
|:-:|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/zh/chat.png" width="150" alt="主会话：device_status 工具卡片和定时任务的运行结果" /> | <img src="site/assets/screens/zh/tool_card.png" width="150" alt="展开的 device_status 工具卡片，显示运行结果" /> | <img src="site/assets/screens/zh/photo_reply.png" width="150" alt="从照片选择器发送的照片缩略图与模型的回复" /> | <img src="site/assets/screens/zh/web_sources.png" width="150" alt="已联网搜索卡片与来源" /> | <img src="site/assets/screens/zh/task_editor.png" width="150" alt="编辑任务：cron 计划与接下来的运行时间" /> |

截图来自 Android 14 模拟器，对话是 OpenAI `gpt-6-luna` 的真实回复。

<details>
<summary>更多截图</summary>

这里有些较早的截图仍来自离线演示。

| 朗读 | 任务 | 技能 | 联网搜索设置 | 助手工作区 |
|:-:|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/zh/read_aloud.png" width="150" alt="正在朗读回复，输入框上方有停止朗读" /> | <img src="site/assets/screens/zh/tasks.png" width="150" alt="任务列表与运行记录" /> | <img src="site/assets/screens/zh/skills.png" width="150" alt="技能列表与开关，含一个导入的技能" /> | <img src="site/assets/screens/zh/settings_web_search.png" width="150" alt="设置：OpenAI 模型 gpt-6-luna，联网搜索为自动" /> | <img src="site/assets/screens/zh/workspace.png" width="150" alt="助手工作区：人设文件与提示词预算" /> |

| 生成图片 | 视频 | 图片附件 | 图片、视频与语音设置 | 诊断 |
|:-:|:-:|:-:|:-:|:-:|
| <img src="site/assets/screens/zh/generated_image.png" width="150" alt="在查看器中打开的 image_generate 生成图片，带保存和分享" /> | <img src="site/assets/screens/zh/video_tile.png" width="150" alt="带封面帧和播放标记的视频" /> | <img src="site/assets/screens/zh/attach_chip.png" width="150" alt="发送前的图片附件" /> | <img src="site/assets/screens/zh/settings_media_voice.png" width="150" alt="设置：图片与视频模型、语音" /> | <img src="site/assets/screens/zh/diagnostics.png" width="150" alt="诊断：模型服务、调度器、工具" /> |

| 设置 |
|:-:|
| <img src="site/assets/screens/zh/settings.png" width="150" alt="设置：模型服务、记忆、工作区" /> |

</details>

最后核对仓库日期：2026-11-03（v0.3.0）

## 这是什么

AndroidClaw 是一个只有一个 APK 的安卓 AI 助手宿主，整个运行时都在手机上：聊天会话存在 Room 数据库里，模型通过原生工具调用来使用工具，技能来自 `SKILL.md` 文件，定时任务由 WorkManager 和精确闹钟调度。它通过 HTTPS 连接你选择的模型服务，也可以使用内置的离线演示服务（不需要密钥，也不需要联网）。

它参照 NanoClaw 和 OpenClaw，用 Kotlin 为安卓重写了会话、类型化工具、`SKILL.md` 技能和定时自动化。它不内嵌 Node.js、Docker、浏览器内核或 shell，也不是桌面网关的远程伴侣应用。

## 功能

**0.3.0 新功能**

- **在聊天中发送照片。** 通过系统照片选择器选图，每条消息最多 4 张，不需要存储权限，发给支持视觉的模型。
- **联网搜索。** 保存 Brave Search 密钥后，所有模型服务都能用 `web_search` 工具；否则 OpenAI 和 Claude 使用服务商自带的搜索。
- **图片生成与编辑。** `image_generate` 通过 OpenAI Images API 生成图片，或编辑你附加的照片。
- **视频生成。** `video_generate` 发起 OpenAI 视频任务，后台任务在视频生成后把它发到对话里。
- **语音。** 点按麦克风用系统语音识别听写，每条回复都可以朗读。

**核心能力**

- **聊天。** 持久保存的会话和始终存在的主会话，支持 Markdown、流式输出与停止，工具调用以卡片显示。
- **37 个模型可用的工具。** 原生工具调用，覆盖会话、记忆、任务、技能、工作区文件、网页、媒体、系统意图和通知。
- **技能。** 内置、本地和工作区三层 `SKILL.md` 技能，支持斜杠命令、启用/停用和导入。
- **定时任务。** 一次性、按间隔和 cron 计划，结果发到聊天会话或在独立会话中运行。
- **工作区人设文件。** 参照 OpenClaw 的 `AGENTS.md`、`SOUL.md`、`USER.md`、`MEMORY.md`，注入系统提示词。
- **简体中文与英文。** 两种语言的完整界面，Android 13 及以上支持按应用设置语言。

<details>
<summary>功能细节：聊天、照片、联网搜索、图片与视频、语音</summary>

**聊天**
- 以对话记录为主的聊天界面：顶栏显示会话标题，侧边抽屉管理会话（新建、切换、重命名、归档、恢复、删除）。
- 支持 Markdown 和代码块（可一键复制）；流式输出，可随时停止。
- 工具调用以卡片显示在对话中：标题是结果摘要，下方是工具名和“完成 / 失败：错误码 / 运行中”；展开后显示格式化的参数和结果（疑似密钥的字段会被遮盖）。`MEMORY_DISABLED`、`FOREGROUND_REQUIRED`、`PERMISSION_REQUIRED` 等错误码附带本地化提示。直接调用工具的斜杠命令只显示卡片。
- 输入 `/` 时提示斜杠命令；右上角菜单提供搜索、分享/导出、用量统计、`/compact` 压缩历史和“语音回复”。
- 主会话始终存在，定时任务可以把结果发到主会话。

**在聊天中发送照片**
- 附件按钮打开系统照片选择器（任何 Android 版本都不需要存储权限）。每条消息最多 4 张图片，发送前以可移除的小卡片显示；可以只发图片不写文字。
- 导入时按采样解码（从不在内存中生成全分辨率位图），应用 EXIF 方向并去掉元数据，长边不超过 1568 像素，转为 JPEG，每张不超过 1.5 MB，另存一张 256 像素缩略图。图片按会话存在应用私有目录中，删除消息或会话时一并删除。
- 对话中以缩略图网格显示。点按图片打开全屏查看器，可保存（Pictures/AndroidClaw）和分享。导出聊天时会列出附件名。
- 图片发给支持视觉的模型：OpenAI、Codex、Claude、Gemini 默认开启；DeepSeek、GLM、Kimi、MiniMax 和自定义 OpenAI 兼容服务在服务商设置中有“模型接受图片”开关，关闭时附件按钮会说明原因。只重发最近 2 轮带图片的消息（最多 4 张、编码后 8 MB），更早的图片换成文字标记。

**联网搜索**

由一条规则决定搜索方式，显示在 设置 → 联网搜索（自动 / 关闭）中：
1. 保存了 Brave Search API 密钥 → 使用 `web_search` 工具，所有模型服务和定时任务都能用。
2. 没有 Brave 密钥，但模型服务自带搜索（OpenAI Responses、指向 `api.openai.com` 的 OpenAI 兼容服务、Claude）→ 使用服务商的内置搜索，只需要模型密钥。
3. 其他情况下搜索关闭，设置页会说明原因。

回复下方显示“已联网搜索：<查询>”卡片和来源行（最多 8 个链接，点按在浏览器中打开）。Brave 密钥加密保存，可用“测试”按钮检查。

**图片与视频生成**
- `image_generate` 通过 OpenAI Images API 生成图片，或编辑你附加的照片。无论当前用哪个聊天服务，它都使用已保存的 OpenAI API 密钥，只有存在该密钥时才提供给模型。生成的图片显示在对话中，同样可以查看、保存和分享。模型只看到 `Generated image <id>, <w>×<h>`。
- `video_generate` 发起 OpenAI 视频任务并立即返回。一个一次性的后台任务会检查进度（30 分钟后放弃），下载 mp4（不超过 50 MB），以带封面帧的消息发出并发送通知。点按即可用系统视频播放器播放。
- 设置 → 图片与视频 中可以修改模型（默认 `gpt-image-2.5-sunburst` 和 `sora-2`）。每次调用都会计入你的 OpenAI 账单；定时任务不能使用这两个工具。
- Debug 版本配合离线演示服务时会在本地画一张测试图片，所以不需要密钥也能体验图片生成；视频始终需要密钥。

**语音**
- 消息框为空且系统装有语音识别器时显示麦克风按钮，点按后由系统识别器听写。麦克风由系统识别器控制，所以应用没有 `RECORD_AUDIO` 权限。识别结果填入消息框；设置 → 语音 →“语音输入后立即发送”（默认关闭）可以直接发送。
- 朗读：每条助手回复都有朗读按钮，使用系统 TextToSpeech 引擎。朗读前去掉 Markdown，代码块换成一句简短说明；朗读时输入框上方一直有“停止朗读”。
- 语音回复（聊天菜单）：对你用语音输入的消息，回复会自动朗读。麦克风不会自己再次打开。
- 诊断 → 语音 显示识别器、TTS 引擎，以及 TTS 是否支持应用语言。

</details>

<details>
<summary>模型可用的工具（37 个）</summary>

只使用原生工具调用：工具定义放在服务商接口的 `tools` 字段里，不在系统提示词中重复列出（每轮约 14.6 KB 工具 JSON，单元测试保证不超过 24 KB）。

| 分组 | 工具 |
|---|---|
| 会话 | `sessions_list`、`sessions_history`、`sessions_create`、`sessions_rename`、`sessions_send` |
| 记忆 | `memory_search`、`memory_get`、`memory_remember`、`memory_forget` |
| 任务 | `tasks_list`、`tasks_create`、`tasks_update`、`tasks_delete`、`tasks_run_now`、`tasks_runs` |
| 技能 | `skills_list`、`skills_get`、`skills_set_enabled` |
| 工作区文件 | `workspace_list`、`workspace_read`、`workspace_write`、`workspace_edit` |
| 网页 | `web_fetch`（把公开的 http/https 网页抓取为文本，禁止访问内网地址）、`web_search`（Brave Search，只有保存了 Brave 密钥时才发给模型） |
| 媒体 | `image_generate`、`video_generate`（OpenAI，只有保存了 OpenAI 密钥时才发给模型） |
| 设备与系统意图 | `device_status`、`open_url`、`open_app`、`open_settings`、`set_alarm`、`set_timer`、`calendar_add_event`、`clipboard_get`、`clipboard_set` |
| 通知与健康 | `notify`、`health_status` |

- 另有两个只能通过斜杠命令使用、不会发给模型的工具：`sessions_compact`（`/compact`）和 `memory_command`（`/memory`）。
- 系统意图类工具（`open_*`、`set_alarm`、`set_timer`、`calendar_add_event`、`clipboard_*`）要求应用在前台。`calendar_add_event` 会打开预填好的日历页面，由用户自己保存。
- 定时任务运行时只能使用 19 个只读或显式开放的工具（`web_fetch`、`web_search`、设备状态、通知、工作区文件、记忆搜索/读取/记住、会话列表/历史/发送、任务列表/运行记录、技能列表/读取、健康状态）；前台专用工具在后台会返回 `FOREGROUND_REQUIRED`，收费的 `image_generate` 和 `video_generate` 永远不会提供给定时任务。

</details>

<details>
<summary>技能、定时任务、工作区、设置与诊断</summary>

**工作区人设文件**

参照 OpenClaw，`AGENTS.md`、`SOUL.md`、`USER.md`、`MEMORY.md` 保存在应用私有目录中，首次使用时按模板创建，并在 8 KB 预算内注入系统提示词（被截断时会标明）。助手可以用 `workspace_*` 工具读取和修改它们；你也可以在 设置 → 助手工作区 中编辑（文件列表与大小、提示词预算、等宽编辑器、确认后重置为模板、未保存提醒）。

**技能**
- 支持内置、本地和工作区三层 `SKILL.md` 技能：解析 frontmatter、按优先级覆盖、启用/停用（有风险的技能需要确认）、详情页、从文件导入。
- 内置技能：`/compact`、`/list_tasks`、`/memory`、`/summarize_session`、`/task_helper`。声明了 `command-dispatch: tool` 的技能直接调用工具，不经过模型。

**定时任务**
- 支持一次性、按间隔（预设最短 15 分钟）和五段式 cron 计划；使用本地日期、时间选择器，cron 会预览下一次运行时间。
- 结果可以发到某个聊天会话，也可以在新的独立会话中运行。
- 可以编辑已有任务、启用/停用、立即运行、查看运行记录；系统拒绝精确闹钟时会给出提示。
- Android 13 及以上在保存第一个任务时（以及在任务页提示条中）申请通知权限。
- 独立会话运行时会新建标题为 `<任务名> · <日期> <时间>` 的会话。

**设置与诊断**
- 模型服务配置与连接测试、联网搜索、图片与视频、语音、可选的本地记忆（附说明）、助手工作区编辑器、主题（跟随系统/浅色/深色）、应用语言（跟随系统/English/简体中文）。
- 诊断页：模型服务、网络、调度器、通知权限、语音、最近的自动化结果，可导出报告。
- 界面完整支持英文和简体中文（两种语言各有 519 条翻译字符串和 13 条复数字符串，由单元测试保证一致）。Android 13 及以上使用系统的按应用语言设置，Android 8 到 12 使用应用内切换。离线演示的回复和写给模型的工具摘要在任何语言下都是英文（真实模型会用你的语言回答）。系统照片选择器和语音识别器跟随系统语言。

</details>

## 开始使用

1. **安装。** 在 Android 8.0 及以上的手机上[下载 APK](https://github.com/YJLi-new/AndroidClaw/releases/latest/download/AndroidClaw-latest.apk)，也可以在[所有版本](https://github.com/YJLi-new/AndroidClaw/releases)中挑选。打开下载的文件；如果 Android 询问，允许浏览器安装应用。
2. **先试离线演示。** 首次打开时选择“使用离线演示”。演示服务在本机回复，不需要密钥，也不需要联网。
3. **填入模型服务密钥。** 在 设置 → 模型服务 中选择服务商，粘贴密钥并测试连接。可选：在联网搜索中保存 Brave Search 密钥，保存 OpenAI 密钥以使用图片与视频生成。

**支持的模型服务**

| 服务 | 接口 | 图片 | 内置联网搜索 |
|---|---|---|---|
| 离线演示（FakeProvider） | 本地、确定性回复、无需密钥 | 支持（回复图片数量和尺寸） | 用 `[search:<查询>]` 模拟 |
| OpenAI | Responses API（`/v1/responses`），默认模型 `gpt-6-luna` | 支持 | 支持 |
| OpenAI 兼容 | chat/completions；如果 Base URL 指向 `api.openai.com`，会自动改走 Responses API | 开关 | 仅 `api.openai.com` |
| OpenAI Codex | 设备码 OAuth 登录，Responses 流式输出 | 支持 | 不支持 |
| Claude | 原生 Anthropic Messages API | 支持 | 支持 |
| Gemini | OpenAI 兼容预设（chat/completions） | 支持 | 不支持 |
| MiniMax、GLM、Kimi、DeepSeek | OpenAI 兼容预设（chat/completions） | 开关 | 不支持 |

“开关”指“模型接受图片”设置，默认关闭。没有内置搜索的服务，只要保存了 Brave 密钥也能使用 `web_search`。所有远程服务都支持 SSE 流式输出，定时任务使用非流式路径。API 密钥、Brave Search 密钥和 Codex OAuth 凭据在设备上加密保存。

<details>
<summary>不用密钥试用：演示标记（Debug 版本）</summary>

Debug 版本默认使用离线演示服务，回复是确定的，消息中的标记可以控制它的行为：

| 发送 | 效果 |
|---|---|
| `What is my battery? [tool:device_status]` | 调用工具并显示工具卡片 |
| `[tool:<工具名> {json}]` | 用 JSON 参数调用任意工具 |
| `Draw a fox [tool:image_generate]` | 以 “Draw a fox” 为提示词调用 `image_generate`，Debug 版的模拟后端会画一张测试图片 |
| `Latest Android news [search:Android 17 release]` | 模拟内置搜索：显示“已联网搜索”卡片和两个示例来源 |
| 用附件按钮发送一张照片 | 回复图片数量和尺寸，例如 `Images: 1 (1568×1176)` |

</details>

## 从源码构建

需要 JDK 17 和带 platform 36 的 Android SDK，`minSdk` 为 26。

```bash
export JAVA_HOME=/path/to/jdk17

# 第一次构建必须联网：会下载依赖并按 gradle/verification-metadata.xml 校验。
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./scripts/run_ktlint.sh

# 发布形态的打包
./gradlew :app:assembleQa :app:assembleRelease :app:bundleRelease
```

说明：
- 联网跑过一次之后可以加 `--offline`。
- 如果 Gradle 访问不了 Google Maven 但 `curl` 可以，用 `./scripts/seed_android_build_local_maven.sh` 填充项目内的备用仓库。
- `./scripts/run_ktlint.sh` 在 `git ls-files` 不可用时（例如在 Windows git 创建的 worktree 中使用 WSL git）会改用文件通配；发现问题或没有匹配到文件时返回非零退出码。

最近一次完整运行（v0.3.0）：单元测试 621 个中 614 个通过、7 个跳过（未提供密钥的真实服务测试）、0 个失败；仪器测试在 `AndroidClawApi34` 模拟器上 17/17 通过；lint 0 错误；ktlint 0 问题；`qa`、`release` APK 和 bundle 均构建成功。QA 版 APK 为 3,244,963 字节（约 3.2 MB）。

<details>
<summary>真实服务测试与设备测试</summary>

**真实服务测试（可选）**：`OpenAiLiveTest`（对话、流式、工具往返、图片理解、内置联网搜索）和 `ImageGenerateLiveTest` 会调用真实的 OpenAI API，只有设置了环境变量 `ANDROIDCLAW_LIVE_OPENAI_KEY` 才会运行；`ANDROIDCLAW_LIVE_OPENAI_MODEL` 指定聊天模型（默认 `gpt-6-luna`），`ANDROIDCLAW_LIVE_IMAGE_MODEL` 指定图片模型。每次运行都会产生真实调用费用。运行时从文件读取密钥到环境变量，不要把密钥提交到仓库。

```bash
ANDROIDCLAW_LIVE_OPENAI_KEY="$(cat /path/to/key.txt)" \
  ./gradlew :app:testDebugUnitTest \
  --tests 'ai.androidclaw.runtime.providers.OpenAiLiveTest' \
  --tests 'ai.androidclaw.runtime.media.ImageGenerateLiveTest'
```

**真机/模拟器测试**：在 WSL 中驱动 Windows 上的 AVD。

```bash
ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 \
  ./scripts/run_windows_android_test.sh \
  --avd AndroidClawApi34 \
  --test-class ai.androidclaw.app.MainActivitySmokeTest,ai.androidclaw.runtime.scheduler.TaskExecutionWorkerSmokeTest,ai.androidclaw.app.OnboardingProviderSetupSmokeTest,ai.androidclaw.feature.tasks.TasksScreenSmokeTest

ANDROIDCLAW_JAVA_HOME=/path/to/jdk17 \
  ./scripts/run_exact_alarm_regression.sh \
  --api34-avd AndroidClawApi34 \
  --api31-avd AndroidClawApi31
```

</details>

## 工作原理

四项运行时约定，对齐的是 OpenClaw 的行为而不是源码：

| 约定 | 在安卓上的实现 |
|---|---|
| 会话 | Room 中的主会话和普通会话，持久历史，`/compact` 摘要 |
| 工具 | 带 JSON schema 的类型化工具，通过各服务商原生的工具调用发送 |
| 技能 | `SKILL.md` 解析、frontmatter、启用/停用、导入、斜杠命令分发 |
| 自动化 | 基于 WorkManager 和精确闹钟的一次性、间隔和 cron 任务，在主会话或独立会话中运行 |

一个 Gradle 应用模块，在 `AppContainer` 中手动组装依赖：

- `app`：Application、Activity、依赖容器、启动维护。
- `ui`：主题、导航（4 个标签页：聊天、任务、技能、设置）、通用组件、语言切换。
- `feature`：聊天（对话记录、附件、来源、语音）、任务、技能、设置、诊断、首次引导。
- `data`：Room（schema v6，含 `message_attachments` 表）、附件存储与图片导入、DataStore 设置、加密密钥存储、`WorkspaceStore`。
- `runtime`：智能体循环、提示词组装、模型服务、工具、联网搜索规则、图片与视频后端（含 `VideoJobWorker`）、语音适配、技能、调度器、记忆。

技术栈：Kotlin、Jetpack Compose、Room、WorkManager、协程、kotlinx serialization、OkHttp。不使用依赖注入框架，不引入厂商 SDK，也不引入图片或播放器库：使用平台解码、TextToSpeech、系统语音识别器和系统视频播放器。

## 不做的事

不做浏览器自动化、外部聊天渠道（Telegram、WhatsApp、Slack 等）、X/Twitter 集成、远程桥接、shell 执行、相机拍摄、免手动或连续语音对话、语音唤醒和云同步。

## 开发说明

- 给编程智能体的项目规则写在本地的 `AGENTS.md` 中（不纳入版本库）：硬性约束、架构取向和验证命令。
- 仓库根目录下编号最大的 `PLANv*.md` 是当前计划，较早的计划保留为历史。[`CHANGELOG.md`](CHANGELOG.md) 记录每个版本的改动。
- `site/` 是 [androidclaw.top](https://androidclaw.top/zh/) 网站，每次推送到 `main` 后由 GitHub Pages 部署。README 的截图和横幅图在 `site/assets/screens/` 与 `site/assets/readme/` 中。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
