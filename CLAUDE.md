# Spring AI Skills Demo - Claude Code 记忆文件

## 项目概述
Spring Boot + Spring AI 多模态聊天应用，支持文本聊天、RAG（知识库问答）、图片识别和语音识别。

## 技术栈
- **框架**: Spring Boot 3.4.2, Spring AI 1.1.2
- **主模型**: DeepSeek (OpenAI API 兼容)
- **视觉模型**: 火山方舟 ARK (doubao-1-5-vision-pro-32k-250115)
- **语音转写**: 智谱 GLM-ASR (glm-asr-2512)
- **嵌入模型**: SiliconFlow (BAAI/bge-m3)
- **向量存储**: PostgreSQL + pgvector
- **数据库**: PostgreSQL (chat memory + vector store)
- **前端**: Next.js 15 + React 19 + TypeScript + Tailwind CSS + CopilotKit

## 启动方式（重要！）
```bash
# 1. 杀死现有服务
lsof -ti:8080 -sTCP:LISTEN | xargs -r kill -9 2>/dev/null; echo "Killed"

# 2. 加载 .env 环境变量（必须用 bash -c）
bash -c 'export $(cat .env | grep -v "^#" | xargs) && mvn spring-boot:run -DskipTests'

# 或者
set -a && . ./.env && set +a && mvn spring-boot:run -DskipTests
```

**注意**: 直接在 shell 中使用 `export $(cat .env ...)` 会导致 zsh 解析错误，必须用 `bash -c` 包裹。

## 多模态功能状态

### ✅ 文本聊天 - 正常
- DeepSeek 模型工作正常
- RAG（知识库）正常工作
- SiliconFlow 嵌入模型正常

### ✅ 图片识别 - 正常 (2026-04-01 已修复)
- **修复方案**: 在 `SpringAiConfig.java` 的 `visionChatClient()` 方法中添加 `.completionsPath("/chat/completions")`
- **原因**: Spring AI 的 OpenAiApi 默认追加 `/v1/chat/completions`，但火山方舟 ARK API 需要 `/chat/completions`
- **验证**: 图片识别返回正确的描述（dddappp.org 标志）

### ✅ 语音识别 - 正常
- 智谱 GLM-ASR 转写服务工作正常
- 测试音频: `/Users/yangjiefeng/Documents/wubuku/-IELTS-Preparation/video-workspaces/News_English_International_News_20260401_0912/news_english_video_workdir/concatenated.wav`

## 关键文件路径

### 配置
- `.env` - 所有环境配置（勿修改！）
- `src/main/resources/application.yml` - Spring Boot 主配置
- `src/main/resources/application-dev.yml` - 开发环境配置
- `src/main/resources/application-postgresql.yml` - PostgreSQL 配置

### Controller 层
- `controller/ChatController.java` - 文本聊天 (`/api/chat`, `/api/chat/stream`)
- `controller/MultimodalChatController.java` - 多模态聊天 (`/api/chat/multimodal/stream`)
- `controller/StreamingTranscriptionController.java` - 流式语音转写 (`/api/transcribe/stream`)
- `controller/AgUiController.java` - AG-UI 协议端点
- `controller/AuthController.java` - 认证 (`/api/auth`)
- `controller/ProductController.java` - 产品 CRUD (`/api/products`)

### Agent 层（AG-UI 模式）
- `agent/SkillCoreTools.java` - AG-UI 专用工具（仅 loadSkill + readSkillReference，不含 HTTP 工具）
- `agent/SkillTools.java` - 完整技能工具（含 httpRequest/buildHttpRequest，供 AgentService 链路使用）
- `agent/SkillsAdvisor.java` - 技能系统提示注入（AG-UI 模式使用 SkillCoreTools）
- `agent/SkillRegistry.java` - 技能注册表
- `agent/JsonArgToolCallback.java` - 自定义 ToolCallback 包装器，修复 Spring AI 1.1.2 JSON 参数反序列化 bug
- `com/agui/spring/ai/SpringAIAgent.java` - AG-UI 核心桥梁（含 maxToolCalls + StreamingTagFilter）

### Service 层
- `service/AgentService.java` - 核心聊天逻辑（RAG、记忆、技能）
- `service/MultimodalAgentService.java` - 图片理解、音频转写、流式处理
- `service/ConversationHistoryService.java` - JDBC 直查会话历史
- `service/OpenAiStreamingTranscriptionService.java` - OkHttp 流式 ASR
- `service/PromptLoader.java` - 模板加载

