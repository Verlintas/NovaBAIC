# Web research / 内置浏览器

> 状态：技术验证通过，设计阶段
> 最后更新：2026-11-02
> 目标版本：0.5.0

## 1. 结论

对于**有可用网页端**的平台，内置浏览器 + DOM 自动化比继续硬啃第三方 App 的原生 UI 更稳。

原因很直接：

- 原生 `app_research` 依赖无障碍树 / OCR / 坐标；微信这类 App 会主动隐藏无障碍树，搜索入口还是纯图标，识别成本高且容易点错。
- 网页端有 DOM：文本、链接、输入框、按钮、`aria-label` 都是结构化的，选择器比 OCR 坐标稳定得多。
- 浏览器是我们自己的 Activity / WebView，不需要把别人的 App 塞进虚拟屏，也不需要 `am start --display`。
- 登录 / 验证码可以自然交还前台：同一个会话、同一份 Cookie，用户完成后再回到后台。

## 2. 已验证的原型

Debug-only 的 `DebugBrowserActivity` 已经验证：

- 用 `DisplayManager.createVirtualDisplay()` 创建**应用自己的虚拟屏**（不需要 Shizuku / root / 无线 ADB）。
- 在虚拟屏上显示 `Presentation` + `WebView`。
- 加载 `https://example.com`，通过 `evaluateJavascript` 读取标题、正文、链接、输入框。
- 通过 JS 点击链接，页面成功从 `example.com` 导航到 `iana.org/help/example-domains`。
- 用 `ImageReader` 抓取虚拟屏画面，PNG 1200×2608，页面渲染正常。

关键参数：

```kotlin
DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
```

不要加 `PUBLIC | PRESENTATION` 但没有 `OWN_CONTENT_ONLY`，否则系统会要求 `ADD_MIRROR_DISPLAY` / `CAPTURE_VIDEO_OUTPUT` 或 MediaProjection token。

`Presentation` 要用 `virtualDisplay.display`，不要用 `DisplayManager.getDisplay(id)` 重新查；后者在部分 HyperOS 上会报 `InvalidDisplayException: specified display can not be found`。

## 3. 目标形态

新增工具 `web_research`（或 `browse`），与 `app_research` 并列：

```text
web_research open(url)
web_research snapshot()          # 页面文本 + 可交互元素 + 稳定 ref
web_research click(ref)
web_research type(ref, text)
web_research scroll(up|down)
web_research read()
web_research show_to_user()      # 把当前会话交给前台，用户登录/过验证码
web_research hide()
```

实现上不需要把动作暴露给模型做坐标点击：工具内部用 DOM 选择器和 ref 完成，模型只看到结构化结果。

### snapshot 返回什么

- 页面标题、URL、可见正文（截断）
- 可交互元素列表：索引、标签、role、tag、placeholder、href、是否可编辑、是否可见
- 表单 / 搜索框 / 结果列表的语义分组

### 登录 / 验证码 handoff

1. 自动化检测到登录页、验证码、风控或空结果。
2. `web_research` 返回 `NeedsUser` + 原因，不继续猜。
3. `show_to_user` 把同一个 WebView 会话显示到前台（Activity 或悬浮窗）。
4. 用户完成登录/验证码；工具轮询 DOM/URL 判断成功。
5. `hide` 回到虚拟屏，继续原任务。

Cookie / WebStorage 复用，不保存密码，不尝试绕过验证码。

## 4. 架构

```text
web_research tool
  -> BrowserSession (singleton, app process)
       -> BrowserHost (app-owned VirtualDisplay + Presentation + WebView)
       -> DomBridge (evaluateJavascript + ref registry)
       -> CookieStore (WebView CookieManager / WebStorage)
       -> Handoff (foreground Activity or overlay)
```

- **BrowserHost**：应用自己的虚拟屏，不需要 shell。用前台服务 + 唤醒锁保持进程和渲染。
- **DomBridge**：`snapshot` 时给元素分配短期 ref（例如 `w1`、`w2`），`click`/`type` 前按 DOM 重新校验；页面导航后 ref 失效。
- **CookieStore**：独立 WebView profile；设置里提供「清除浏览器数据」。
- **Handoff**：前台 Activity 复用同一个 WebView 或共享 Cookie 的第二个 WebView；登录完成后后台 WebView reload。

## 5. MVP 范围

先做**只读**，不做发送/下单/支付：

1. `open` + `snapshot` + `read` + `scroll`
2. `click` 只允许链接/结果行/搜索框，不允许提交表单
3. `type` 只允许搜索框，不自动按 Enter 发送消息
4. 登录/验证码 handoff
5. 先覆盖 2–3 个站点：GitHub、X、Reddit（或知乎）

通过标准：

- 同一页面连续 10 次 snapshot 稳定，ref 可点击
- 搜索结果能读回标题/摘要
- 登录页能交还前台并恢复
- 页面导航后旧 ref 被拒绝，不盲点

## 6. 风险

- **WebView 后台节流**：虚拟屏上的 WebView 可能被系统降频；需要前台服务/保持显示 active，实测再定。
- **反爬 / 风控**：验证码、登录墙、WebView 指纹；只能人工接管，不绕过。
- **账号风险**：自动化操作可能触发平台风控；默认低频、只读、可随时停止。
- **安全**：浏览器里有登录态；独立 profile、可清除、不保存密码；不把 Cookie 写进模型上下文。
- **WebView 兼容性**：部分站点要求 Chrome 内核/DRM；必要时只把前台 handoff 交给 Custom Tabs，后台自动化仍用 WebView。
- **平台条款**：只做用户授权范围内的读取；不承诺绕过任何平台限制。

## 7. 非目标

- 不用浏览器操作微信聊天（微信网页版不支持聊天搜索/读取）。
- 不做支付、下单、发送消息等状态变更，除非后续单独设计确认流程。
- 不把验证码/登录自动化；只做 handoff。
- 不替代 `app_research` 对系统 App / 原生只读页面的能力。

## 8. 下一步

1. 把 `DebugBrowserActivity` 提取成 `BrowserHost` + `BrowserSession`。
2. 实现 `snapshot` / `click(ref)` / `type(ref)` 的 DOM 桥。
3. 做一个 `web_research` 只读工具，先接 GitHub 搜索。
4. 验证前台 handoff（用 GitHub 登录页或 X 登录页）。
5. 通过后再考虑 0.5.0 发布。
