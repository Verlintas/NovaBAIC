# Agent 效率与隐式交互路线图

> 状态：进行中
> 基线：v0.4.1
> 最后更新：2026-11-02
> 原则：版本、CHANGELOG、官网与 release 说明统一在“发”之前处理。

这份文档是当前 Agent 能力迭代的单一事实来源。上下文被截断时，先读这里，再读代码。

## 0. 目标

1. **前端隐式化**：用户不需要看 Agent 的原始工具调用，只看到“Using WeChat…”这类人类可读状态；Act/Max 完成后可以看一张后台虚拟屏截图作为回执。
2. **效率**：减少模型 token、模型轮次、端到端耗时和失败重试，而不是只压缩提示词。
3. **不依赖外部 App**：后台虚拟屏优先走内置无线调试；Shizuku/root 只是可选加速；纯前台无障碍始终可用。
4. **安全与可审计**：读优先、危险动作确认、配对码/ADB key 不离开设备；截图等 UI 产物不进入模型上下文。

## 1. 当前基线

### 已落地

- `PromptCompiler`：稳定前缀 + 运行时尾部，保护 provider prefix cache；时间不再走 `get_time`。
- `ToolRouter`：CHAT+ 研究核心 + 按关键词按需加载只读工具，schema 有上限。
- 场景增强：权限页开关，默认关闭；只附最近定位与 30 分钟内天气缓存。
- `app_research`：
  - 前台无障碍路径（无需 Shizuku/root）。
  - 后台虚拟屏：Shizuku、root、内置无线调试三种 shell 来源。
  - 跨 display 无障碍树（`getWindowsOnAllDisplays`），OCR 回退。
  - 结果去重、去 UI chrome、query 相关行优先，3000 字/80 行。
  - 搜索入口优先按 resource id（view id 含 `search`）识别，文字标签从资源数组读取；英文/中文界面都不依赖硬编码文案。
  - 每次成功搜索后把搜索控件的结构 view id 写入 App 私有的 `app-knowledge.json`，下次优先使用；只存包名和 view id，不存 query、屏幕文字或截图。
  - `open` / `open_top`：搜索后打开明确命名的结果或第一条 query 命中的结果并读取详情，省掉一次模型+工具往返。工具不做“发送/购买/关注”等文案匹配拒绝；由提示词约束 Agent 只选结果行，工具只按控件结构拒绝 Button/ImageButton/Switch/CheckBox/EditText/带开关的行。登录/验证码/风控页改为提示用户接管，不再硬失败。
  - `session`：虚拟屏返回短 session id；同 query 后续调用直接读当前页；换 query 时优先在当前结果页重设搜索框，结果页不可用时在同一 display 上重启 App（不重建 helper/display）。
- 常驻 helper：
  - `IVirtualDisplayService` AIDL + `VirtualDisplayHandoff` provider 交接。
  - App 持有 ImageReader surface，帧不落盘。
  - helper 在 App 进程死亡后自动退出。
- 无线调试：
  - libadb-android + Conscrypt + Spake2 + BouncyCastle。
  - 本地 RSA key/自签名证书，App 私有目录。
  - `WirelessPairingService` 前台服务用无障碍读取系统配对码并自动配对。
  - 配对后经 ADB shell 执行 `pm grant ... WRITE_SECURE_SETTINGS`，用于自动重开无线调试。
  - 端口发现：缓存端口 → `service.adb.tls.port`（反射）→ 手动端口 → mDNS → 本机端口扫描；启动/网络变化/开机触发重连。
  - 一条命令一个连接 + 设备端 marker，规避 adbd CLSE 延迟。
- Act/Max UI：
  - 运行中隐藏原始 tool card，显示 `Using WeChat…` 等活动提示。
  - 结束后显示折叠动作摘要（`Used WeChat · 2 actions`），可展开技术细节。
  - 成功使用后台虚拟屏的运行，把最后一帧作为 `Background screenshot` 附件挂到最终 assistant 消息。
  - assistant 附件在 `prepareHistory` 中被剥离，不进入模型上下文。
- 57 个工具不变；DB 仍为 v21；全模块测试、lint、debug/release 构建通过。

### 已知未完成

- 真实 Act/Max 端到端已在模拟器 + 真实设备跑通单次（真实模型 → 工具 → 虚拟屏/前台），仍需多模型、多任务回归。
- 截图附件删除消息时不回收，存在孤儿文件。
- 没有截图回执开关、保留期限或 secure-screen 判断。
- 动作摘要只对“最后一条用户消息之后”的调用有效，发下一条消息后消失。
- Chat+ 仍显示研究工具卡（有意保留来源可见性）。
- 官网 demo 仍是旧版 tool card 样式。
- 内置无线调试已在真实 Xiaomi 17 Pro Max 完成配对、后台虚拟屏与 Wi-Fi 关闭后的会话复用；重启恢复、OEM 拒绝写设置仍需更多真机验证。
- 无线端口扫描已实现，但只在 JVM 单测和模拟器上验证；真机上的扫描耗时、OEM 网络限制仍待测。
- 前台输入已增加焦点重试与提交后内容等待；MIUI 无障碍仍可能被 SmartPower 反复解绑，失败会回退前台。
- v0.4.1 候选已构建，待微信/X 真机回归后发布；剩余项见下方发布门禁与后续优化。