### 配置类
- `config/AgUiConfig.java` - AG-UI 协议配置（使用 SkillCoreTools，不注册 HTTP 工具）
- `config/SpringAiConfig.java` - AI 模型配置（修复了视觉模型路径问题）
- `config/VectorStorePostgresqlConfig.java` - pgvector 配置
- `config/EmbeddingModelConfig.java` - 嵌入模型配置
- `config/SecurityConfig.java` - 安全配置
- `config/CorsConfig.java` - CORS 配置

### 前端
- `frontend/app/page.tsx` - 主页面（HttpRequestToolProvider + CopilotPopup）
- `frontend/app/layout.tsx` - 布局
- `frontend/hooks/useHttpRequestTool.tsx` - httpRequest 工具注册（useCopilotAction）
- `frontend/instrumentation.ts` - undici Agent 配置（SSE 长连接超时）
- `frontend/components/AuthProvider.tsx` - 认证状态管理
- `frontend/components/CopilotAssistantMessage.tsx` - 简化透传组件（未引用，保留备用）

## 测试脚本

| 脚本 | 用途 |
|------|------|
| `test-streaming.sh` | 流式聊天测试（文本/图片/音频） |
| `test-multimodal.sh` | 多模态端点测试 |
| `test-streaming-transcribe.sh` | 流式转写测试 |
| `test.sh` | 通用 E2E 测试 |
| `test-agui-jwt-full.sh` | AG-UI JWT 认证测试 |
| `test-rag-knowledge-base.sh` | RAG 知识库测试 |

## 前端流式切换功能

### 功能说明
前端页面右上角新增了**流式/同步模式切换开关**，用于切换 API 调用方式和 AI 回复展示效果。

### 界面位置
```
[📡 流式] [开关]  ← 位于 auth-bar，与"欢迎，user1"同行
```

### 切换效果
| 状态 | 端点 | AI 回复展示 |
|------|------|-------------|
| 同步 | `/api/chat` | 完整显示 |
| 流式 | `/api/chat/multimodal/stream` | 打字机效果（逐字显示） |

### SSE 协议要点
- **格式**: `data:{"type":"vision"|"content"|"transcribed"|"prompt","choices":[{"delta":{"content":"xxx"}}]}`
- **事件分隔**: `\n\n`（双换行）

### SSE 事件类型
| type | 来源 | 说明 |
|------|------|------|
| `vision` | 视觉模型 | 图片描述内容 |
| `content` | 语言模型 | AI 最终回复 |
| `transcribed` | 语音转写 | 音频转文字 |
| `prompt` | 提示词生成 | 仅在有会话历史时出现（用于调试） |

## 流式语音转写功能

