# 贡献指南 Contributing

欢迎提交 Issue 和 Pull Request！在此之前，请花两分钟阅读以下约定。

Thanks for your interest in contributing! Issues and pull requests are welcome.

## 提交 Issue

- **Bug**：请使用内置的 Bug 反馈模板，附上应用版本、设备型号、系统版本与复现步骤，有日志更好
- **功能建议**：请先搜索已有 Issue，避免重复
- 提交前请确认使用的是 [最新版本](https://github.com/Verlintas/NovaBAIC/releases/latest)
- **安全问题请不要公开提交**，走 [SECURITY.md](SECURITY.md) 的私下渠道

## 开发环境

- JDK 17、Android SDK Platform 37（build-tools 37.0.0）
- 首次构建：`./gradlew :app:assembleDebug`
- 本地联调不需要 API Key：`dev/` 下有两个 mock 服务器

  ```bash
  python3 dev/mock-openai-server.py 8765   # OpenAI 兼容（流式/工具/辅助任务）
  python3 dev/mock-mcp-server.py 8766      # MCP Streamable HTTP
  adb reverse tcp:8765 tcp:8765 && adb reverse tcp:8766 tcp:8766
  # App 内配置：Base URL http://localhost:8765/v1，Key 任意
  # 工具链测试：给模型发 tooltest:工具名 或 tooltest:auto
  ```

- Release 签名仅维护者需要，日常开发用 debug 构建即可

## 代码结构

```
app/            Compose UI 壳、导航、DI 装配
core/model      纯 Kotlin 领域模型（JVM）
core/engine     AgentLoop、确认队列、工具契约（JVM）
core/data       Room + DataStore + 仓库 + Keystore 加密
core/network    OkHttp + SSE + OpenAI/Anthropic/Gemini 适配
core/runtime    运行持久化（演进中）
core/designsystem 设计系统 token 与组件
feature/*       chat / conversations / tasks / settings / agents / library
device/api      设备能力接口（截图/OCR/无障碍/语音/提醒/运行通知）
device/impl     Android 实现（服务与广播接收器）
tools           57 个内置工具 + 技能 / 自动化 / 子代理
mcp             远程 MCP 客户端
eval            场景评测 harness
dev/            本地 mock 服务器
```

依赖方向：`feature -> core / device:api / tools`，`app` 负责装配；`core:model` 与 `core:engine` 是纯 Kotlin，不得引入 Android 依赖。更详细的说明见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 提交 PR

- 一个 PR 解决一件事，标题与提交信息可用 `feat:` / `fix:` / `docs:` / `chore:` 前缀
- 提交前请确保：
  - `tools/check-license-headers.sh` 通过（所有源码需带 GPL-3.0-or-later 头）
  - `./gradlew test` 单元测试通过
  - `./gradlew :app:assembleDebug` 构建通过
  - 在真机或模拟器上实际验证过
- 用户可见的改动请在 `CHANGELOG.md` 顶部按既有格式添加条目
- 不要提交密钥、签名文件（`*.keystore` / `*.jks`）、`keystore.properties`、`local.properties`

## 代码风格

- 沿用现有代码风格（Kotlin 官方风格、Compose 惯用写法）
- 用户可见字符串一律走资源文件（默认中文 + `values-en` 英文）
- 领域逻辑尽量放在 `core:engine` / `core:data` 并补单元测试；工具错误必须可行动（告诉模型下一步怎么做）
- 新工具 = 实现 `DeviceTool` + 在 `ToolsModule` 注册；新 Provider = 实现 `ChatProvider` + `ProviderFactory` 分支 + MockWebServer 测试