## 2. 设计原则

- **UI 产物不是模型输入**：activity hint、摘要、截图附件都不应进入下一轮请求。
- **减少轮次优先于减少字符**：一次高层动作胜过“点击 → 回读 → 输入 → 回读”。
- **读优先**：可读工具尽量并行；写动作保留确认与串行。
- **失败可行动**：超时、无权限、OEM 限制都要给出用户下一步，而不是空洞错误。
- **关闭即降级**：无线调试/无障碍不可用时，退回 Shizuku/root 或前台，不阻塞回答。
- **不把外部 App 变成硬依赖**。
- **可验证**：每个工作流都有明确验收标准；至少 emulator + 一台真机。

## 3. 工作流总表

| ID | 工作流 | 优先级 | 状态 | 预期收益 |
|---|---|---|---|---|
| E1 | Act/Max 隐式交互 + 截图回执 | P0 | 部分完成 | 体验/审计 |
| E2 | helper Binder 化：启动、点击、滑动、按键、输入 | P0 | 已落地，待无线/真机验证 | 最大单项延迟收益 |
| E3 | 跨屏无障碍节点动作 + 事件等待 | P0 | 已落地基础动作/事件等待，前台输入待修 | 去掉坐标/固定 sleep |
| E4 | 持久无线 shell / 批量命令 | P1 | 未开始 | 保留 shell 路径时降低往返 |
| E5 | 屏幕差分与结构化观察 | P1 | 快照/元素引用已落地，通用 diff 未开始 | token 与轮次双降 |
| E6 | App 知识卡 | P1 | 搜索入口基础已落地，结果容器/路径未开始 | 跳过重复探索 |
| E7 | 成功流程自动 Skill 化/复用 | P1 | 手动保存 + 可选自动保存已落地，参数化未开始 | 同类任务近零推理 |
| E8 | 快慢模型分层 / 本地路由 | P2 | 未开始 | 降低强模型调用成本 |
| E9 | 只读并行 + 预取 | P2 | CHAT+ 已落地，ACT/预取未开始 | 减少模型轮次 |
| E10 | 真机/release/截图生命周期/官网收尾 | P0 | 未开始 | 可发布 |

## 4. E1：Act/Max 隐式交互与截图回执

### 已完成

- `ChatViewModel.ActivityHint`：从 `ToolCallStarted` 推导 `USING_APP` / `OPENING_APP` / `MANAGING_APP` / `READING_SCREEN` / `SEARCHING_WEB` / `READING_WEB` / `READING_FILES` / `WORKING`。
- `ChatScreen`：
  - 运行中显示 `ActivityHintLine`，例如 `Using WeChat…`。
  - Act/Max 调用 `MessageRow(compactTools = true)`，隐藏原始 tool cards。
  - 最后一次用户消息之后的所有工具调用汇总成 `ToolSummaryCard`，默认折叠，失败自动展开。
- 截图回执：
  - `ToolCallFinished` 检测 `app_research` 结果是否以 `Mode: virtual display` 开头。
  - 运行成功且存在虚拟屏时，`attachRunScreenshot()` 捕获最后一帧，`AttachmentProcessor.importImageBytes()` 落盘，`ConversationRepository.updateAttachments()` 挂到最终 assistant 消息。
  - `AssistantMessage` 渲染 assistant 图片附件，显示 `Background screenshot`。
  - `prepareHistory` 对 assistant 附件直接丢弃，不产生附件 marker。

### 剩余

1. 真实 Act/Max 端到端验证，确认截图挂载的是最终 assistant 消息，不是中间 tool-call 消息。
2. 增加“保留执行截图”开关（默认开），关闭后不捕获、不落盘。
3. 截图保留策略：按数量/天数清理，或跟随消息删除。
4. 删除消息时回收附件文件（当前所有消息附件都有此问题，不只截图）。
5. secure-screen / 敏感页面检测：如果虚拟屏页面含支付、密码、验证码等，跳过截图或打码。
6. 把动作摘要持久化到消息级（可选），避免发下一条消息后丢失审计信息。
7. 官网 demo 改为隐式提示 + 截图回执样式。

### 验收

- 真实模型在 Act 中执行一次 `app_research`，最终回复带截图，历史里没有 base64/image 输入。
- 关闭截图开关后不产生附件。
- 删除消息后附件目录不增长。

## 5. E2：helper Binder 化（最高优先级）

### 问题

当前无线/Shizuku 路径下，虚拟屏里的每个动作仍走 shell：

- `am start --display`
- `input -d tap`
- `input -d text`
- `input -d keyevent`
- `input -d swipe`

