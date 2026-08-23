# 草稿与历史材料

> **文档状态**: 导航索引
> **最后核对**: 2026-08-23

`docs/drafts/` 保存计划、调查、诊断、比较和进度记录。这里的内容可能包含已替代的端口、工具、认证或配置方案，使用前必须回到源码和 `docs/` 稳定指南核对。

## 当前工作计划

- [DOCUMENTATION_PLAN.md](DOCUMENTATION_PLAN.md)：本轮文档体系建设计划。
- [skill-support-improvement-plan.md](skill-support-improvement-plan.md)：当前项目 SKILL 支持改进规划；依赖 [社区库审计报告](../spring-ai-agent-utils-audit.md) 的固定 `v0.10.0` 基线。

## 稳定指南的历史补充

- [spring-ai-agui-guide.md](spring-ai-agui-guide.md)：AG-UI 调研和实现草稿。
- [AG-UI-4j-SSE-Streaming-Analysis.md](AG-UI-4j-SSE-Streaming-Analysis.md)：AG-UI SSE 生命周期分析。
- [v2-copilotkit-incomplete-stream-checkpoint-2026-06-14.md](v2-copilotkit-incomplete-stream-checkpoint-2026-06-14.md)：CopilotKit v2 SSE 问题检查点。
- [copilotkit-native-httprequest-rescue-plan.md](copilotkit-native-httprequest-rescue-plan.md)：原生 HTTP 工具迁移方案。
- [copilotkit-native-tool-call-refactoring-plan.md](copilotkit-native-tool-call-refactoring-plan.md)：前后端工具调用重构方案。

## 认证、工具和确认诊断

- [agui-user-token-pass-through-diagnosis.md](agui-user-token-pass-through-diagnosis.md)：AG-UI 用户 Token 透传调查。
- [auth-pass-through-solution.md](auth-pass-through-solution.md)：认证透传方案草稿。
- [httpRequest-tool-call-stuck-report.md](httpRequest-tool-call-stuck-report.md)：HTTP 工具卡住问题报告。
- [frontend-httprequest-stub-execution-diagnosis.md](frontend-httprequest-stub-execution-diagnosis.md)：前端工具 stub 执行诊断。
- [frontend-confirmation-dialog-plan.md](frontend-confirmation-dialog-plan.md)：旧确认对话框方案，当前实现已改为 `useHumanInTheLoop`。

## 功能和架构方案

- [api-result-explainer-design.md](api-result-explainer-design.md)：API 结果解释功能设计。
- [memory-system-improvement-plan.md](memory-system-improvement-plan.md)：记忆系统改进计划。
- [vector-store-chat-memory-advisor-plan.md](vector-store-chat-memory-advisor-plan.md)：向量记忆 Advisor 方案。
- [rag-knowledge-base-plan.md](rag-knowledge-base-plan.md)：RAG 知识库方案。
- [multimodal-vision-prompt-enhancement-plan.md](multimodal-vision-prompt-enhancement-plan.md)：多模态视觉提示词方案。
- [多模态输入支持规划文档.md](多模态输入支持规划文档.md)：多模态输入规划。
- [prompt-extraction-plan.md](prompt-extraction-plan.md)：提示词模板提取方案。

## 探索、比较和进度记录

- [ai-agent-framework-comparison.md](ai-agent-framework-comparison.md)：Agent 框架比较。
- [copilotkit-exploration.md](copilotkit-exploration.md)：CopilotKit 探索记录。
- [enterprise-agent-frontend-guide-v4.md](enterprise-agent-frontend-guide-v4.md)：前端集成指南草稿。
- [copilotkit-chat-ui-polish-progress.md](copilotkit-chat-ui-polish-progress.md)：聊天 UI 进度记录。
- [spring-ai-skills-demo.md](spring-ai-skills-demo.md)：项目早期方案/进度记录。

## 使用规则

1. 草稿中的命令、端口、Provider 版本和认证命名都必须回到当前源码核对。
2. 已完成的方案保留为历史背景，不自动升级为稳定指南。
3. 图片、日志和 `*.bak` 文件不是文档入口；需要复现时再按文件名定位。
4. 新增草稿时使用描述性文件名，并在开头标明状态、目的和最后核对日期。
