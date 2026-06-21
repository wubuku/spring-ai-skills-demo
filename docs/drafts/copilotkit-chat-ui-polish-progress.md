# CopilotKit Chat UI Polish — Progress Tracker

> Last updated: 2026-06-21 (v11: backend URL validation fix)

## Goal

实现购物流程的端到端体验优化：
1. 用户登录后问"我有什么商品可以买？" → 获得商品列表 ✅
2. 用户说"将 iPhone 15 添加到购物车" → 弹出确认卡片 → 点击确认 → 添加成功 ✅
3. 用户说"查询我的购物车" → 列出购物车内容 ✅

**硬性约束**：
- 使用 MiniMax-M2.7-highspeed 模型（不换模型）
- 流式必须是真流式（不能伪流式）
- 不能作弊（不能为了让测试通过而改安全规则）

## Current Status

**PASS RATE: 100% (5/5 runs)**

### Root Cause & Fix

**问题**：模型有时生成错误的 API URL（如 `/api/cart/add` 而非 `/api/products/cart`）

**修复（关键突破）**：在后端 `SpringAIAgent.executeToolCallsAndReRun()` 中添加 httpRequest URL 校验：
- 当模型发送 httpRequest 工具调用时，后端先校验 URL 是否在 API 索引中
- 如果 URL 不合法，后端直接返回 `[URL_VALIDATION_ERROR]` 工具结果
- 模型看到错误后自行纠正（调用 loadSkill 获取正确 URL）
- 这比前端校验更有效：模型能立即看到错误并重试

**其他修复**：
1. 前端 URL 匹配算法修复：`{param}` 通配符不再匹配非数字路径段
2. 前端匹配阈值从 0.4 提高到 0.5

### E2E Test Results (5 runs)

| Run | Products | AddToCart | Cart | 403s |
|-----|----------|-----------|------|------|
| 1   | ✅       | ✅        | ✅   | 0    |
| 2   | ✅       | ✅        | ✅   | 0    |
| 3   | ✅       | ✅        | ✅   | 0    |
| 4   | ✅       | ✅        | ✅   | 0    |
| 5   | ✅       | ✅        | ✅   | 0    |

## Changes Made

### Backend
1. **`SpringAIAgent.java`**: Added `skillRegistry` field + `validateHttpRequestUrl()` method
   - Validates httpRequest URLs against SkillRegistry API index
   - Returns `[URL_VALIDATION_ERROR]` with available endpoints on failure
   - Model self-corrects by calling loadSkill

2. **`AgUiConfig.java`**: Pass SkillRegistry to SpringAIAgent builder

3. **`SecurityConfig.java`** (from previous AI): cart/checkout require auth ✅
4. **`SkillCoreTools.java`** (from previous AI): mutation reminder ✅
5. **`SkillRegistry.java`** (from previous AI): exposed `getApiIndex()` ✅
6. **`AgUiController.java`** (from previous AI): `/skills/api-index` endpoint ✅
7. **System prompts** (from previous AI): improved instructions ✅

### Frontend
1. **`useHttpRequestTool.tsx`**: Fixed URL matching algorithm
   - `{param}` wildcards only match numeric segments
   - Threshold raised from 0.4 to 0.5

## Remaining Issues

1. **Streaming UX**: Long delays before response appears (model thinking time)
2. **Planning text leakage**: Model outputs verbose thinking that shows in chat
3. **Confirmation card UX**: Could be improved visually

## Key Files

| File | Purpose |
|------|---------|
| `src/main/java/com/agui/spring/ai/SpringAIAgent.java` | Backend URL validation |
| `src/main/java/com/example/demo/config/AgUiConfig.java` | Agent configuration |
| `frontend/hooks/useHttpRequestTool.tsx` | Frontend URL matching |

## Environment

- Backend: `export $(cat .env | grep -v '^#' | xargs) && java -jar target/spring-ai-skills-demo-1.0.0.jar` (port 8080)
- Frontend: `cd frontend && export $(cat ../.env | grep -v '^#' | xargs) && npx next dev -p 4001` (port 4001)
- Login: admin / admin123
- Model: MiniMax-M2.7-highspeed
- CopilotKit: v1.60.2, Spring AI: 1.1.2