无线 ADB 每条命令需要重新连接/握手；`app_research` 一次通常 4–6 条命令，端到端多出数秒。Shizuku 虽快一些，但仍有 binder/进程开销。

### 方案

扩展 `device/api/src/main/aidl/.../IVirtualDisplayService.aidl`：

```aidl
int startApp(int displayId, String component);
boolean tap(int displayId, int x, int y);
boolean swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs);
boolean keyEvent(int displayId, int keyCode);
boolean typeText(int displayId, String text);
boolean scroll(int displayId, int direction);
```

实现放在 `ShellDisplayService`（helper 内，shell/root 身份）：

- **startApp**
  - 首选隐藏 `IActivityTaskManager.startActivityAsUser` + `ActivityOptions.setLaunchDisplayId`。
  - 次选 `am start --display`（通过 helper 内 `Runtime.exec` 或反射）。
  - 只允许启动到 helper 自己创建的 display id（`displays` map 校验）。
- **tap / swipe**
  - 首选隐藏 `InputManager.injectInputEvent`，给 `MotionEvent` 设置 display id。
  - 失败退回 helper 内 `input -d`。
- **keyEvent**
  - `KeyEvent` 注入；失败退回 `input -d keyevent`。
- **typeText**
  - ASCII 走输入注入/`input text`。
  - CJK 走 helper 剪贴板 + PASTE，使用完恢复。
- **display 校验**
  - 所有方法只接受当前 helper 自己创建的 display id；不接受主屏或其他 display。

`AndroidVirtualDisplayBridge` 改为：

```kotlin
launch(component) -> service.startApp(displayId, component)
tap(x, y) -> service.tap(displayId, x, y)
swipe(...) -> service.swipe(displayId, ...)
keyEvent(code) -> service.keyEvent(displayId, code)
typeText(text) -> service.typeText(displayId, text)
```

shell 只保留：启动 helper、清理旧 helper、无可避免的 fallback。

### 预期

- app_research 虚拟屏路径少 3–5 次 ADB 往返。
- 无线调试延迟从“每动作数百毫秒到 1 秒”降到 Binder 级。
- 输入/启动可靠性提升，`am start` 失败时可回退。

### 验收

- Settings 搜索 `battery` 全链路只启动一次 helper；后续动作无 `input`/`am start` shell 调用（日志计数）。
- 记录优化前后 app_research 端到端耗时（目标至少降低 40%）。
- release R8 后 helper 仍能加载并完成 startApp/tap/type。
- 无线 ADB transport 下复测（目前只在 Shizuku transport 上验证）。
- CJK 输入在虚拟屏可用，剪贴板恢复。

## 6. E3：跨屏无障碍节点动作 + 事件等待

### 问题

当前虚拟屏搜索/点击用坐标；固定 sleep（1.2s/0.7s）在部分设备上仍然不够或浪费。

### 方案

在 `AccessibilityBridge` 增加：

```kotlin
fun clickTextOnDisplay(displayId: Int, query: String): Result<Unit>
fun clickEditableOnDisplay(displayId: Int): Result<Unit>
fun setTextOnDisplay(displayId: Int, text: String): Result<Unit>
fun pressEnterOnDisplay(displayId: Int): Result<Unit>
fun scrollOnDisplay(displayId: Int, forward: Boolean): Result<Unit>
fun awaitContentChange(displayId: Int, timeoutMs: Long): Boolean
```

实现：

- 用 `windowsOnAllDisplays[displayId]` 找目标窗口，遍历节点。
- `ACTION_CLICK` / `ACTION_SET_TEXT` / `ACTION_IME_ENTER` / `ACTION_SCROLL_FORWARD`。
- 等待用 `AccessibilityEvent` 流或轮询树变化，替代固定 sleep。
- 失败回退现有坐标 + E2 Binder 输入。

### 预期

- 搜索框定位不再依赖坐标/OCR。
- 点击后无需固定 700ms；内容变化即继续。
- 部分自定义控件仍不可点，保留 fallback。

### 已落地（2026-10-31）

- `AccessibilityBridge` 新增 `clickTextOnDisplay/clickEditableOnDisplay/setTextOnDisplay/pressEnterOnDisplay/scrollOnDisplay`。
- 搜索优先走节点动作：`ACTION_CLICK` → `ACTION_SET_TEXT` → `ACTION_IME_ENTER`；失败回退坐标 + Binder 输入。
- 多窗口 editable 扫描，解决 Settings 搜索页在独立窗口、`ACTION_SET_TEXT` 找不到目标的问题。
- 模拟器验证：`clicked=true`、`set=Success`、`enter=Success`，Settings 搜索 `battery` 全链路成功。
- 滚动路径已接入 `ACTION_SCROLL_FORWARD`，尚未在 `max_screens=2` 下单独回归。

### 已落地：事件等待（2026-10-31）

