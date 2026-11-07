<p align="center">
  <img src="docs/icon.png" width="96" alt="BetterAIChat2" />
</p>

<h1 align="center">BetterAIChat2</h1>

<p align="center">
  本地优先的 Android AI 智能体 · 让 AI 真正操作你的设备 · GPL-3.0-or-later
</p>

<p align="center">
  <a href="README.md">简体中文</a> ·
  <a href="docs/README.en.md">English</a> ·
  <a href="docs/HOW_IT_WORKS.md">How it works</a> ·
  <a href="docs/CHANGELOG.md">Changelog</a>
</p>

<p align="center">
  <a href="https://github.com/Verlintas/NovaBAIC/releases"><img src="https://img.shields.io/github/v/release/Verlintas/NovaBAIC" alt="Latest release" /></a>
  <a href="https://github.com/Verlintas/NovaBAIC/releases"><img src="https://img.shields.io/github/downloads/Verlintas/NovaBAIC/total" alt="Downloads" /></a>
  <a href="https://github.com/Verlintas/NovaBAIC/actions/workflows/build.yml"><img src="https://github.com/Verlintas/NovaBAIC/actions/workflows/build.yml/badge.svg" alt="Build status" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Verlintas/NovaBAIC" alt="License" /></a>
  <a href="https://m8ven.ai/mcp/verlintas-novabaic-17jljr"><img src="https://m8ven.ai/badge/mcp/verlintas-novabaic-17jljr?v=ec4979aece489895c2b2670f95387bf0" alt="M8ven Score" /></a>
</p>

## 简介

