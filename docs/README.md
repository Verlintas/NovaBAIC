# BetterAIChat2 文档

根目录的 [README](../README.md) 面向第一次接触项目的用户；这里存放技术文档、项目治理说明和历史记录。

## 开始使用

- [README（简体中文）](../README.md) — 功能概览、安装、模式说明与常见问题
- [README (English)](README.en.md) — English overview and quick start
- [How it works](HOW_IT_WORKS.md) — 完整技术剖析：模块架构、请求管线、Agent 循环、工具系统、设备桥、数据层与评测（英文）
- [Architecture](ARCHITECTURE.md) — 架构原则、模块依赖与工具链决策

## Agent 与实现

- [Agent 效率与隐式交互路线图](AGENT_EFFICIENCY_ROADMAP.md) — 当前 Agent 能力迭代的工作流、验收标准与进度
- [Architecture Decision Records](adr/) — 关键架构决策及其背景

## 项目治理

- [Contributing](CONTRIBUTING.md) — 开发环境、代码结构、PR 约定
- [Code of Conduct](CODE_OF_CONDUCT.md) — 社区行为准则
- [Security model](SECURITY.md) — 威胁模型、信任边界与已实现的防御
- [Changelog](CHANGELOG.md) — 每个版本的详细变更

## 资源

- [icon.png](icon.png) — 文档图标
- [screenshots/](screenshots/) — 界面截图

## 维护约定

- 面向用户的概览留在根目录 `README.md`，实现细节和长文档放进 `docs/`。
- 新增文档时，在上面的索引里加一行，避免文档再次散落。
- 架构或用户可见行为变化时，同步更新 `docs/HOW_IT_WORKS.md`、`docs/ARCHITECTURE.md`、`docs/README.md`、`docs/README.en.md`、`docs/CHANGELOG.md` 和根目录 `README.md` 中受影响的部分。