### 新增端点
| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/transcribe/stream` | POST | 纯语音流式转写（返回 SSE） |

### 技术实现
- 使用 OkHttp 直接调用 OpenAI 流式转写 API（`stream=True`）
- 将 OpenAI 的 `transcript.text.delta` 事件转换为 SSE 格式
- 响应格式: `data:{"type":"transcribed","choices":[{"delta":{"content":"xxx"}}]}`

## 图片输入处理逻辑增强

### 核心功能
根据会话历史选择不同的图片处理流程：

**无会话历史（冷启动）**：直接使用默认视觉提示词
**有会话历史**：先调用 LLM 生成**情境化视觉提示词**，再处理图片

### 新增组件
- `service/ConversationHistoryService.java` - JDBC 直查会话历史（`hasHistory`/`getMessageCount`/`getRecentHistorySummary`）
- `prompts/multimodal/vision-prompt-generator.template` - 视觉提示词生成模板
- `promptGenerationChatClient` Bean - 独立的 ChatClient（避免污染会话历史）
- `streamVisionToLlm()` - 视觉+LLM 流式处理公共方法

### 测试验证
- 测试命令: `bash test-streaming.sh --image`
- 测试结果: 757 个事件块（视觉流: 461, LLM流: 296）
- 状态: ✅ 功能正常

### 相关文档
- `docs/drafts/multimodal-vision-prompt-enhancement-plan.md` - 详细规划文档
- `README.md` - 图片输入处理逻辑说明

## Prompt 模板

| 模板路径 | 用途 |
|----------|------|
| `prompts/multimodal/vision-prompt.template` | 默认图片描述 |
| `prompts/multimodal/vision-prompt-with-hint.template` | 带用户查询提示的图片描述 |
| `prompts/multimodal/vision-prompt-generator.template` | 从历史生成情境化视觉提示词 |
| `prompts/skills-advisor/system-prompt.template` | 技能顾问基础提示 |
| `prompts/skills-advisor/mode-rules.template` | httpRequest 工具使用规则（前端原生工具调用） |
| `prompts/enterprise-agent/system-prompt.template` | 企业助手系统提示 |

## 数据库

### Chat Memory 表
```sql
CREATE TABLE SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(36) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
)
```

## 技术要点

1. **独立 promptGenerationChatClient**: 避免生成情境化视觉提示词时污染会话历史
2. **JDBC 直查会话历史**: 使用 JdbcTemplate 而非 Spring AI 内部 API，保证稳定性
3. **Flux 缓存 (.cache())**: 防止冷源导致重复 LLM 调用
4. **ffmpeg 音频转换**: 确保单声道音频符合 GLM-ASR 要求
5. **AG-UI 协议**: 通过 `ag-ui-4j` 子模块集成
6. **maxToolCalls 防御**: SpringAIAgent 单次 run 最多 5 次工具调用，防止推理模型（MiniMax-M3 等）无限循环
7. **StreamingTagFilter**: 跨 chunk 流式过滤推理模型泄漏的 `<think>`/`<parameter>`/`<invoke>` 等 XML/JSX 标签
8. **双 httpRequest 工具架构**（2026-06-11）：后端 SkillTools 注册 httpRequest（公开 API）+ 前端 useCopilotAction 注册 httpRequest（受保护 API）。LLM 根据 API 性质路由
9. **同名工具去重**（2026-06-12 关键修复）：`SpringAIAgent.run()` 合并 `input.tools()`（前端）和 `this.toolCallbacks`（后端）时，**按工具名去重保留后端版本**。否则 LLM 收到两个同名 httpRequest 会静默 hanging。后端日志: `Merged 4 toolCallbacks ... [skipped 1 duplicate frontend tool(s) by name: httpRequest]`
10. **undici Agent 超时**: `frontend/instrumentation.ts` 设置 bodyTimeout=0 支持 SSE 长连接
11. **WebClient vs OkHttp**: `MiniMaxApi` 用 WebClient（响应式）做 LLM 流式调用，不走 OkHttp 拦截器
12. **框架 setter ≠ appender**: Spring AI 的 `chatRequest.toolCallbacks(...)` 是覆盖前一个值；合并时必须 `new ArrayList<>(existing) + addAll(new)`，否则会丢工具
13. **Spring AI 1.1.2 JSON 参数反序列化 bug**（2026-06-15 修复）：`MethodToolCallback` 无法正确将 JSON 键映射到 `@Tool` 方法参数。解决方案：自定义 `JsonArgToolCallback` 包装器，手动解析 JSON 参数后通过反射调用
14. **ToolCallArgsEvent 必须收集到 deferredEvents**（2026-06-15 关键修复）：`SpringAIAgent.onEvent()` 中 `TOOL_CALL_ARGS` 事件仅发射到流但未添加到 `deferredEvents`，导致 `executeToolCallsAndReRun()` 收集到空参数 `{}`。修复：添加 `deferredEvents.add(toolCallArgsEvent(...))`
15. **loadSkill 错误信息自修正**（2026-06-15）：当 LLM 使用错误技能名时，错误信息显示所有可用技能列表，帮助 LLM 自动修正到正确技能名

## 文档
- `docs/CLAUDE.md` - 本记忆文件
- `docs/README.md` - 主文档
- **`docs/copilotkit-native-tool-call-lessons-learned.md`** - **CopilotKit 原生工具调用经验教训总结（必读！）**
- `docs/drafts/` - 设计文档:
  - `multimodal-vision-prompt-enhancement-plan.md`
  - `多模态输入支持规划文档.md`
  - `AG-UI-4j-SSE-Streaming-Analysis.md`
  - `vector-store-chat-memory-advisor-plan.md`
  - `copilotkit-native-tool-call-refactoring-plan.md` - **CopilotKit 原生工具调用重构（2026-06 已完成）**
  - `copilotkit-native-httprequest-rescue-plan.md` - httpRequest 工具救援计划
  - `frontend-httprequest-stub-execution-diagnosis.md` - 前端 httpRequest 执行诊断
  - `v2-copilotkit-incomplete-stream-checkpoint-2026-06-14.md` - V2 流式检查点

## CopilotKit 原生工具调用（2026-06 重构，2026-06-11 修正）

**架构变化**：将"http-request 代码块解析"重构为 CopilotKit 原生 `useCopilotAction` 工具调用机制。

### 修正后的双 httpRequest 工具架构（2026-06-11）

**⚠️ 重要修正**：之前的版本错误地从 AG-UI 后端移除了 httpRequest，导致公开 API（如 GET /api/products）无工具可用、LLM 卡死。**正确架构**：

| 工具 | 位置 | 适用 API | 参数类型 | JWT 注入 |
|------|------|---------|---------|---------|
| **后端 httpRequest** | Spring AI 工具回调（Java） | 公开 API | Map 对象 | ❌ |
| **前端 httpRequest** | CopilotKit `useCopilotAction`（浏览器） | 受保护 API | JSON 字符串 | ✅（从 localStorage） |

**LLM 路由决策**（prompt 中明确写出）：
- 接口能否匿名访问？→ 是：后端；否：前端
- 是否写操作？→ 前端（带确认 UI）
- 是否查询当前用户数据？→ 前端

### 前端工具注册
- **新文件**：`frontend/hooks/useHttpRequestTool.tsx`
- **注册方式**：`useCopilotAction({ name: "httpRequest", renderAndWaitForResponse: ... })`
- **行为**：
  - GET 请求自动执行（无确认 UI）
  - POST/PUT/DELETE/PATCH 显示确认对话框，用户确认后 `respond()` 返回结果
  - 从 `localStorage.getItem('auth_token')` 读取 token
  - 相对路径拼接 `NEXT_PUBLIC_JAVA_BACKEND_URL`（默认 `http://localhost:8080`）

