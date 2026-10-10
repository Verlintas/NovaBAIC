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
  <a href="CHANGELOG.md">Changelog</a>
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

- **真操作设备**：56 个内置工具，覆盖无障碍操作、截屏 OCR、文件、网络、个人助理与 Shizuku shell
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
   | `Chat+` | 只读检索：查通知、读文件、搜网页、问天气 |
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
- **仿生记忆**：常驻核心记忆（关于你 / 正在进行，占用恒定，可被 `core_memory_update` 当场纠正）+ 跨会话情景检索（`memory_search` / `memory_read`，按说话人加权、支持实体检索、带排名与弱匹配标注）+ 主动记录/原地更新/遗忘（`memory_write` 支持批量与 `replaces` 原地改写、`memory_forget` 软删/硬删）；**隐私保留**（`memory_hold`：用户说"别记"就变成可执行状态，curator 与写入路径强制遵守）；`memory_overview` 一键总览；联想链接（两跳扩散唤醒）、前瞻记忆、间隔重复、模式补全、**来源监控**、**实体记忆**、**睡眠维护**（预演+修剪）、**抑制指纹**；笔记改写保留**历史版本**（库内可查），可长按**彻底删除**；每条笔记带**出处**（可从库内回到原话）、易腐事实带**过期**、检索支持**时间旅行**（`at=` 回放当时版本）、冲突会显式提示（而不是悄悄留两条）；库内可**导出/导入**记忆 JSON、查看**策展人日志**（空计划与运行失败不再混淆）；原文永不摘要化；上下文压缩（>85% 自动，压缩前生成**可回滚快照**）
- 上下文占用表：实时显示 token 用量与百分比；AI 自动标题；对话搜索、收藏、Markdown 导出分享

### Agents 与模式

Agent = 服务商 + Key + 模型 + 温度 / 上限 / 深度思考 + 系统提示词。粘贴 Key 自动识别服务商，一键拉取端点模型列表，内置目录提供准确的上下文窗口与推理默认值。

| 模式 | 语义 |
| --- | --- |
| `Chat` | 纯对话（仅记忆工具） |
| `Chat+` | 对话 + 只读工具 + 计划（8 轮 / 25 次工具调用） |
| `Act` | 执行工具，逐项确认 |
| `Max` | 自主运行：持久化 Run、预算约束（60 轮 / 160 次工具调用 / 60 分钟）、可后台 |

### AI × 设备（56 个内置工具）

**看屏与操作（无障碍 + 视觉）**

- `take_screenshot`、`screen_ocr`（中英文 OCR，带坐标）、`screen_record`（录屏）、`get_screen_state`、`get_foreground_app`
- `ui_control`：按文字 / 描述模糊查找，点击 / 长按 / 滚动查找 / 输入 / 按键 / 等待出现；一次调用完成「定位 → 操作 → 校验」，操作前后的界面差异作为验证信号
- `open_app` / `manage_app`（模糊匹配应用名）、`open_settings`（直达系统设置页）、`run_shell`（Shizuku，可选 root 级 shell）

**系统与设备**

`set_volume` · `set_brightness` · `set_flashlight` · `media_control` · `vibrate` · `send_notification` · `read_notifications` · `get_clipboard` / `set_clipboard` · `share_text` · `open_dialer` · `get_location` · `open_map` · `device_info` · `network_status` · `get_app_usage` · `list_installed_apps` · `get_time` · `compute`

**内容与网络**

`files`（浏览 / 搜索 / 读取，含 `grep` 全文搜索）· `file_write` · `download_file` · `web_search`（多引擎并发，支持 `freshness` / `engines`）· `web_read`（正文提取 + 分页）· `fetch_rss` · `get_weather` · `generate_qr` / `decode_qr` · `ocr_file`

**个人助理**

`search_contacts` · `send_email` · `create_calendar_event` · `reminder`（单次 / 每日重复）· `transcribe_audio`

**Agent 协作**

- `spawn_agent`：子代理独立上下文与预算，可并行发起
- `plan_update`：计划工件，聊天顶部常驻计划卡，步骤状态实时更新
- `load_skill`：按需把技能加载为工具
- `automation`：创建定时 / 电量触发的自动化

### 自动化、技能与 MCP

- **定时任务**：每日 / 按周调度（系统精确闹钟），前台服务无人值守执行，结束后通知汇报；可开关、立即运行
- **Skills v2**：YAML recipe 声明式技能，导入后按需加载为工具；也可以把一次完成的操作序列「保存为技能」
- **MCP**：添加远程 Streamable HTTP 服务器，其工具自动并入工具箱
- **自动化**：定时（每日 / 按周）与电量触发，动作序列执行并回报通知

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
| Shizuku | 可选：root 级 shell、应用管理 | 自行安装 Shizuku 并授权 |

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
tools              56 个内置工具 + 自动化 + 技能 + 子代理
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
- **进行中**：真机能力全量验收（Shizuku / 语音 / 录屏）、UI 细节持续打磨、评测场景扩充
- **计划中**：更丰富的设备能力与技能生态、更多模型协议跟进

## 常见问题

**API Key 存在哪里？会被上传吗？**
Key 由 Android Keystore 加密（AES-GCM）后存在本机数据库；只有你配置的模型服务端点会收到请求，应用本身没有服务器、没有遥测。

**为什么不做成和旧版同一个应用？**
新版是架构重写（新包名、新签名密钥），与旧版并存安装、数据不互通；后续升级共用新签名，可覆盖升级。

**耗电吗？**
空闲时不驻留任何服务；长任务（Act / Max、定时任务）运行期间用前台服务保持，通知栏可随时停止；屏幕捕获也只在授权后由前台服务持有。

**没有 Shizuku 能用吗？**
能。绝大多数工具（无障碍、截屏、文件、网络、个人助理）不依赖 Shizuku；它只解锁 `run_shell` 与深度应用管理。

**支持本地模型吗？**
支持。任何 OpenAI 兼容端点（Ollama、vLLM、LM Studio…）填好 Base URL 即可；明文 HTTP 只放行环回地址。

**工具调用中途停止会把会话弄坏吗？**
不会。停止时会为被中断的调用补齐「已取消」的工具结果并重写状态，历史始终满足模型协议的转录要求，可以继续对话或重试。

## 文档

- [docs/HOW_IT_WORKS.md](docs/HOW_IT_WORKS.md) — 完整技术剖析：模块架构、请求管线、Agent 循环、工具系统、设备桥、数据层、评测与工程经验（英文）
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构原则、模块依赖与工具链决策；决策记录见 [docs/adr/](docs/adr/)
- [docs/README.en.md](docs/README.en.md) — English README
- [CHANGELOG.md](CHANGELOG.md) — 每个版本的详细变更

## 参与贡献

- [CONTRIBUTING.md](CONTRIBUTING.md) · [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) · [SECURITY.md](SECURITY.md)
- [CHANGELOG.md](CHANGELOG.md)：每个版本的详细变更

## License

[GPL-3.0-or-later](LICENSE) © 2026 Verlintas. BetterAIChat2 is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License.

内置离线地名数据来自 [GeoNames](https://www.geonames.org/)，按 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 授权。