- `AccessibilityBridge.awaitContentChange(displayId, timeoutMs, previousText)`：无障碍事件唤醒 + 400ms 周期兜底快照；文本变化立即返回，超时返回 false。
- `Baic2AccessibilityService` 只转发窗口/内容/滚动/文本变化事件，不保存事件内容。
- `app_research` 去掉启动、搜索提交、滚动后的固定 1.2s/0.7s/750ms：改为等待内容变化（启动 1.2s、提交 1.5s、滚动 750/900ms），未变化时按原超时继续。
- 模拟器（Shizuku 虚拟屏）验证：Settings `battery`（2 屏）、`display`（3 屏请求，实际 2 屏到底）、`s`（2 屏滚动）均成功；中文 `电池` 通过 `ACTION_SET_TEXT` 输入成功（该 emulator 语言为英文，返回 No results 属预期）。
- 前台无障碍路径复测出现 `set_text_rejected`（搜索框窗口/焦点波动）；事件等待本身不报错，但前台搜索输入仍需修，暂不标记为已通过。

### 验收

- Settings / 一个第三方 App 的搜索流程连续运行 10 次，成功率不低于当前，平均耗时下降。
- 输入中文不依赖 shell `input`（待验证）。
- 无节点可点时明确回退，而不是卡住。
- 滚动/事件等待在 `max_screens=2` 下验证。

已验证（2026-10-31）：虚拟屏滚动/事件等待在 `max_screens=2` 下通过（Settings `s`）；中文 `电池` 经 `ACTION_SET_TEXT` 输入成功。前台路径复测出现 `set_text_rejected`，待修。
待补：连续 10 次成功率/耗时统计；一个第三方 App 回归。

## 7. E4：持久无线 shell / 批量命令

### 适用场景

E2/E3 完成后，无线 shell 主要用于启动 helper。保留此工作流作为 fallback，以及未来 `run_shell` 的无线传输。

### 方案

- `AdbShellSession`：保持一个 `shell:` 交互流，互斥写入命令 + marker，读到 marker 返回。
- 连接死亡时重建一次；正在执行的命令返回失败。
- 提供 `execBatch(commands)`：一次写入多行，减少往返；仅用于可批处理的非交互命令。

### 验收

- 连续 3 条命令只握手一次。
- 断网/关闭无线调试后 1 次重连失败即回退，不无限重试。
- 不破坏单命令超时与取消。

## 8. E5：屏幕差分与结构化观察

### 问题

`screen_ocr` / `app_research` 每次返回整页文字，模型重复读相同内容，token 和轮次都浪费。

### 方案

- 已落地：`ui_control snapshot` 返回带短引用（e1…）的可交互元素；`tap/long_press ref=…` 在点击前重新校验标签与位置；`sequence` 支持 `assert_text` / `read_text`。
- 新增 `ScreenSnapshot`：节点 id、text、bounds、editable、clickable、scrollable。
- 新增 `ScreenDelta`：新增/消失/文本变化/位置变化的节点。
- `app_research` 返回结构化结果：
  ```text
  App: WeChat
  Query: 周末聚会
  Results:
  - 周末聚会 | 周六 19:00 | 滨江
  ```
- 滚动后只返回差分；模型需要完整页时显式调用 `screen_ocr`。
- 保留每轮 observation token 预算（例如 1500 tokens），超出截断并标注。

### 验收

- 同一页面重复读取时 token 明显下降。
- 模型仍能拿到足够的标题/摘要/按钮。
- 失败时能看到“页面没有变化”而不是重复整页。

## 9. E6：App 知识卡

### 数据

```kotlin
data class AppKnowledge(
    val packageName: String,
    val label: String,
    val searchEntry: NodeHint?,
    val resultContainer: NodeHint?,
    val backPath: List<String> = emptyList(),
    val verifiedAt: Long,
    val source: String, // seeded / learned
)
```

可以存 Room 或 DataStore；先用内存 + DataStore JSON，避免 DB bump。

### 已落地（2026-10-31）

- `app-knowledge.json` 保存 `packageName`、`searchViewId`、`updatedAt`、`successes`。
- 搜索成功后回写实际控件的 view id；下次通过 preferred view id 优先定位，不依赖按钮文案。
- 只保存结构 id，不保存 query、屏幕文字或截图；失效时自动回退到结构探测/资源词表。
- 结果容器、返回路径、过期清理和设置页重置入口仍未做。

### 使用

- `app_research` 先查知识卡，直接点搜索入口，跳过“找搜索框”。
- 成功后回写搜索入口/结果容器。
- 过期（如 30 天）或节点失效时重新探索并更新。
- 只保存 UI 结构，不保存用户查询/结果。

### 验收

- 已知识 App 的搜索启动时间下降。
- 知识卡失效时自动回退，不产生错误。
- 清理/重置入口可用。

## 10. E7：成功流程自动 Skill 化

### 现状

