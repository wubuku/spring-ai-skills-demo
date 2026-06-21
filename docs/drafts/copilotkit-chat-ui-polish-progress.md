# CopilotKit Chat UI Polish — Progress Tracker

> Last updated: 2026-06-21 (v12: prompt cleanup, rate limited)

## Goal

实现购物流程的端到端体验优化：
1. 用户登录后问"我有什么商品可以买？" → 获得商品列表
2. 用户说"将 iPhone 15 添加到购物车" → 弹出确认卡片 → 点击确认 → 添加成功
3. 用户说"查询我的购物车" → 列出购物车内容

**硬性约束**：
- 使用 MiniMax-M2.7-highspeed 模型（不换模型）
- 流式必须是真流式（不能伪流式）
- 不能作弊（提示词中不能硬编码 API URL，模型必须通过 SKILL 探索获取 URL）

## Current Status

**RATE LIMITED — 等待 API 限流恢复**

### 已完成的修复

1. **后端 httpRequest URL 校验**（SpringAIAgent.java）
   - 当模型发送错误 URL 时，后端返回 `[URL_VALIDATION_ERROR]`
   - 模型看到错误后自行纠正（调用 loadSkill）
   - 之前 5/5 通过（但那时提示词有硬编码示例）

2. **前端 URL 匹配算法修复**（useHttpRequestTool.tsx）
   - `{param}` 通配符不再匹配非数字路径段
   - 阈值从 0.4 提高到 0.5

3. **提示词清理**（进行中）
   - 移除了硬编码的 API URL 示例
   - 添加了通用的对话流程示例（展示 loadSkill → httpRequest 的模式）
   - ⚠️ 需要测试：MiniMax 是否能在没有硬编码示例的情况下正确调用工具

### 关键发现

- **MiniMax 需要具体示例**：完全移除示例后，模型不调用任何工具（0/5 通过）
- **需要平衡**：示例要展示工具调用的"模式"，但不能硬编码具体 API URL
- **API 限流**：连续测试导致 429 错误，需要等待或切换 key

### 下一步

1. 等待 API 限流恢复（或切换 key）
2. 测试当前提示词是否能让模型正确调用工具
3. 如果仍然失败，需要找到"不作弊但有示例"的平衡点

## Key Files

| File | Purpose |
|------|---------|
| `src/main/java/com/agui/spring/ai/SpringAIAgent.java` | Backend URL validation |
| `src/main/java/com/example/demo/config/AgUiConfig.java` | Agent config |
| `src/main/resources/prompts/enterprise-agent/system-prompt.template` | System prompt |
| `src/main/resources/prompts/skills-advisor/mode-rules.template` | Mode rules |
| `frontend/hooks/useHttpRequestTool.tsx` | Frontend URL matching |

## Environment

- Backend: `export $(cat .env | grep -v '^#' | xargs) && java -jar target/spring-ai-skills-demo-1.0.0.jar` (port 8080)
- Frontend: `cd frontend && export $(cat ../.env | grep -v '^#' | xargs) && npx next dev -p 4001` (port 4001)
- Login: admin / admin123
- Model: MiniMax-M2.7-highspeed
- CopilotKit: v1.60.2, Spring AI: 1.1.2
- API Keys: 两个 key 轮换使用（.env 中有注释说明）