### 集成方式
```tsx
<HttpRequestToolProvider>  // 调用 useHttpRequestTool()
  <CopilotPopup ... />
</HttpRequestToolProvider>
```

### 后端配合
- **`mode-rules.template` 规则 6-11 已重写**：明确双 httpRequest 工具路由规则（公开 vs 受保护）
- **`enterprise-agent/system-prompt.template` 已重写**：添加"API 路由决策规则"
- **`SkillTools.httpRequest` @Tool 描述已更新**：明确标注"后端版本，仅限公开 API"
- **`AgUiConfig` 改回注入 `SkillTools`**（不是 `SkillCoreTools`）：注册完整 4 工具（loadSkill, readSkillReference, httpRequest, buildHttpRequest）

### SkillCoreTools（保留，备用）
- 旧 AG-UI 专用版本（只含 loadSkill + readSkillReference）
- **目前不再使用**——AG-UI 模式使用 SkillTools（含 httpRequest）
- 保留供未来需要"无 HTTP 工具"模式时使用

### 已删除的旧实现
- ❌ `frontend/components/ConfirmDialogContainer.tsx`（296 行）
- ❌ `page.tsx` 中的 `extractHttpRequestMeta`、`CustomAssistantMessage`、`confirmedRequests`、`getRequestKey`
- ❌ `CopilotAssistantMessage.tsx` 中的 `[CONFIRM_REQUIRED]` 前缀清理（现已简化为透传组件）

### 代码净变化
- 重复实现 `extractHttpRequestMeta` 从 2 处 → 0 处
- 单一 `useHttpRequestTool` 定义
- 代码行数 -179（-22.6%）
- 消除了 module-level `confirmStateCache` workaround

### 验证结果

**2026-06-15（全流程 E2E PASS）**：
- ✅ Playwright 浏览器测试"有什么商品可以买？" → LLM 调用 httpRequest(GET /api/products) → 显示 5 个商品表格
- ✅ Playwright 浏览器测试"把 iPhone 15 加入购物车" → LLM 调用 httpRequest(POST /api/products/cart) → 成功加入购物车
- ✅ LLM 自动修正错误技能名：使用 "shopbot" 失败后自动切换到 "search-products"
- ✅ 修复 Spring AI 1.1.2 JSON 参数反序列化 bug（JsonArgToolCallback）
- ✅ 修复 ToolCallArgsEvent 未收集到 deferredEvents 的问题

**2026-06-11（修正后，E2E PASS）**：
- ✅ curl `/api/agui` 发送"我可以买什么商品？" → LLM 调后端 httpRequest → 返回 5 个商品（iPhone 15, MacBook Air M3, MatePad Pro, Sony WH-1000XM5, 小米电视）
- ✅ Playwright 真实浏览器测试 → 2 秒内响应、5 秒检测到工具调用、0 console errors
- ✅ JWT 透传测试（`test-agui-jwt-full.sh`）→ JWT 正确流到 boundedElastic 线程
- ✅ 截图证据：`e2e-screenshots/quick-03-final-viewport.png`

**2026-06-02（重构初版）**：
- ✅ TypeScript 编译通过（`npx tsc --noEmit`）
- ✅ 前端页面正常渲染（http://localhost:4000/）
- ✅ 后端服务响应（http://localhost:8080/）
- ✅ `httpRequest` 工具正确注册到 CopilotKit 并发送到后端
- ✅ 工具描述和参数 Schema 正确（method/url/params/body）
- ⚠️ 旧版 LLM 响应被 `RUN_ERROR "terminated"` 中断（已通过移除 SkillCoreTools 改用 SkillTools 解决）