- 聊天菜单保留手动「保存为技能」。
- 设置 → 技能新增「自动保存技能」开关，默认关闭。
- 开启后，仅当 Act/Max 最终完成、且包含至少一个成功的 2 步以上 `ui_control sequence` 时自动保存；中途失败的重试会被跳过，只记录 DONE 步骤。
- 自动保存会过滤 `plan_update` / `ask_user` / `load_skill` / `spawn_agent` / `automation`、全部 memory 工具（尤其 `core_memory_update`）、纯观察型 `ui_control`（snapshot/find/screen_text/read_text/wait_for/assert_text）和屏幕读取类工具；按步骤签名去重，最多保留 20 个；相同流程不会重复写入。疑似密码/验证码/密钥输入（按前一个点击控件标签或 token 前缀识别）直接跳过自动保存；普通输入文本仍会写进技能，因此设置页明确提示不要在密码/聊天等敏感流程开启。
- 自动技能和手动技能一样通过 `CompositeSkillTool` 回放，仍走权限门与失败停止；关闭开关不会删除已有技能。
- 主聊天界面不显示保存提示，避免干扰正常阅读。

### 剩余

- 自动参数化（把 query/app 等提取成 `{{参数}}`）未做。
- 自动判断“这次是否值得保存”未做；当前是用户显式开启后的保守规则。
- 自动技能的命名目前取第一个 tap 文案，必要时仍需在库里重命名。

### 验收

- 保存后同一任务不再需要模型逐步探索。
- replay 失败能回退到普通 Agent。
- 不能保存含 `spawn_agent`/`automation` 的循环技能（已有约束）。

## 11. E8：快慢模型分层 / 本地路由

### 方案 A：本地规则优先

- `ToolRouter` 已经做关键词路由；扩展为意图分类（search / app / file / device / chat）。
- 不需要额外模型调用。

### 方案 B：快模型辅助

- Provider 配置增加可选 `fastModel`。
- 快模型只做：
  - 意图/工具选择
  - 屏幕观察压缩
  - 结果摘要
- 强模型做最终决策/回答。
- 失败/未配置时回退主模型。

### 风险

- 两套模型增加配置复杂度和费用不确定性。
- 快模型摘要可能丢信息；必须保留原始工具结果在本地，模型只拿摘要。

### 验收

- 同一任务 token/费用下降，成功率不降。
- 关闭快模型后行为与当前一致。

## 12. E9：Act 只读并行 + 预取

- CHAT+：全部只读、`parallelSafe`、gate Allow 的调用已经可以并行；不改变权限门。
- ACT：每个调用都要用户确认，直接并行会绕过逐项确认；需要先设计批量确认，暂不做。
- 预取（可选）：用户消息命中 web/app 研究意图时，在模型首轮思考期间预启动 `web_search`/`app_research`；结果只作为缓存，未请求则丢弃。
- 预取必须有预算、可取消、不能触发写操作。

### 验收

- 多工具只读任务少一轮。
- 预取不增加失败率，不越过权限门。

## 13. E10：验证、发布与收尾

### 真机矩阵

- Android 11/12/13/14/15/16
- Pixel / 三星 / 小米 / OPPO/vivo
- 无线调试 mDNS、系统配对窗口、虚拟屏、IME、旋转、分屏、锁屏
- Shizuku / root / 无线 / 前台四条路径

### Release 全链路

- release APK 安装后：无线配对 → 连接 → helper → 虚拟屏 → app_research → 截图回执。
- R8 验证 Conscrypt/libadb/Spake2/BouncyCastle/AIDL 均保留。

### 截图与附件生命周期

- 消息删除 → 附件文件回收。
- 截图开关、保留期限、敏感页面跳过。
- 清空会话/导入导出时附件行为一致。

### 文档/官网

- README、docs/README.en、HOW_IT_WORKS、SECURITY、site/index.html。
- 版本 bump、CHANGELOG、release notes、APK SHA。
- 官网 demo 改成隐式提示 + 截图回执。

### 发布门禁

- 全部单测 + lint + debug/release。
- 57 工具 schema 校验。
- `BAIC2.png` / `docs/icon.png` 不改。
- 用户明确说“发”才发布。

## 14. 里程碑

### M1：效率核心

- E2 + E3。
- 验收：app_research 虚拟屏路径无逐动作 shell；端到端耗时明显下降；Settings 连续 10 次成功。

### M2：观察与 shell

- E5 + E4。
- 验收：重复读屏 token 下降；无线 shell 连续命令只握手一次。

### M3：知识卡与技能

- E6 + E7。
- 验收：高频 App 启动更快；成功流程可一键复用。

### M4：模型与并行

- E8 + E9。
- 验收：只读多工具少一轮；快模型可选且可回退。

### M5：发布

- E1 剩余 + E10。
- 验收：真机矩阵、release 全链路、截图生命周期、文档/官网、版本/CHANGELOG。

## 15. 关键文件

