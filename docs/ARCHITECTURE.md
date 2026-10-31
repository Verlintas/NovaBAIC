# BAIC2 架构设计

> 本文随代码演进，记录当前架构与决策；模块正在按里程碑持续充实。

## 1. 设计原则

1. **本地优先**：无云端、无埋点；API Key 用 Android Keystore 加密，数据全部留在设备。
2. **模型不可信**：模型输出只是提案。权限门、危险等级、策略在应用侧强制检查，不依赖提示词。
3. **失败必须可行动**：工具错误是写给模型看的 —— 带下一步的失败结束重试循环，裸失败制造重试循环。
4. **上下文是稀缺资源**：工具结果有预算、按阶段裁剪 schema、步骤自动摘要；token 经济性是一等设计约束。
5. **默认拒绝**：Chat 不暴露工具；只读白名单靠注册表强制；缺权限给出明确指引。
6. **React 不轮询**：状态用 Flow 驱动，唯一允许的轮询要有节流理由并写进文档。
7. **单一事实来源**：Agents 管模型配置，DataStore 管设备偏好，Room 管会话与运行数据。

## 2. 模块与依赖方向

```
:app ──► :feature:* ──► :core:designsystem
  │           │
  │           └──► :core:model
  │
  ├──► :device:impl ──► :device:api
  ├──► :tools ──► :core:engine / :device:api
  ├──► :mcp / :eval
  └──► :core:data / :core:network / :core:engine / :core:runtime
```

- `:core:model`、`:core:engine` 是纯 Kotlin/JVM 模块 —— 强制的纯净边界，测试不需要 Android stub。
- `:core:data` 是唯一的数据入口（Room + DataStore + Keystore 加密）；UI 不得直连 DAO。
- `:device:api` 定义能力接口（截图/OCR/无障碍/Shizuku/无线调试/通知/语音），`:device:impl` 提供 Android 实现。
- `:tools` 放内置工具实现；`:mcp` 把远程 MCP 工具适配进同一工具注册表。
- `:core:runtime` 目前是预留模块（无源码）：交互运行的调度在 `device:impl`（RunService），定时任务的调度在 `tools/scheduling`；等出现第三个引擎宿主时再把公共运行层迁入。

## 3. 工具链决策

| 决策 | 理由 |
| --- | --- |
| AGP 9.4.1 + Gradle 9.6.1 | Hilt 2.60 要求 AGP ≥ 9；本机已有验证过的组合；模块使用 AGP 9 内置 Kotlin（不再单独应用 kotlin-android） |
| compileSdk 37 / targetSdk 36 | Compose BOM 2026.09 要求最低 compileSdk 37；targetSdk 停留 36 保持运行时行为稳定 |
| material3 1.4.0（稳定） | M3 Expressive 的 `MaterialExpressiveTheme`/`MotionScheme` 在 1.4.0 仍为 internal，因此设计系统采用公开 `MaterialTheme` + 自研 `Baic2Motion`；待 1.5 稳定再迁 |
| Hilt（KSP） | 编译期检查、作用域清晰；替代旧项目的服务定位器 AppContainer |
| 无 flavor 单一 APK | OCR 直接内置，发布流程简单 |

## 4. 设计系统（Codex 式控制台）

- **暗色优先**：`#0B0B0D` 近黑背景、三级表面（surfaceContainer*）、低对比 outline、单一冷蓝强调色（`#7AA2F7`）。
- **信息密度**：等宽字体用于工具输出/日志/id/时间戳（`Baic2Mono`），小圆角（4-20dp），不做胶囊。
- **动效**：`Baic2Motion` 提供 spatial/effects 弹簧（对齐 M3 Expressive 规格），功能性动画 150-250ms。
- **token 先行**：颜色/字体/形状/间距/动效全部走 token，组件库带 Preview 与截图测试（M1 起）。
- **导航交互**：左上角悬浮 Dock 按钮 → 点击向下展开导航面板（弹簧展开、逐项错峰、scrim 点击关闭、返回键关闭、触感反馈）；无底栏，内容区全屏。

## 5. Agent Runtime v2（M1/M3 落地）

- **持久化运行**：`Run → Step → ToolCall → Observation → Artifact` 落 Room + 事件日志；进程死亡可恢复，前台服务支持后台长任务。
- **显式阶段**：TASK → PLAN → ACT → OBSERVE → VERIFY →（REPLAN / DONE）；计划是一等工件（`plan_update` 工具维护）。
- **感知**：无障碍树优先（role/可交互/状态），OCR 与截图视觉兜底；操作前后 UI diff 作为验证信号。
- **工具契约**：强类型参数与 `ToolResult`（见 `:core:model`）、进度 Flow、可取消、幂等/危险级/并行安全元数据、**按工具名熔断**（同名工具一次运行内 3 次失败即拒绝后续调用，与 `HOW_IT_WORKS` §7.3 一致）。
- **子代理**：`spawn_agent` 独立上下文与预算，结构化报告，取消树 + 深度/预算继承。
- **上下文工程**：分层上下文（策略/任务/计划/工作记忆/工件），按阶段裁剪工具 schema，per-run/per-step 预算。
- **MCP**：远程 Streamable HTTP 优先，工具并入统一注册表，权限与内置工具一致。
- **评测**：mock 供应商 + 模拟设备 + 场景判分器（成功率/步数/token/耗时），CI 回归门禁。

## 6. 模式体系

| 模式 | 语义 |
| --- | --- |
| Chat | 纯对话，无工具 |
| Chat+ | 对话 + 只读工具 + 计划（原 Plan 并入） |
| Act | 执行工具，逐项确认（原 Build） |
| Max | 自主运行：持久化 Run、预算、可后台 |

## 7. 测试与 CI

- 单元测试：JVM 模块用 kotlin-test；Android 模块用 JUnit。M1 起引入 MockWebServer、Room 迁移测试、Roborazzi 截图测试。
- CI：`test` + `lintDebug` + `:app:assembleDebug`（GitHub Actions，见 `.github/workflows/build.yml`）。

## 8. 里程碑

见 [README](../README.md#里程碑与路线图)。每个阶段都有可验收物；M1 先立垂直切片与评测骨架，避免"最后才发现不行"。