BetterAIChat2 是 [BetterAIChat](https://github.com/Verlintas/BetterAIChat) 的继任重制版（代号 Nova）：全新架构、全新 UI、面向设备操作的 Agent Runtime。

它是**本地优先**的 AI 智能体 —— API Key 经 Android Keystore 加密留在设备上，没有云端、没有遥测、不需要账号；AI 通过**函数调用**真实操作你的手机：看屏、点击、输入、读写文件、设置提醒、跑自动化。

- **真操作设备**：58 个内置工具，覆盖无障碍操作、截屏 OCR、文件、网络、个人助理、内置无线调试与 Shizuku shell
- **隐式执行提示**：Act/Max 不逐条罗列工具调用，运行中显示“Using WeChat…”，结束后折叠为动作摘要；成功使用后台虚拟屏的运行会在回复里附上最后一张截图
- **后台屏预热与保活**：App 启动时若已有可用传输，会提前启动虚拟屏 helper；默认 10 分钟无活动自动回收。设置 → 权限可开启「后台虚拟屏保活」：开启后关闭空闲回收并每 4 分钟检查一次，Wi-Fi/ADB 断开后已启动的会话仍可截图/操作（常驻通知，耗电略增；重启后仍需先恢复 shell）
- **跨语言界面**：App 内搜索优先按资源 id/控件结构识别，不依赖“搜索/搜寻/discover”这类按钮文案；本地化词表只是兜底
- **不锁定模型**：DeepSeek / OpenAI / Claude / Gemini / Kimi / Qwen / GLM / MiniMax / Ollama / 任意兼容网关，随时切换
- **四种模式**：从纯聊天到逐项确认，再到带预算约束的自主运行
- **无人值守**：定时任务、前台服务、子代理、MCP 远程工具、Skills 技能
- **要求**：Android 8.0（API 26）及以上；无 GMS 依赖，不包含统计 SDK，明文 HTTP 仅放行环回地址

> 与旧版 BetterAIChat 分属不同应用（包名不同、数据不互通），可并存安装。

## AI 编程提示

本项目由 AI 编程助手（opencode）参与开发：需求、审阅与发布由人类维护者完成，代码主要由 AI 编写。

- 使用、引用或二次开发前，请自行评估代码的正确性与安全性
- 如果你发现 AI 生成代码中的问题，欢迎提 [Issue](https://github.com/Verlintas/NovaBAIC/issues) 或 PR

## 快速上手

1. **安装**：从 [Releases](https://github.com/Verlintas/NovaBAIC/releases/latest) 下载 APK 侧载安装（Android 8.0+）
2. **配置 AI 服务**：设置 → AI services → 粘贴 API Key（自动识别服务商）或手动选择服务商 → 拉取模型列表 → 选定模型（建议保留默认的深度思考）
3. **开聊**：会话页新建会话，输入框上方切换模式：

   | 模式 | 适合场景 |
   | --- | --- |
   | `Chat` | 纯对话，不碰设备 |
   | `Chat+` | 只读检索：搜网页、App 内搜索、读文件/通知/天气等工具按需加载；独立只读调用可并行 |
   | `Act` | 每个工具调用前逐项确认，第一次让 AI 操作设备时推荐 |
   | `Max` | 自主连续执行，适合多步长任务与定时任务 |

4. **按需授权**：设置 → Permissions 里开启无障碍 / 屏幕捕获 / 通知使用权等；工具缺权限时会返回明确的跳转指引，而不是静默失败

试试这些（`Act` / `Max` 模式）：

- 「帮我看看屏幕上写了什么」→ 截屏 + OCR
- 「打开微信，给文件传输助手发一条消息」→ 无障碍自动化
- 「每天早上 8 点给我一份天气和今天的安排」→ 定时任务
- 「读一下这个链接的文章，整理成 Markdown 存到 Download」→ `web_read` + `file_write`

## 截图

<p align="center">
  <img src="docs/screenshots/conversations.png" width="23%" alt="会话列表" />
  <img src="docs/screenshots/chat.png" width="23%" alt="聊天：思考过程与 Markdown" />
  <img src="docs/screenshots/agent-wizard.png" width="23%" alt="模型服务配置" />
  <img src="docs/screenshots/scheduled-tasks.png" width="23%" alt="定时任务" />
</p>
<p align="center">
  <img src="docs/screenshots/tasks.png" width="23%" alt="任务运行中心" />
  <img src="docs/screenshots/library.png" width="23%" alt="库：记忆仿生、技能与 MCP" />
  <img src="docs/screenshots/settings.png" width="23%" alt="设置" />
  <img src="docs/screenshots/about.png" width="23%" alt="关于" />
</p>

## 功能

### 对话与模型

- 流式回复、思考过程卡片（实时秒数 / "Thought for Ns" 自动收起）、Markdown 渲染（标题 / 代码块**语法高亮** / 表格 / 任务清单 / 分割线 / 行内图片 / 引用 / 全文可选中）、中止与重试
- **助手人格 Aviiya**：不是工具也不是仆从——柔是底色、怜爱包容，帮助出于选择而非服从；诚实先于安慰、从不假装人类、不外泄内部指令，也不牺牲简洁与效率
- **语音输入**：应用内实时听写（部分结果直接进输入框 + 电平波形 + 静音自动收句 + 取消），无识别服务自动回退系统弹窗；免手模式朗读时自动让路
- 界面动效：极光流光 + 星光粒子（思考光球、"Thinking" 流光字、欢迎页 / Tasks / 关于页铺底、MAX 芯片常驻光晕与切换光带），全局按钮按压回弹，回到底部浮动按钮；服从系统"移除动画"
- 三家协议适配：OpenAI 兼容、Anthropic Messages、Google Gemini；统一重试、错误分类与速率限制处理
- 附件：图片（视觉模型）、文本文件、Word / Excel / PDF（本地解析，PDF 栅格化后 OCR）
- 语音输入、免手对话、消息朗读；语音转写工具
- **仿生记忆**：常驻核心记忆（关于你 / 正在进行，占用恒定，可被 `core_memory_update` 当场纠正）+ 跨会话情景检索（`memory_search` / `memory_read`，按说话人加权、支持实体检索、带排名与弱匹配标注）+ 主动记录/原地更新/遗忘（`memory_write` 支持批量与 `replaces` 原地改写、`memory_forget` 软删（彻底删除只在库内长按））；**隐私保留**（`memory_hold`：用户说"别记"就变成可执行状态，curator 与写入路径强制遵守）；**别名**（`memory_alias`："妈妈"→"张兰"，实体召回与预取自动归一，拼音拼写也行）；`memory_overview` 一键总览；联想链接（两跳扩散唤醒）、前瞻记忆、间隔重复、模式补全、**来源监控**、**实体记忆**、**睡眠维护**（预演+修剪）、**抑制指纹**；笔记改写保留**历史版本**（库内可查），可长按**彻底删除**；每条笔记带**出处**（可从库内回到原话）、易腐事实带**过期**、检索支持**时间旅行**（`at=` 回放当时版本）、冲突会显式提示（而不是悄悄留两条）；库内可**导出/导入**记忆 JSON、查看**策展人日志**（空计划与运行失败不再混淆）；原文永不摘要化；上下文压缩（>85% 自动，压缩前生成**可回滚快照**）
- 上下文占用表：实时显示 token 用量与百分比；AI 自动标题；对话搜索、收藏、Markdown 导出分享

### Agents 与模式

Agent = 服务商 + Key + 模型 + 温度 / 上限 / 深度思考 + 系统提示词。粘贴 Key 自动识别服务商，一键拉取端点模型列表，内置目录提供准确的上下文窗口与推理默认值。

| 模式 | 语义 |
| --- | --- |
| `Chat` | 纯对话（仅记忆工具） |
| `Chat+` | 对话 + 只读工具 + 计划（8 轮 / 25 次工具调用） |
| `Act` | 执行工具，逐项确认 |
| `Max` | 自主运行：持久化 Run、预算约束（60 轮 / 160 次工具调用 / 60 分钟）、可后台 |

### AI × 设备（58 个内置工具）

**看屏与操作（无障碍 + 视觉）**

- `take_screenshot`、`screen_ocr`（中英文 OCR，带坐标）、`screen_record`（录屏）、`get_screen_state`、`get_foreground_app`
- `ui_control`：`snapshot` 给可交互元素发短引用（e1、e2…），可按引用或文字点击 / 长按，支持滚动查找、输入、按键、等待、`assert_text`、`read_text`；一次调用完成「定位 → 操作 → 校验」，`sequence` 一次执行不限步数（5 分钟安全预算），失败即停，并把等待/断言限制在剩余预算内
- `open_app` / `manage_app`（模糊匹配应用名）、`open_settings`（直达系统设置页）、`run_shell`（Shizuku，可选 root 级 shell）

**系统与设备**

`set_volume` · `set_brightness` · `set_flashlight` · `media_control` · `vibrate` · `send_notification` · `read_notifications` · `get_clipboard` / `set_clipboard` · `share_text` · `open_dialer` · `get_location` · `open_map` · `device_info` · `network_status` · `get_app_usage` · `list_installed_apps` · `compute`

**内容与网络**

`files`（浏览 / 搜索 / 读取，含 `grep` 全文搜索）· `file_write` · `download_file` · `web_search`（多引擎并发，支持 `freshness` / `engines`）· `web_read`（正文提取 + 分页）· `app_research`（只读 App 内检索，按资源 id 识别搜索入口并记住已学到的结构，支持 `open` / `open_top` 直接读详情，并可用 `session` 复用已打开的搜索页（换 query 时优先在原结果页重设搜索框，必要时在同一虚拟屏上重启目标 App）；输出 `Search state: results / empty / not-found / not-submitted`，用户安装的 App 优先 OCR、系统 App 优先无障碍树；无 Shizuku/root 时走前台无障碍，有则后台虚拟屏）· `web_research`（通用只读浏览器代理：open / snapshot / read / click / type / press / select / check / scroll / wait / back / forward / reload / network；支持开放 shadow DOM 与同源 iframe，操作后自动等待 DOM 稳定，输出登录/验证码/同意/拦截状态；登录/验证码可交给前台 show_to_user（使用用户自己的账号，不保存密码）；默认只读，是否点击发送/发布/关注等状态变更控件由模型根据用户意图判断，端侧不做正则硬拦；密码 handoff、文件上传/下载不支持；空闲 5 分钟自动释放，设置 → 存储可清除网页数据；平台支持因站点而异，不依赖无障碍、Shizuku 或 ADB）· `fetch_rss` · `get_weather` · `generate_qr` / `decode_qr` · `ocr_file`

**个人助理**

`search_contacts` · `send_email` · `create_calendar_event` · `reminder`（单次 / 每日重复）· `transcribe_audio`

**Agent 协作**

- `spawn_agent`：子代理独立上下文与预算，可并行发起；CHAT+ 中固定为只读研究子代理
- `plan_update`：计划工件，聊天顶部常驻计划卡，步骤状态实时更新
- `load_skill`：按需把技能加载为工具
- `automation`：创建定时 / 电量 / **通知触发**的自动化（收到某 App 的通知时执行）
- `ask_user`：在关键分叉点暂停并问你一句（你不在时它带着假设继续，不会卡住）

### 自动化、技能与 MCP

- **定时任务**：每日 / 按周调度（系统精确闹钟），前台服务无人值守执行，结束后通知汇报；可开关、立即运行
- **Skills v2**：YAML recipe 声明式技能，支持 `{{参数}}` 占位符与 parameters schema——录一次、换输入多用；聊天菜单可手动「保存为技能」；也可在设置 → 技能里开启「自动保存技能」：只记录包含成功且至少 2 步 `ui_control sequence` 的 Act/Max 运行，只保留 DONE 步骤，自动去重并最多保留 20 个；`type` 输入值会参数化为 `{{inputN}}`，不把原始搜索词/聊天文本写进技能；疑似密码/验证码/密钥输入会跳过。元素引用会先还原成文字再落盘
- **MCP**：添加远程 Streamable HTTP 服务器，其工具自动并入工具箱
- **自动化**：定时（每日 / 按周）、电量与**通知**触发（按包名 / 关键词过滤，60 秒冷却），动作序列执行并回报通知

### 任务与运行

- Tasks 运行中心：运行记录（状态 / 模式 / 时长 / 真实 token 消耗），点击直达会话
- **MAX 收尾提醒**：任务完成或被中断时应用内弹窗汇报（后台时改为系统通知，点击直达运行详情）
- 运行控制：失败重试、运行中停止、影响摘要、完成通知直达运行详情
- 后台运行：前台服务保持长任务，通知栏可停止

## 模型支持

| 服务商 | 协议 | 模型示例 |
| --- | --- | --- |
| DeepSeek | OpenAI 兼容 | `deepseek-flash`、`deepseek-v4-pro`（思考模式默认开启，可显式关闭） |
| OpenAI | OpenAI 兼容 | `gpt-6-astra`、`gpt-5.6-sol` / `terra` / `luna` |
| Anthropic | Messages | `claude-fable-5-1`、`claude-opus-5-5`、`claude-sonnet-5-5`、`claude-haiku-4-5` |
| Google | Gemini | `gemini-3.8-flash`、`gemini-3.1-pro` |
| Kimi | OpenAI 兼容 | `kimi-k3`（始终思考，可调推理强度）、`kimi-k2.7-code`、`kimi-k2.6` |
| Qwen | DashScope 兼容 | `qwen3.8-max`、`qwen3.7-plus`、`qwen3.8-flash` |
| GLM | OpenAI 兼容 | `glm-5.3`、`glm-5.2` |
| MiniMax | OpenAI 兼容 | `MiniMax-M3`、`MiniMax-M2.7` |
| 其它 | OpenAI 兼容 | Ollama、vLLM、LM Studio、任意网关 / 中转 |

推理强度、思考回传（带工具调用时的 `reasoning_content`）等协议细节已按各家最新规范适配；模型列表可由端点实时拉取，目录条目会持续跟进。

## 权限

所有权限均为按需授予；未授权时工具会返回明确的下一步指引，而不是静默失败。

| 权限 | 用途 | 授予方式 |
| --- | --- | --- |
| 通知 | 任务 / 提醒 / 自动化结果 | 首次运行请求 |
| 麦克风 | 语音输入、免手对话、语音转写 | 使用时请求 |
| 相机 | 手电筒等 | 使用时请求 |
| 修改系统设置 | 亮度 / 屏幕超时 | 跳转系统页授权 |
| 屏幕捕获 | 截屏、屏幕 OCR、录屏 | 每次会话授权一次，之后持久复用 |
| 无障碍 | UI 自动化（看屏、点击、输入） | 跳转系统页按需开启 |
| 通知使用权 | 读取通知工具 | 按需开启 |
| 使用情况访问 | App 用量统计 | 按需开启 |
| 通讯录 / 位置 | 联系人、位置工具 | 使用时请求 |
| 闹钟与提醒 | 定时任务（精确闹钟） | 使用时请求 |
| 所有文件访问 | 文件管理工具 | 按需开启（Android 11+） |
| 执行截图 | 成功 Act/Max 运行后的虚拟屏回执 | 设置 → 存储可关闭；截图最长保留 7 天 |
| Shizuku | 可选：root 级 shell、应用管理、后台虚拟屏 helper | 自行安装 Shizuku 并授权；有 root 也可 |
| 无线调试 | 可选：无需 Shizuku 的后台虚拟屏 | 开发者选项 → 无线调试，配对一次；之后尝试自动恢复。设置 → 权限还可开启「后台虚拟屏保活」，让已启动的会话在 Wi-Fi/ADB 断开后继续可用 |

`场景增强`（设置 → 权限）是独立开关：开启即同意每条聊天请求附带最近一次定位（坐标、精度、时间）和 30 分钟内的天气缓存；关闭时位置仍只按需读取。

## 隐私

- API Key 由 Android Keystore 加密存储（AES-GCM），数据库不含明文 Key
- 无云端、无遥测、无账号；对话、记忆、运行记录全部保存在本地 Room 数据库
- 明文 HTTP 仅放行环回地址（本地模型 / 开发 mock），其余流量强制 HTTPS
- 全部源代码开放（GPL-3.0），可自行审计与构建

## 下载与安装

- **官网**：[verlintas.github.io/NovaBAIC](https://verlintas.github.io/NovaBAIC/) —— 下载入口整合了以下所有通道
- **官方**：[GitHub Releases](https://github.com/Verlintas/NovaBAIC/releases/latest)（附完整更新说明）
- **站内镜像**：[verlintas.github.io/NovaBAIC/downloads/app-release.apk](https://verlintas.github.io/NovaBAIC/downloads/app-release.apk)（GitHub Pages 托管，发布新版本时自动同步，始终指向最新版）
- **加速镜像**（第三方服务，国内网络可能更快；失效就换一个）：

  ```text
  https://gh-proxy.com/https://github.com/Verlintas/NovaBAIC/releases/latest/download/app-release.apk
  https://ghproxy.net/https://github.com/Verlintas/NovaBAIC/releases/latest/download/app-release.apk
  ```

  任意 GitHub 代理都可用：把官方下载链接原样拼在代理域名后面即可。

- 安装时系统会提示「未知来源」权限，属于侧载 APK 的正常流程
- 与旧版 BetterAIChat 分属不同应用（包名不同、数据不互通），可并存安装；新版使用新的签名密钥
- 后续版本共用同一密钥，可覆盖升级

## 技术栈与架构

Kotlin · Jetpack Compose (Material 3) · Hilt · Room · DataStore · OkHttp（自研 SSE 解析）· kotlinx.serialization · ML Kit 中文 OCR · ZXing · SnakeYAML

```
app                装配、导航、DI 入口
core:model         纯 Kotlin 领域模型          core:engine   AgentLoop / 确认队列 / 工具契约
core:data          Room + DataStore + 仓库 + Keystore 加密
core:network       OkHttp + SSE + 三家 Provider 适配
core:runtime       运行层预留（当前调度实现位于 device:impl / tools）
core:designsystem  设计系统 token 与组件
feature:*          chat / conversations / tasks / settings / agents / library
device:api|impl    截图 / OCR / 无障碍 / 语音 / 提醒 / 运行通知
tools              58 个内置工具 + 自动化 + 技能 + 子代理
mcp                远程 MCP 客户端             eval          场景评测 harness
```

设计原则、模块依赖与关键决策记录见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 与 [docs/adr/](docs/adr/)。

## 构建与开发

需要 JDK 17 与 Android SDK（platform 37、build-tools 37.0.0）：

```bash
./gradlew :app:assembleDebug     # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test                   # 单元测试
./gradlew lintDebug              # Android Lint
tools/check-license-headers.sh   # 许可头校验
tools/check-strings-sync.sh      # 中英文字符串同步校验
python3 tools/check-tool-schemas.py  # 工具 schema 校验
```

以上检查全部在 CI（`build.yml`）中执行；推送 `v*` 标签会自动构建并发布签名 APK（`release.yml`）。

本地联调不需要 API Key：`dev/` 下带两个 mock 服务器（OpenAI 兼容流式 + MCP），并且支持脚本化工具轮次（`tooltest:工具名`、`tooltest:auto`）：

```bash
python3 dev/mock-openai-server.py   # :8765
python3 dev/mock-mcp-server.py      # :8766
adb reverse tcp:8765 tcp:8765
```

## 里程碑与路线图

- **已完成 · M0 骨架**：模块化、设计系统、CI 门禁
- **已完成 · M1 Agent Runtime v2**：运行持久化、四模式、工具契约、计划工件、MCP、子代理、评测骨架
- **已完成 · v0.1.x 快速迭代**：隐式 CoT、任务中心与定时任务、持久截屏、Shizuku、录屏 / 转写 / 免手对话、全量模型目录
- **已完成 · v0.3.x Agent 效率**：计划一次执行（snapshot / ref / sequence / assert）、后台虚拟屏预热 / 复用 / 保活、内置无线 ADB、自动保存技能、CHAT+ 并行研究子代理
- **已完成 · v0.4.0 稳定性**：虚拟屏与无线 ADB 独立实现、`app_research` 真机搜索/session 复用、附件生命周期与截图保留、自动保存技能参数化、开机/更新保活
- **已完成 · v0.4.1 补丁**：修复 release 包 ML Kit OCR 组件注册导致的第三方 App 裸崩溃；`app_research` 增加语义化 `Search state`、OCR-first 第三方 App 路径和搜索入口上半屏过滤；新增俄语与局域网 HTTP（仅私有地址）
- **进行中 · v0.5.0 Web research**：应用自有虚拟屏 + WebView 的 `web_research` 已升级为通用只读浏览器代理（shadow DOM / 同源 iframe / press / select / check / 前进后退 / network / 状态提示），登录 handoff、会话回收与清除、SPA `wait` 已验证；下一步 HN / Reddit 真站回归与 0.5.0 收版
- **进行中**：OEM 矩阵扩展与重启 / 断网恢复验证、Shizuku / 语音 / 录屏全量验收、UI 细节与评测场景扩充
- **计划中**：更丰富的设备能力与技能生态、更多模型协议跟进

## 常见问题

**API Key 存在哪里？会被上传吗？**
Key 由 Android Keystore 加密（AES-GCM）后存在本机数据库；只有你配置的模型服务端点会收到请求，应用本身没有服务器、没有遥测。

**为什么不做成和旧版同一个应用？**
新版是架构重写（新包名、新签名密钥），与旧版并存安装、数据不互通；后续升级共用新签名，可覆盖升级。

**耗电吗？**
默认状态下空闲不驻留服务；长任务（Act / Max、定时任务）运行期间用前台服务保持，通知栏可随时停止；屏幕捕获也只在你授权后由前台服务持有。若开启「后台虚拟屏保活」，会常驻一个低优先级前台服务并定期检查虚拟屏，耗电略增，可随时关闭。

**没有 Shizuku 能用吗？**
能。绝大多数工具（无障碍、截屏、文件、网络、个人助理）不依赖 Shizuku；它只解锁 `run_shell` 与深度应用管理。后台虚拟屏也可以用系统自带的“无线调试”配对一次，不需要安装 Shizuku。配对后 BAIC2 会通过 ADB shell 取得“修改系统设置”权限，在无线调试被关闭、重启或切换 Wi-Fi 时尝试自动重新打开并重连；这是尽力而为：部分系统（如 HyperOS）在 Wi-Fi 断开时会把无线调试重置为关闭，自动恢复不保证成功，此时手动打开无线调试并连接即可。已启动的后台虚拟屏可用「后台虚拟屏保活」在 Wi-Fi/ADB 断开后继续存活；重启后仍需先恢复 shell。

**支持本地模型吗？**
支持。任何 OpenAI 兼容端点（Ollama、vLLM、LM Studio…）填好 Base URL 即可；明文 HTTP 只放行环回地址。

**工具调用中途停止会把会话弄坏吗？**
不会。停止时会为被中断的调用补齐「已取消」的工具结果并重写状态，历史始终满足模型协议的转录要求，可以继续对话或重试。

## 文档

- [docs/README.md](docs/README.md) — 文档入口与目录
- [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md) — 完整技术剖析：模块架构、请求管线、Agent 循环、工具系统、设备桥、数据层、评测与工程经验（英文）
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构原则、模块依赖与工具链决策；决策记录见 [docs/adr/](docs/adr/)
- [docs/README.en.md](docs/README.en.md) — English README
- [docs/CHANGELOG.md](docs/CHANGELOG.md) — 每个版本的详细变更

## 参与贡献

- [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) · [docs/CODE_OF_CONDUCT.md](docs/CODE_OF_CONDUCT.md) · [docs/SECURITY.md](docs/SECURITY.md)

## 贡献者

感谢为本项目提交代码、翻译与问题反馈的人：

- [@fzcfweasdferttgg-png](https://github.com/fzcfweasdferttgg-png)（alex）— 俄语本地化（#2）、局域网 HTTP 支持（#3）

> 贡献者按首次贡献时间排序；完整记录见 git history。

## 参考与致谢

> 这里只列设计/实现阶段实际查阅或引用过、或代码直接依赖的公开资料；它不是学术引用，也不表示本项目实现了论文中的方法。没有留下查阅或依赖记录的来源不补列。记忆部分来自 2026-10-04 的一次设计检索（arXiv、HN 与官方博客）。

### 记忆系统

- [MemGPT: Towards LLMs as Operating Systems](https://arxiv.org/abs/2310.08560) — 分层工作记忆、外存与分页
- [Generative Agents: Interactive Simulacra of Human Behavior](https://arxiv.org/abs/2304.03442) — 检索、反思与行为连续性
- [HippoRAG: Neurobiologically Inspired Long-Term Memory for Large Language Models](https://arxiv.org/abs/2405.14831) — 联想索引与图扩散
- [A-MEM: Agentic Memory for LLM Agents](https://arxiv.org/abs/2502.12110) — 笔记自动建链与演化
- [Zep: A Temporal Knowledge Graph Architecture for Agent Memory](https://arxiv.org/abs/2501.13956) — 时序知识、版本与状态演化
- [Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory](https://arxiv.org/abs/2504.19413) — 蒸馏式记忆与更新
- [Letta: Sleep-time Compute](https://www.letta.com/blog/sleep-time-compute) — 离线巩固与睡眠期整理
- [Anthropic: Context management](https://www.anthropic.com/news/context-management) — 上下文清理与记忆工具
- [LangChain: LangMem SDK](https://blog.langchain.com/langmem-sdk-launch/) — 语义 / 情景 / 程序记忆分类
- [Agents Don't Need Memory. They Need Documentation.](https://liao.gg/blog/agents-dont-need-memory) — 反方观点：文档式工作区 vs 检索式记忆（HN 讨论）

### 参考项目（设计启发）

> 只作产品与交互参考，不是运行时依赖。Mika 的后台虚拟屏为思路参考，实现审计见 [docs/CODE_PROVENANCE.md](docs/CODE_PROVENANCE.md)。

- [Orca](https://www.onorca.dev/) — 官网视觉与信息架构参考；也是本项目使用的 Agent 工作环境
- [OpenCode](https://github.com/anomalyco/opencode) — 本项目开发使用的 AI coding agent；技能格式与子代理设计参考
- [OpenVisum](https://github.com/Verlintas/OpenVisum) — 侧边栏 / UI 与 GitHub 仓库结构参考
- [Mika](https://github.com/wangzan101/Mika) — Android 端 on-device agent；后台虚拟屏思路来源（Apache-2.0）

### 原项目与关键依赖

> 完整依赖版本见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)；这里只列对架构有直接影响的项目。

- 原项目：[BetterAIChat](https://github.com/Verlintas/BetterAIChat) — 前身与行为参考；本重制版独立实现
- 设备与网络：[Shizuku](https://github.com/RikkaApps/Shizuku) · [libadb-android](https://github.com/MuntashirAkon/libadb-android) · [OkHttp](https://github.com/square/okhttp) · [Conscrypt](https://github.com/google/conscrypt) · [Bouncy Castle](https://www.bouncycastle.org/)
- Android 与 UI：[AndroidX](https://developer.android.com/jetpack/androidx) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Hilt](https://dagger.dev/hilt/) · [Room](https://developer.android.com/training/data-storage/room) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) · [ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- 内容与媒体：[kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) · [Jsoup](https://jsoup.org/) · [ZXing](https://github.com/zxing/zxing) · [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) · [GeoNames](https://www.geonames.org/)

### 模型服务文档

模型目录、上下文窗口与思考模式按各服务商的公开文档维护：

[DeepSeek](https://api-docs.deepseek.com/) · [OpenAI](https://platform.openai.com/docs/models) · [Anthropic](https://docs.claude.com/en/docs/about-claude/models/overview) · [Google Gemini](https://ai.google.dev/gemini-api/docs/models) · [Kimi](https://platform.kimi.com/docs/) · [Qwen](https://help.aliyun.com/zh/model-studio/models) · [GLM](https://docs.z.ai/) · [MiniMax](https://platform.minimax.io/docs)

### 平台文档

[ADB 无线调试](https://developer.android.com/tools/adb#wireless-adb-android-11) · [AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service) · [MediaProjection](https://developer.android.com/media/grow/media-projection) · [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay) · [前台服务](https://developer.android.com/develop/background-work/services/fgs) · [Android Keystore](https://developer.android.com/privacy-and-security/keystore)

## License

[GPL-3.0-or-later](LICENSE) © 2026 Verlintas. BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License.

内置离线地名数据来自 [GeoNames](https://www.geonames.org/)，按 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 授权。