- `core/engine/.../PromptCompiler.kt`：稳定前缀/运行时尾部。
- `core/engine/.../ToolRouter.kt`：CHAT+ 工具路由。
- `core/engine/.../AgentLoop.kt`：轮次、并行、预算、gate。
- `tools/.../apps/AppResearchTool.kt`：搜索/滚动/读文字/结果压缩。
- `tools/.../apps/AppResearchSessions.kt`：虚拟屏研究 session 复用。
- `tools/.../apps/AppKnowledgeStore.kt`：搜索入口结构 id 的知识缓存。
- `tools/.../apps/ResearchLexicon.kt`：语言资源词表（只作兜底）。
- `device/api/.../VirtualDisplayBridge.kt`：虚拟屏接口。
- `device/api/.../WirelessAdbBridge.kt`：无线调试接口。
- `device/impl/.../wireless/LocalPortScan.kt`：无线调试端口的本机 NIO 扫描。
- `device/impl/.../display/AndroidVirtualDisplayBridge.kt`：helper 启动与动作分发。
- `device/impl/.../display/ShellDisplayService.kt`：helper 内创建 display。
- `device/api/src/main/aidl/.../IVirtualDisplayService.aidl`：E2 扩展点。
- `device/impl/.../a11y/AndroidAccessibilityBridge.kt`：跨屏 a11y、E3 扩展点。
- `feature/chat/.../ChatViewModel.kt`：活动提示、截图回执、历史附件剥离。
- `feature/chat/.../MessageViews.kt`：ActivityHintLine、ToolSummaryCard、assistant 截图。
- `app/.../WirelessPairingService.kt`：自动读配对码。
- `app/.../VirtualDisplayHelperProvider.kt`：Binder 交接。

## 16. 进度日志

- 2026-10-31：建立本文档。基线确认：57 工具、DB v21、act/max 隐式 UI 与截图回执已实现但未真机端到端。
- 2026-10-31：E2 落地。`IVirtualDisplayService` 增加启动/点击/滑动/按键/输入；helper 内 startApp 使用 ActivityTaskManager，失败在 helper 内 am start；bridge 优先 Binder。模拟器 + Shizuku 验证通过；待无线 transport 与 release R8 复测。
- 2026-10-31：E3 基础动作落地。跨屏 `ACTION_CLICK` / `ACTION_SET_TEXT` / `ACTION_IME_ENTER` 在 Settings 搜索验证通过；滚动和事件等待待补测。
- 2026-10-31：`app_research` 增加 `open`。搜索后按可见文本打开一条结果并读取详情，减少一轮“先看结果、再点进去”的模型往返。模拟器 + Shizuku + 虚拟屏验证：`Battery Saver` 搜索 → 打开 → 详情成功。
- 2026-10-31：前台输入修复。`AccessibilityBridge.typeText` 从“只看 active window”改为扫描默认 display 的所有窗口，Settings 搜索框（独立窗口）不再 `set_text_rejected`；前台 `app_research` 搜索 `battery` → 打开 `Battery Saver` → 详情在模拟器验证通过（后续重复复测出现窗口/焦点波动，见下条）。
- 2026-10-31：E3 事件等待落地。`awaitContentChange` 用无障碍事件唤醒 + 周期快照兜底；`app_research` 启动/提交/滚动后的固定 sleep 改为内容等待。虚拟屏验证：`battery`（2 屏）、`display`（2 屏）、`s`（2 屏滚动）与中文 `电池` 输入均成功。前台无障碍路径复测出现 `set_text_rejected`，事件等待未报错，但输入仍需修。剩余：连续 10 次成功率/耗时统计、第三方 App 回归。
- 2026-10-31：无线调试保活落地。配对后经 ADB shell 自授权 `WRITE_SECURE_SETTINGS`；启动/开机/Wi-Fi 变化/开关变化时尝试重开无线调试并重连；端口按缓存 → `service.adb.tls.port` → 手动端口 → mDNS 兜底，成功端口写回缓存；权限页增加手动端口入口、自恢复开关与说明。模拟器验证：授予权限后 App 启动把 `adb_wifi_enabled` 从 0 写回 1。剩余：真机/OEM 验证、端口本机扫描、前台搜索输入。
- 2026-10-31：前台文本输入增加 `ACTION_FOCUS` → `ACTION_SET_TEXT` → 剪贴板 `ACTION_PASTE` 兜底，覆盖拒绝 `ACTION_SET_TEXT` 的 OEM 输入框；编译/lint 通过，仍待真机与前台 `app_research` 验证。
- 2026-10-31：无线端口补本机扫描。新增 `LocalPortScan`，读取 `/proc/sys/net/ipv4/ip_local_port_range`，用 NIO 扫 127.0.0.1 与本机 Wi-Fi IPv4，仅在本机地址上找开放端口；作为缓存/属性/手动/mDNS 之后的最后兜底。单测覆盖端口范围解析与 localhost 监听端口发现。
- 2026-10-31：CHAT+ 只读并行落地。`AgentLoop` 对 CHAT+ 中全部 `parallelSafe` 且 gate Allow 的调用并行执行，结果仍按原顺序写回；单测验证两个只读调用重叠执行。
- 2026-10-31：去掉 `app_research` 的搜索/UI 文案硬编码。搜索入口先按 view id（含 `search`）识别，失败才用 `tools` 资源数组里的本地化标签；动作词、开关前缀、登录/验证码/风控词也全部资源化（默认英文 + 中文）。模拟器英文 Settings 验证通过；中文 Settings 在英文 App 语言下也能靠 view id 找到入口。
- 2026-10-31：`ui_control` 增加 `sequence`：一次调用不限步数（5 分钟安全预算），逐步校验，失败即停；用于把“搜索 → 点结果 → 读详情”等多步 UI 流程压成一轮模型调用。
- 2026-11-02：合并社区 PR #2（俄语本地化）与 PR #3（局域网明文 HTTP）。PR #2 通过许可证头、字符串同步、占位符/数组校验、`:app:assembleDebug` 与 `lintDebug`；PR #3 合并后立即加 `NetworkEndpointPolicy`：HTTPS 任意，HTTP 仅限 loopback/RFC1918/link-local/私有 mDNS，公网 HTTP 在设置向导、自动拉模型和保存前拒绝，并有单测。
- 2026-11-02：内置浏览器技术验证通过。`DebugBrowserActivity` 用应用自有 `DisplayManager.createVirtualDisplay()`（`OWN_CONTENT_ONLY`）创建虚拟屏，`Presentation + WebView` 成功加载 `example.com`，JS 读取 DOM/链接/输入框，点击链接导航到 `iana.org`，`ImageReader` 抓取 1200×2608 画面正常。全程不需要 Shizuku/root/无线 ADB。设计与 MVP 见 `docs/WEB_RESEARCH.md`；下一步抽 `BrowserHost`/`BrowserSession`，做只读 `web_research`。
- 2026-11-01：v0.4.1 稳定性补丁（候选，未发布）。修复 R8 删除 ML Kit `ComponentRegistrar` 无参构造导致 release 包首次 OCR 必然 NPE；`app_research` 增加 `Search state`（results/empty/not-found/not-submitted）、OCR-first 第三方 App 路径、移除任意 EditText 兜底、虚拟屏坐标改走 `displays.tap`；OCR 串行化/回调兜底/超时；release APK OCR 冒烟通过。真机微信回归暴露 OCR 选中底部“搜索”tab；已加上半屏搜索入口过滤，待复测。
- 2026-11-01：v0.4.0 收版。虚拟屏 helper / provider 交接 / 无线 ADB 本机枚举独立重写；`app_research` 增加 EditText 聚焦与提交重试，虚拟屏搜索失败回退前台；修复 Settings 误匹配数字健康包；附件删除/7 天截图清理/保留截图开关；自动保存技能把输入值参数化；开机和 App 更新后重挂保活。真机：`Settings` → `WLAN` 首次 8.7s、同 session 1.7–2.0s（3/3）；`电池` 首次 14.4s、复用 1.6–1.7s（5/5）；虚拟屏 capture 204KB。
- 2026-11-01：虚拟屏本地会话复用与保活。`ensure()` 先检查已有 helper Binder，再检查 shell 传输；实测 Wi-Fi 关闭后 `adb_wifi_enabled=0`、无 TLS 端口，helper 仍存活，`ensure/capture` 继续成功。新增设置 → 权限 → 「后台虚拟屏保活」前台服务；开启时关闭空闲回收，并每 4 分钟 ensure 一次；常驻通知、耗电略增，重启后等待新的 shell。真实设备验证（旧测试构建，未含保活服务）：Wi-Fi 关闭后 `adb_wifi_enabled=0`，helper 仍存活，displayId=8 复用成功，capture 约 215KB；保活服务在 API 35 模拟器验证：开关开启后前台服务运行，关闭 Wi-Fi 后 helper 仍在，`ensure/capture` 成功。测试包已卸载。
- 2026-10-31：虚拟屏预热落地。App 启动时若已有可用 shell 传输（Shizuku/root/无线），后台调用 `ensure()` 预热 helper；每次 ensure/capture/动作刷新 10 分钟 idle 计时，空闲自动释放。API 35 实测：冷启动首调约 12.2s，预热后首调约 4.2s。
- 2026-10-31：API 30 模拟器（Android 11）前台路径验证。Settings 搜索结果分布在多个 application window；`app_research` 增加 all-application-window 读取、系统窗口过滤、可点击结果过滤和搜索结果等待。API 30 `battery` + `open_top` 成功打开 `Battery optimization`；API 35 虚拟屏回归通过。
- 2026-10-31：CHAT+ 开放只读研究子代理。`spawn_agent` 加入 CHAT+ 核心工具，仍保持 `parallelSafe`；父模式 CHAT+ 时子 Agent 被强制为 CHAT_PLUS，不能写设备。多个 `spawn_agent` 可在同一轮并行执行独立搜索。`app_research` 用进程级 mutex 串行化，避免两个子代理同时驱动同一虚拟屏。
- 2026-10-31：`app_research` 学习搜索入口结构。成功后把 view id 写入 `filesDir/app-knowledge.json`，下次带 preferred view id 查找；只保存包名、view id、时间和成功次数。
- 2026-10-31：去掉 `app_research` 的词表式动作拒绝。`open`/`open_top` 不再按“发送/购买/关注/Use …”等文案 block，改为提示 Agent 只选结果行；工具只按控件结构（Button/ImageButton/Switch/CheckBox/EditText/带开关行）保护。登录/验证码/风控从硬 `Failure` 改为结果里的 hand-off note，减少误 block。
- 2026-10-31：`app_research` 增加 `open_top` 与 `session`。虚拟屏首次调用返回 session id，后续调用复用同一 App/搜索页；`open_top` 打开第一条 query 命中且非控件的结果。模拟器验证：Settings `battery` + `open_top` 直接打开 `Battery Saver` 并读详情，返回 `Session: ...`；带同一 session 的第二次调用直接读取详情页，未重新启动/搜索。调试计时：首次约 12.2s（含 helper 启动/搜索/详情），session 复用约 2.3s。
- 2026-10-31：自动保存端到端验证（模拟器 + DeepSeek）。API 35 模拟器配置 DeepSeek 后，以 MAX 模式跑“打开设置 → 搜索 display → 读结果”：8 轮、7 次工具调用、16s；成功触发 auto-save，生成 `Auto · Search settings`。验证中发现旧过滤规则把 `core_memory_update` 和纯观察步骤也写进技能，会在回放时污染记忆；已修复为排除 memory/编排/观察型调用，调试 harness 确认保存步骤只剩 `open_settings` + `ui_control`。测试技能已删除，模拟器开关已恢复关闭。
- 2026-10-31：`app_research` session 支持换 query。session 记录是否停在详情页：结果页先尝试搜索节点/本地化搜索控件/可编辑节点并重新提交；详情页或找不到搜索框时在同一虚拟屏上重启目标 App，避免不可靠的返回键把虚拟屏置空。模拟器验证：Settings `battery` → `display` 成功返回 `Display size and text` 等结果，耗时约 8.6–9.4s（首次约 8.8s，省去 display/helper 重建但收益有限）；同 query 复用仍约 2.3s。Settings 的结果页搜索框位于独立窗口，当前 fast path 常回落到“同一 display 重启 App”；后续需用 App 知识卡记录搜索入口/返回路径，而不是依赖 active root 查找。
- 2026-10-31：结构化快照与元素引用落地。`ui_control snapshot` 跨 `TYPE_APPLICATION` 窗口列出带标签的可交互元素，分配 `e1…` 引用；引用 5 分钟过期、下次快照覆盖，点击前按标签 + 32px 位置重新校验，屏幕变化后拒绝旧引用而不是盲点。保存技能时优先用本次运行的快照结果把引用还原成文字，避免回放依赖瞬时句柄；设置 → 技能新增默认关闭的「自动保存技能」，只对最终完成、含成功 2 步以上 `ui_control sequence` 的 Act/Max 运行生效；失败的重试步骤会被跳过，自动去重并最多保留 20 个。`sequence` 新增 `assert_text` / `read_text`，不限步数、5 分钟安全预算，等待/断言按剩余预算裁剪；`type` 在刚打开的页面上短暂重试并尝试聚焦搜索框。API 35 模拟器验证：Settings 搜索 → 输入 battery → 等待 → 快照 → 按 e6 引用打开 `Battery Saver` → 断言 `Extreme Battery Saver` → 读屏，7 步约 2.8s；首次复测暴露的输入过早问题已由重试 + 提交后内容等待修复。API 30 验证同一流程（断言 `Turn on now`）7 步约 1.8s，快照 25 个元素；同时修掉 API 30 `isVisibleToUser=false` 导致 RecyclerView 结果行被快照漏掉的问题，改用屏幕内 bounds 判断。

## 17. v0.4.1 / 稳定性补丁门禁

- **只在功能开发完成、只剩测试和收尾时发布**。
- `v0.4.1` 覆盖 Xiaomi 17 Pro Max（Android 16 / HyperOS）+ API 30/35 模拟器；完整 OEM 矩阵（Pixel/三星/OPPO/vivo）继续作为后续门槛。
- 0.4.1 的目标：修复 release 包 ML Kit OCR 组件注册失败、`app_research` 第三方 App 语义化失败与 OCR-first 路径、release 包真机回归微信/X。
- 版本目标：`0.4.1`，更新 `versionName`、`versionCode`、CHANGELOG、README/docs、release notes。
- 发布说明只描述本项目自己的实现与依赖许可；不追加参考仓库链接，不复制第三方代码。
- 发布门禁：全量测试、lint、debug/release、57 工具 schema、许可证头、字符串同步、release helper `app_process` 加载、APK SHA-256。
