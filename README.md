# Spring AI Skills Demo

一个 Spring Boot 示例项目，展示如何使用 **Spring AI Skills 渐进式披露**机制构建 AI Agent。

核心思路：LLM 初始只看到简短的技能目录（Level 1 元数据），需要时再调用 `loadSkill` 按需加载完整 API 指令（Level 2），对于复杂的分层 Skill（如 OpenAPI 规范生成的），可进一步调用 `readSkillReference` 按需读取具体操作/资源文档（Level 3），相比一次性注入完整规范可减少 60-90% 的 token 消耗。

## 🐳 Docker 部署（推荐）

项目提供了完整的 Docker 支持，使用多阶段构建优化镜像大小，支持两种部署模式。

### 前置要求

- Docker 20.10+
- Docker Compose 2.0+
- OpenAI 兼容 API Key

### 快速开始

1. **克隆项目并进入目录**

```bash
cd spring-ai-skills-demo
```

2. **配置环境变量**

复制示例环境文件并编辑：

```bash
cp .env.example .env
```

编辑 `.env` 文件，配置你的 API 密钥：

```bash
# 必需配置
OPENAI_API_KEY=your-api-key-here
OPENAI_BASE_URL=https://api.deepseek.com  # 或 https://api.openai.com
OPENAI_MODEL=deepseek-chat                # 或 gpt-4o

# 可选配置
SILICONFLOW_API_KEY=your-siliconflow-key  # 用于向量记忆功能
```

3. **使用 Docker Compose 启动**

```bash
# 构建并启动服务
docker-compose up -d

# 查看日志
docker-compose logs -f app

# 等待服务启动完成（约 30-60 秒）
```

4. **访问应用**

| 功能 | URL |
|------|-----|
| 聊天界面 | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| H2 控制台 | http://localhost:8080/h2-console |

### Docker 常用命令

```bash
# 停止服务
docker-compose down

# 停止并删除数据卷（会清空 H2 数据库）
docker-compose down -v

# 重新构建镜像
docker-compose up -d --build

# 查看容器状态
docker-compose ps

# 进入容器内部
docker-compose exec app sh

# 查看应用日志
docker-compose logs -f app
```

### 仅使用 Dockerfile 部署

如果你不想使用 Docker Compose，也可以直接使用 Dockerfile：

```bash
# 构建镜像
docker build -t spring-ai-skills-demo .

# 运行容器
docker run -d \
  --name spring-ai-skills-demo \
  -p 8080:8080 \
  -e OPENAI_API_KEY=your-api-key \
  -e OPENAI_BASE_URL=https://api.deepseek.com \
  -e OPENAI_MODEL=deepseek-chat \
  -v $(pwd)/data:/app/data \
  --restart unless-stopped \
  spring-ai-skills-demo
```

示例（连接宿主机的 PostgreSQL 数据库数据库，映射到本机的 8082 端口）：

```bash
docker run -d \
  --name spring-ai-skills-demo \
  -p 8082:8080 \
  -e OPENAI_API_KEY=sk-xxx \
  -e OPENAI_BASE_URL=https://api.deepseek.com \
  -e OPENAI_MODEL=deepseek-chat \
  -e SILICONFLOW_API_KEY=sk-xxx \
  -e SILICONFLOW_MODEL=BAAI/bge-m3 \
  -e SILICONFLOW_DIMENSIONS=1024 \
  -e SILICONFLOW_URL=https://api.siliconflow.cn \
  -e SPRING_PROFILES_ACTIVE=postgresql \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/spring-ai-skills-demo \
  -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=123456 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=123456 \
  -v $(pwd)/data:/app/data \
  --add-host=host.docker.internal:host-gateway \
  --restart unless-stopped \
  spring-ai-skills-demo
```

### PostgreSQL 模式部署

对于生产环境，建议使用 PostgreSQL 数据库替代 H2：

1. **编辑 `docker-compose.yml`**，取消 PostgreSQL 相关配置的注释

2. **在 `.env` 中添加 PostgreSQL 配置**：

```bash
SPRING_PROFILES_ACTIVE=postgresql
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your-secure-password
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/spring-ai-skills-demo
```

3. **启动服务**：

```bash
docker-compose up -d
```

### 数据持久化

- **H2 模式**：数据存储在 `./data` 目录，通过 volume 挂载到容器
- **PostgreSQL 模式**：数据存储在 Docker volume `postgres_data` 中

### 生产环境建议

1. **使用 PostgreSQL 模式**：H2 适合开发和测试，生产环境请使用 PostgreSQL
2. **配置健康检查**：Dockerfile 已内置健康检查，可通过 `docker ps` 查看状态
3. **限制资源使用**：在 `docker-compose.yml` 中添加资源限制：

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '1'
          memory: 1G
```

4. **使用反向代理**：生产环境建议配合 Nginx 或 Traefik 使用

---

## 功能特性

- **渐进式披露 Skills** - 三级加载机制：目录 → 技能文档 → 参考文件
- **分层 Skill 结构** - 支持 OpenAPI 规范生成的复杂技能
- **PetStore Mock 后端** - 完整的 Swagger PetStore API 示例实现
- **会话记忆系统** - 基于 JDBC 的持久化对话记忆，支持多会话隔离
- **🆕 语义记忆自动注入** - 基于 VectorStoreChatMemoryAdvisor，自动将相关历史对话注入到上下文
- **确认模式** - 变更操作需用户手动确认后才执行
- **OkHttp 重试机制** - 处理 LLM API 的间歇性网络问题
- **🆕 CopilotKit 前端集成** - 现代化的 Next.js 15 + React 19 前端，支持 AG-UI 协议
- **🆕 双存储后端支持** - 默认 H2 文件数据库 + SimpleVectorStore，可切换为 PostgreSQL + PgVectorStore
- **🆕 多模态输入支持** - 支持图片识别和语音转写，可同时或单独使用
- **🆕 SSE 流式响应** - 支持 Server-Sent Events 流式输出，实时显示 AI 生成内容
- **🆕 前端流式切换** - 测试页面右上角 📡 开关，一键切换同步/流式打字机效果（2026-04-11）

## 聊天端点概览

项目提供多个聊天端点，支持不同场景需求：

| 端点 | 方法 | Content-Type | 流式 | 说明 |
|------|------|--------------|------|------|
| `/api/chat` | POST | `application/json` | ❌ | 纯文本同步聊天 |
| `/api/chat/stream` | POST | `application/json` | ✅ | 纯文本流式聊天 |
| `/api/chat` | POST | `multipart/form-data` | ❌ | 多模态同步聊天（图片+语音） |
| `/api/chat/multimodal/stream` | POST | `multipart/form-data` | ✅ | 多模态流式聊天（图片+语音） |
| `/api/transcribe/stream` | POST | `multipart/form-data` | ✅ | **纯语音流式转写**（2026-04-11 新增） |

### 端点对比

| 特性 | 文本聊天 | 多模态聊天 | 流式版本 |
|------|---------|-----------|---------|
| 端点 | `POST /api/chat` | `POST /api/chat` (multipart) | `POST /api/chat/stream` |
| Content-Type | `application/json` | `multipart/form-data` | 同左列 |
| 文本输入 | ✅ | ✅ | ✅ |
| 图片上传 | ❌ | ✅ | ✅ |
| 语音上传 | ❌ | ✅ | ✅ |
| Skills 工具调用 | ✅ | ✅ | ✅ |
| RAG 知识库 | ✅ | ✅ | ✅ |
| 会话记忆 | ✅ | ✅ | ✅ |
| 确认模式 | ✅ | ✅ | ✅ |
| **SSE 流式输出** | ❌ | ❌ | ✅ |

### 端点 1：纯文本聊天（向后兼容）

适合纯文本对话场景，与传统 Web 界面配合使用。

**请求示例：**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你好，请回复 OK","conversationId":"test-001"}' \
  --max-time 60
```

**响应：**

```json
{"response":"OK"}
```

### 端点 2：多模态聊天（图片 + 语音）

适合需要图片识别或语音转写的场景，支持同时上传图片和音频。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| query | string | 否 | 文本问题 |
| conversationId | string | 否 | 会话 ID（默认 "default"） |
| image | file | 否 | 图片文件（PNG, JPG, JPEG, GIF, WebP） |
| audio | file | 否 | 音频文件（WAV, MP3, M4A） |

**图片识别示例：**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -F "query=请详细描述这张图片的内容" \
  -F "conversationId=test-image-001" \
  -F "image=@/path/to/image.png;type=image/png" \
  --max-time 180
```

**语音识别示例：**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -F "query=请转写这段音频的内容" \
  -F "conversationId=test-audio-001" \
  -F "audio=@/path/to/audio.wav;type=audio/wav" \
  --max-time 300
```

**同时上传图片和语音：**

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -F "query=这张图片里的人说了什么？" \
  -F "conversationId=test-multimodal-001" \
  -F "image=@/path/to/image.png;type=image/png" \
  -F "audio=@/path/to/audio.wav;type=audio/wav" \
  --max-time 300
```

**响应格式（两种端点统一）：**

```json
{"answer":"AI 的回复内容..."}
```

### 端点 3：纯文本流式聊天（SSE）

适合需要实时显示 AI 生成内容的场景，使用 Server-Sent Events 协议。

**请求示例：**

```bash
curl -s -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"content":"你好，介绍一下你自己","conversationId":"test-stream-001"}' \
  --max-time 60
```

**SSE 响应格式（OpenAI 兼容格式）：**

```json
data:{"choices":[{"delta":{"content":"你"}}]}
data:{"choices":[{"delta":{"content":"好"}}]}
data:{"choices":[{"delta":{"content":"！"}}]}
...
data:[DONE]
```

### 端点 4：多模态流式聊天（SSE）

适合需要图片识别或语音转写的流式场景。

**图片流式识别示例：**

```bash
curl -s -N -X POST http://localhost:8080/api/chat/multimodal/stream \
  -F "query=这张图片里有什么?" \
  -F "conversationId=test-multimodal-stream" \
  -F "image=@/path/to/image.jpg;type=image/jpeg" \
  --max-time 180
```

**响应特点：**
- 使用 OpenAI SSE 格式，兼容主流 AI 前端库
- `type` 字段区分内容来源：`"vision"`（视觉模型） 或 `"content"`（LLM）
- `delta.content` 包含实际的 token 内容
- 流结束发送 `[DONE]` 标记

**多模态 SSE 响应格式示例：**

```json
data:{"type":"vision","choices":[{"delta":{"content":"这"}}]}
data:{"type":"vision","choices":[{"delta":{"content":"张"}}]}
data:{"type":"vision","choices":[{"delta":{"content":"图"}}]}
...
data:{"type":"content","choices":[{"delta":{"content":"根"}}]}
data:{"type":"content","choices":[{"delta":{"content":"据"}}]}
...
data:[DONE]
```

### 图片输入处理逻辑

图片上传时，系统会先检测该会话是否有历史消息，然后选择不同的处理流程：

**无会话历史（冷启动）**：
```
用户上传图片 + 附带文本(query)
    ↓
直接使用默认视觉提示词（如果 query 存在则添加 hint）
    ↓
调用视觉模型 → 流式返回 type="vision"
    ↓
组合 "【图片内容】描述 + 【用户输入】query"
    ↓
调用语言模型 → 流式返回 type="content"
```

**有会话历史（提示词增强）**：
```
用户上传图片 + 附带文本(query)
    ↓
检测到会话有历史消息
    ↓
[第一步] 调用语言模型生成情境化视觉提示词
         输入：用户 query + 会话历史摘要（最近6条）+ 默认视觉提示词
         输出：生成的提示词（通过 SSE type="prompt" 返回）
    ↓
[第二步] 使用生成的视觉提示词调用视觉模型
    ↓
[第三步] 组合 "【图片内容】描述 + 【用户输入】query"
    ↓
[第四步] 调用语言模型 → 流式返回 type="content"
```

**完整处理流程（含音频）**：
```
用户上传（图片/音频）
       │
       ▼
MultimodalAgentService.streamChat()
       │
       ├─── 历史检测 ──→ ConversationHistoryService.hasHistory()
       │
       ├─── 图片处理 ──→ 视觉模型 (doubao-1-5-vision)
       │                    │
       │                    ├── 有历史 → 提示词增强流程
       │                    └── 无历史 → 默认提示词流程
       │
       ├─── 音频处理 ──→ TranscriptionModel (glm-asr-2512)
       │                    │
       │                    └── 流式返回 type="transcribed"
       │
       ▼
合并所有输入 → AgentService.streamChat()
       │
       ▼
流式返回 type="content"
```

**SSE 事件类型**：
| type | 来源 | 说明 |
|------|------|------|
| `vision` | 视觉模型 | 图片描述内容 |
| `content` | 语言模型 | AI 最终回复 |
| `transcribed` | 语音转写 | 音频转文字 |
| `prompt` | 提示词生成 | 仅在有会话历史时出现（用于调试） |

### 配置要求

多模态功能依赖以下外部服务（需在 `.env` 中配置）：

```bash
# 视觉模型配置（火山引擎 ARK）- 用于图片识别
VISION_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
VISION_API_KEY=your-ark-api-key
VISION_MODEL=doubao-1-5-vision-pro-32k-250115

# 语音转写配置（智谱 GLM-ASR）- 用于语音转文字
TRANSCRIPTION_BASE_URL=https://open.bigmodel.cn/api/paas
TRANSCRIPTION_API_KEY=your-glm-api-key
TRANSCRIPTION_MODEL=glm-asr-2512
```

**说明：**
- 图片识别必需配置视觉模型
- 语音转写为可选（未配置时，音频会被忽略并提示用户）
- 文本聊天（`/api/chat` JSON 格式）无需额外配置

### 测试脚本

项目提供两个测试脚本，支持快速验证功能：

**1. 多模态测试脚本（同步版本）：**

```bash
# 测试所有功能（需在 .env 中配置 TEST_IMAGE_PATH 和 TEST_AUDIO_PATH）
./test-multimodal.sh

# 或命令行指定文件
./test-multimodal.sh --image /path/to/test.png --audio /path/to/test.wav

# 查看帮助
./test-multimodal.sh --help
```

**2. 流式聊天测试脚本（新增）：**

```bash
# 测试纯文本流式聊天
./test-streaming.sh --text

# 测试带图片的多模态流式聊天
./test-streaming.sh --image

# 测试带语音的多模态流式聊天
./test-streaming.sh --audio

# 测试所有流式端点
./test-streaming.sh --all
```

**注意：** 测试文件路径需要在 `.env` 中配置：

```bash
# E2E 测试配置
TEST_IMAGE_PATH=/path/to/your/test-image.jpg
TEST_AUDIO_PATH=/path/to/your/test-audio.mp3
```

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.4.2 |
| Spring AI | 1.1.0 |
| Java | 17+ |
| springdoc-openapi | 2.6.0 |
| OkHttp | 4.12.0 |
| H2 Database | 2.3.232 |
| PostgreSQL | 16+ (可选) |
| PgVector | 0.7+ (可选) |
| Maven | 3.8+ |

兼容任何 OpenAI API 兼容的 LLM 服务（OpenAI、DeepSeek 等）。

## 架构

### 双前端架构

项目现在支持两种前端方式：

```
┌─────────────────────────────────────────────────────────────┐
│  方式 1: 传统 Web 界面（原有）                                 │
│  http://localhost:8080                                       │
│  ├── Thymeleaf 模板 + 静态资源                                │
│  └── ✅ 流式/同步切换开关（2026-04-11 新增）                   │
│      └── 右上角 📡 开关，一键切换 SSE 流式打字机效果            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  方式 2: CopilotKit 现代前端（新增）🆕                         │
│  http://localhost:3001                                       │
│  ├── Next.js 15 + React 19                                   │
│  ├── CopilotPopup 聊天组件                                    │
│  ├── CopilotRuntime BFF 层                                   │
│  └── HttpAgent 连接到 Java 后端                               │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  Java 后端 (Spring AI)                                       │
│  ├── /api/chat      - 传统聊天端点（旧前端，支持流式切换）       │
│  ├── /api/agui      - AG-UI 协议端点（新前端）🆕               │
│  ├── /api/products  - 商品 REST API                          │
│  └── /api/v3/*      - PetStore Mock API                      │
└─────────────────────────────────────────────────────────────┘
```

### 请求流程（传统前端）

```
用户消息
  │
  ▼
ChatController (POST /api/chat)
  │
  ▼
AgentService.chat()
  ├── 重置已加载技能（无状态）
  │
  ▼
MessageChatMemoryAdvisor
  ├── 从 H2 数据库加载历史消息
  │
  ▼
SkillsAdvisor (CallAdvisor, HIGHEST_PRECEDENCE)
  ├── 注入 System Prompt：
  │   ├── Level 1: 所有技能名称 + 描述（来自 SkillRegistry）
  │   └── Level 2: 当前对话已加载技能的完整指令
  │
  ▼
LLM 决策
  ├── 调用 SkillTools.loadSkill(name)       → 获取技能概述 + 参考文件索引
  ├── 调用 SkillTools.readSkillReference()  → 按需读取具体操作/资源/Schema 文档
  ├── 调用 SkillTools.httpRequest(...)      → 请求 REST API（或返回确认请求）
  │
  ▼
MessageChatMemoryAdvisor
  ├── 将对话保存到 H2 数据库
  │
  ▼
返回结果给用户（确认模式下，变更操作返回确认请求由前端执行）
```

### 三级渐进式披露

| Level | 工具 | 内容 | 示例 |
|-------|------|------|------|
| 1 | System Prompt | 技能目录（名称+描述） | `swagger-petstore-openapi-3-0: Pet Store API` |
| 2 | `loadSkill` | 技能概述 + 参考文件索引 | SKILL.md（含 resources/operations/schemas 目录结构） |
| 3 | `readSkillReference` | 具体操作/资源文档 | `operations/findPetsByStatus.md`, `schemas/Pet.md` |

### 核心组件

| 组件 | 职责 |
|------|------|
| `agent/SkillRegistry` | 启动时读取 `resources/skills/*/SKILL.md`，解析 YAML frontmatter + Markdown body |
| `agent/SkillTools` | `@Tool` 方法：`loadSkill`（加载技能）、`readSkillReference`（读取参考文件）、`httpRequest`（HTTP 调用） |
| `agent/SkillsAdvisor` | `CallAdvisor`，构建含 Level 1 目录 + Level 2 已加载内容的 System Prompt |
| `service/AgentService` | 编排 `ChatClient` + Advisor + Tools + ChatMemory |
| `service/ProductService` | 内存商品目录和购物车（无数据库），预置 5 条示例数据 |
| `petstore/*` | PetStore Mock 后端（Controller + Service + Model） |
| `config/SpringAiConfig` | OkHttp 配置，含响应体缓冲和 EOFException 重试机制 |

### 会话记忆系统

基于 Spring AI 的双层记忆系统，支持双存储后端切换：

#### 存储后端配置

| 配置 | 默认配置 | PostgreSQL 配置 |
|------|---------|----------------|
| 激活方式 | 无需设置（默认） | `SPRING_PROFILES_ACTIVE=postgresql` |
| Chat Memory | H2 文件数据库 | PostgreSQL 数据库 |
| Vector Store | SimpleVectorStore | PgVectorStore |
| 数据文件 | `./data/chat-memory.mv.db` | PostgreSQL 服务器 |
| 向量存储 | `./data/vector-store.json` | `vector_store` 表 |

#### 1. 短期记忆 - MessageChatMemoryAdvisor

基于 `JdbcChatMemoryRepository` 实现：

- **默认配置**：H2 文件数据库 `./data/chat-memory.mv.db`
- **PostgreSQL 配置**：PostgreSQL 数据库（`SPRING_AI_CHAT_MEMORY` 表）
- **消息窗口**：保留最近 20 条消息，防止上下文过长
- **多会话隔离**：通过 `conversationId` 区分不同会话

```java
// AgentService.java
ChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(jdbcChatMemoryRepository)
        .maxMessages(20)
        .build();
```

#### 2. 长期记忆 - VectorStoreChatMemoryAdvisor 🆕

基于向量存储的语义记忆自动注入机制，实现跨会话的上下文增强：

- **自动注入**：每次对话自动检索相关历史记忆，无需手动调用
- **语义匹配**：通过向量相似度在历史对话中查找相关内容
- **跨会话关联**：同一 conversationId 的对话会被向量化和存储
- **持久化**：向量数据存储在 `./data/vector-store.json`
- **嵌入模型**：使用 SiliconFlow API（BAAI/bge-m3 模型，1024 维）

```java
// AgentService.java
VectorStoreChatMemoryAdvisor vectorStoreChatMemoryAdvisor =
    VectorStoreChatMemoryAdvisor.builder(vectorStore)
        .build();

this.chatClient = builder
    .defaultAdvisors(
        skillsAdvisor,
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        vectorStoreChatMemoryAdvisor  // 语义记忆自动注入
    )
    .defaultTools(skillTools)
    .build();
```

**工作原理**：
1. 用户发送消息时，`VectorStoreChatMemoryAdvisor.before()` 自动在向量存储中搜索相似的历史对话
2. 搜索结果通过 `{long_term_memory}` 占位符自动注入到 Prompt 中
3. LLM 在生成回复时能看到相关的历史记忆上下文
4. `VectorStoreChatMemoryAdvisor.after()` 将当前对话内容存储到向量数据库

**向量存储后端**：
- **默认配置**：SimpleVectorStore，持久化到 `./data/vector-store.json`
- **PostgreSQL 配置**：PgVectorStore，持久化到 PostgreSQL `vector_store` 表（HNSW 索引）

### Skills 格式

每个技能位于 `src/main/resources/skills/<skill-name>/SKILL.md`：

#### 简单技能（单文件）

```yaml
---
name: search-products
description: 搜索商品目录，支持关键词、分类、价格范围过滤
version: 1.0
links:
  - name: get-product-detail
    description: 获取单个商品的详细信息
---

# 商品搜索技能
## API 端点
GET /api/products
...
```

#### 分层技能（OpenAPI 生成）

```
skills/swagger-petstore-openapi-3-0/
├── SKILL.md                    # Level 2: 技能概述 + 目录索引
└── references/                 # Level 3: 参考文件
    ├── authentication.md       # 认证说明
    ├── resources/              # 资源文档
    │   ├── pet.md
    │   ├── store.md
    │   └── user.md
    ├── operations/             # 操作文档（19个）
    │   ├── findPetsByStatus.md
    │   ├── getPetById.md
    │   └── ...
    └── schemas/                # Schema 文档（6个）
        ├── Pet/Pet.md
        ├── Order/Order.md
        └── ...
```

LLM 工作流程：
1. 看到 `swagger-petstore-openapi-3-0` 目录条目
2. 调用 `loadSkill("swagger-petstore-openapi-3-0")` 获取概述
3. 根据需要调用 `readSkillReference("swagger-petstore-openapi-3-0", "operations/findPetsByStatus.md")` 获取详细文档
4. 调用 `httpRequest` 执行 API 操作

技能通过 `links` 形成有向图，支持链式发现：`search-products → get-product-detail → add-to-cart → checkout`。

## 快速开始

### 前置要求

- JDK 17+
- Maven 3.8+
- OpenAI 兼容 API Key（OpenAI、DeepSeek 等）

### 配置

在项目根目录创建 `.env` 文件（已在 `.gitignore` 中排除）：

```bash
OPENAI_API_KEY=your-api-key
OPENAI_BASE_URL=https://api.deepseek.com   # 或 https://api.openai.com
OPENAI_MODEL=deepseek-chat                  # 或 gpt-4o

# PostgreSQL 数据库配置（可选，用于 PostgreSQL profile）
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/spring-ai-skills-demo
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456
```

### 构建与运行

```bash
# 加载环境变量
set -a && source .env && set +a
# set -a 用于开启allexport模式后加载.env，确保变量导出给子进程（Spring Boot应用）
# source .env 加载环境变量
# set +a 关闭allexport模式
#
# 另一种常见写法：
# export $(cat .env | grep -v '^#' | xargs)

# 构建
mvn clean package -DskipTests

# 如果需要重启服务，可以这样精准杀死在某个端口上运行的服务端进程：
# lsof -ti:8080 -sTCP:LISTEN | xargs -r kill -9 2>/dev/null; echo "Killed server on port 8080"; sleep 1; lsof -ti:8080 || echo "Port 8080 is free"

# 运行（默认配置：H2 + SimpleVectorStore）
mvn spring-boot:run -DskipTests

# 运行（PostgreSQL 配置：PostgreSQL + PgVectorStore）
SPRING_PROFILES_ACTIVE=postgresql mvn spring-boot:run -DskipTests
```

### 访问地址

| 功能 | URL |
|------|-----|
| 聊天界面（传统）| http://localhost:8080 |
| **CopilotKit 前端** 🆕 | http://localhost:3001 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| H2 控制台 | http://localhost:8080/h2-console |

H2 控制台连接信息：
- JDBC URL: `jdbc:h2:file:./data/chat-memory`
- 用户名: `sa`
- 密码: (空)

### 运行 CopilotKit 前端 🆕

前端项目位于 `frontend/` 目录：

```bash
# 安装依赖
cd frontend
npm install

# 运行开发服务器
npm run dev

# 构建生产版本
npm run build
npm start
```

前端环境变量配置（`frontend/.env.local`）：
```bash
JAVA_BACKEND_URL=http://localhost:8080
```

**关键特性**：
- ✅ 实时流式响应（SSE）
- ✅ 会话记忆（与 Java 后端共享）
- ✅ 工具调用（loadSkill + httpRequest）
- ✅ OpenAPI 技能解析（宠物商店功能已验证）
- ✅ 现代化 UI（Tailwind CSS）
- ✅ 深色模式支持

**技术栈**：
- Next.js 15.1.6
- React 19
- CopilotKit 1.3.14
- @ag-ui/client 0.0.47
- Tailwind CSS 3.4.1

## 测试验证

以下是经过验证的测试步骤，可完整复现。

### 1. REST API 测试

启动应用后，验证商品 API 是否正常：

```bash
# 查询全部商品
curl -s http://localhost:8080/api/products
```

预期返回 5 条商品数据（iPhone 15、华为 MatePad Pro、Sony WH-1000XM5、小米电视 65寸、MacBook Air M3）。

```bash
# 按关键词搜索
curl -s "http://localhost:8080/api/products?keyword=耳机&priceMax=3000"
```

预期仅返回 Sony WH-1000XM5。

```bash
# 查看商品详情
curl -s http://localhost:8080/api/products/3
```

```bash
# 加入购物车
curl -s -X POST "http://localhost:8080/api/products/cart?userId=1&productId=3"
```

预期返回 `{"success":true,"message":"已添加到购物车","cartSize":1}`。

```bash
# 结算
curl -s -X POST "http://localhost:8080/api/products/checkout?userId=1"
```

预期返回 `{"success":true,"message":"订单已提交","totalAmount":2499.0,"itemCount":1}`。

### 2. Agent 聊天测试

测试完整的 Agent 流程（LLM → loadSkill → httpRequest → 回复）：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"帮我找一款3000元以下的耳机"}' \
  --max-time 60
```

预期行为：
1. Agent 调用 `loadSkill("search-products")` 加载搜索技能
2. Agent 调用 `httpRequest(GET /api/products?priceMax=3000)` 或类似请求
3. 返回包含 Sony WH-1000XM5（2499 元）的推荐回复

```bash
# 测试完整购物流程
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"把 Sony WH-1000XM5 加入购物车并结算"}' \
  --max-time 60
```

预期行为：Agent 会依次加载 `add-to-cart` 和 `checkout` 技能，完成加购和结算。

### 3. 会话记忆测试

测试 Agent 是否能记住上下文信息：

```bash
# 第一次对话：告诉 Agent 你的名字
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你好，我叫张三", "conversationId": "memory-test-001"}' \
  --max-time 60
```

```bash
# 第二次对话：询问 Agent 是否记得你的名字
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你记得我叫什么名字吗？", "conversationId": "memory-test-001"}' \
  --max-time 60
```

预期行为：Agent 应该回复记得你叫"张三"。

验证数据库持久化：
```bash
# 检查 H2 数据库文件
ls -la ./data/chat-memory.mv.db
```

预期结果：数据库文件存在且有内容（约 40KB+）。

多会话隔离测试：
```bash
# 使用不同的 conversationId，Agent 不会记住之前的信息
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"你记得我叫什么名字吗？", "conversationId": "memory-test-002"}' \
  --max-time 60
```

预期行为：Agent 会表示不记得（因为这是新会话）。

### 3.1 语义记忆自动注入测试（VectorStoreChatMemoryAdvisor）🆕

测试语义记忆自动注入功能：

```bash
# 第一次对话：告诉 Agent 你想要的商品类型
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"帮我找一款3000元以下的耳机", "conversationId": "vector-test-001"}' \
  --max-time 60
```

```bash
# 第二次对话：使用语义相似的问题询问（Agent 会通过向量搜索找到之前的对话）
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"刚才那个耳机具体价格是多少？", "conversationId": "vector-test-001"}' \
  --max-time 60
```

预期行为：Agent 能理解"刚才那个耳机"指的是第一次对话中推荐的耳机，并能回答具体价格（2499元）。

验证向量存储文件：
```bash
# 检查向量存储文件（应用关闭时自动创建）
ls -la ./data/vector-store.json
```

预期结果：向量存储文件存在且有内容。

自动注入原理：
- 对话内容被转换为 1024 维向量（BAAI/bge-m3 模型）
- 新消息会自动在向量数据库中搜索相似的历史对话
- 搜索结果通过 `{long_term_memory}` 占位符自动注入到 Prompt
- 无需手动调用，Advisor 隐式自动运行

### 3.2 多路径知识库测试（RAG）🆕

测试从多个路径加载知识库文档，支持类路径和文件系统路径混合配置：

**1. 创建文件系统知识库目录**

```bash
mkdir -p /tmp/test-knowledge-base
```

创建测试文档 `/tmp/test-knowledge-base/store-hours.md`：

```markdown
# 商店营业时间

## 正常营业时间

- **周一至周五**: 上午 9:00 - 晚上 9:00
- **周六至周日**: 上午 10:00 - 晚上 10:00
```

创建测试文档 `/tmp/test-knowledge-base/return-policy.md`：

```markdown
# 退换货政策

## 退货条件

1. **时间限制**: 自购买之日起 30 天内可申请退货
2. **商品状态**: 商品必须保持原包装完好
```

**2. 配置多路径知识库**

通过环境变量配置多个知识库路径（逗号分隔）：

```bash
export KNOWLEDGE_BASE_PATHS="classpath:knowledge-base/*.md,file:/tmp/test-knowledge-base/*.md"
```

或在 `.env` 文件中添加：

```bash
KNOWLEDGE_BASE_PATHS=classpath:knowledge-base/*.md,file:/tmp/test-knowledge-base/*.md
```

路径格式说明：
- `classpath:knowledge-base/*.md` - 加载类路径下的知识库文件
- `file:/tmp/test-knowledge-base/*.md` - 加载文件系统的知识库文件
- 支持通配符 `*` 和 `**`（递归匹配子目录）

**3. 启动应用并测试 RAG**

```bash
export $(cat .env | grep -v '^#' | xargs)
export KNOWLEDGE_BASE_PATHS="classpath:knowledge-base/*.md,file:/tmp/test-knowledge-base/*.md"
mvn spring-boot:run -DskipTests
```

观察启动日志，确认从两个路径加载了文档：

```
INFO  c.e.d.k.KnowledgeBaseInitializer : 开始加载知识库，配置的路径列表: [classpath:knowledge-base/*.md, file:/tmp/test-knowledge-base/*.md]
INFO  c.e.d.k.KnowledgeBaseInitializer : 从路径 [classpath:knowledge-base/*.md] 加载了 3 个文档
INFO  c.e.d.k.KnowledgeBaseInitializer : 从路径 [file:/tmp/test-knowledge-base/*.md] 加载了 2 个文档
INFO  c.e.d.k.KnowledgeBaseInitializer : 知识库加载完成，共 5 篇文档
```

**4. 测试 RAG 问答**

测试文件系统知识库的问答：

```bash
# 测试商店营业时间（来自文件系统知识库）
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你们的商店周末几点开门？"}'
```

预期回复包含：周六至周日上午 10:00 开门

```bash
# 测试退换货政策（来自文件系统知识库）
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "我买了件衣服想退货，有什么要求？"}'
```

预期回复包含：30 天内可申请退货、保持原包装完好等信息

**5. 配置示例**

| 场景 | 配置值 |
|------|--------|
| 默认（仅类路径） | `classpath:knowledge-base/*.md` |
| 类路径 + 文件系统 | `classpath:knowledge-base/*.md,file:/opt/knowledge/*.md` |
| 多个文件系统路径 | `file:/data/kb/*.md,file:/opt/docs/**/*.md` |
| Docker 环境 | `classpath:knowledge-base/*.md,file:/app/knowledge/*.md` |

### 4. 确认模式测试

以 `confirm-before-mutate=true` 启动应用后，Agent 对变更操作（POST/PUT/DELETE）不会直接执行，而是返回包含 `` ```http-request `` 代码块的确认请求：

```bash
# 以确认模式启动
mvn spring-boot:run -DskipTests '-Dspring-boot.run.arguments=--app.confirm-before-mutate=true'

# 发送加购请求
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"请把商品ID为3的商品加入购物车（用户ID=1）"}' \
  --max-time 90
```

预期行为：
1. Agent 不直接执行加购操作
2. 返回操作说明 + `` ```http-request `` 代码块（含 method、url、params 等元数据）
3. 前端展示说明文本和「确定」/「取消」按钮
4. 用户点击「确定」后，前端根据元数据构造 HTTP 请求并发送到后端 API

### 5. Web 界面测试

在浏览器打开 http://localhost:8080 ，在聊天框中输入自然语言，如：

- "找一款3000元以内的耳机"
- "第一个商品的详细信息"
- "把它加入购物车"
- "帮我结算"

开启确认模式后，加购和结算等变更操作会显示确认按钮，需用户手动确认后才执行。

### 自动化测试

项目包含回归测试脚本，覆盖 REST API、Agent 聊天、确认模式（前端执行）等场景：

```bash
# 主测试脚本（购物助手场景）- 21 个测试用例
./test.sh

# PetStore 测试脚本（分层 Skill 场景）- 13 个测试用例
./test-petstore.sh

# 向量存储测试脚本（语义记忆场景）🆕
./test-vector-store-memory.sh
```

测试脚本会自动启动应用、运行全部测试用例，最后输出通过/失败汇总。

### 已验证环境

- macOS + JDK 17+ + Maven 3.9
- DeepSeek API (`deepseek-chat` 模型)
- Spring AI 1.1.0 + Spring Boot 3.4.2

## 项目结构

```
spring-ai-skills-demo/
├── src/main/java/com/example/demo/
│   ├── agent/
│   │   ├── SkillRegistry.java      # 技能注册表
│   │   ├── SkillTools.java         # @Tool 方法
│   │   └── SkillsAdvisor.java      # System Prompt 构建
│   ├── config/
│   │   ├── SpringAiConfig.java     # OkHttp + 重试配置
│   │   ├── AgUiConfig.java         # AG-UI 配置 🆕
│   │   ├── CorsConfig.java         # 跨域配置 🆕
│   │   ├── VectorStoreConfig.java  # 向量存储配置（默认 SimpleVectorStore）
│   │   ├── VectorStorePostgresqlConfig.java  # PgVectorStore 配置 🆕
│   │   ├── EmbeddingModelConfig.java  # 共享嵌入模型配置 🆕
│   │   └── VectorStorePersistenceExecutor.java  # 向量持久化 🆕
│   ├── controller/
│   │   ├── ChatController.java     # 聊天 API
│   │   ├── ProductController.java  # 商品 REST API
│   │   └── AgUiController.java     # AG-UI 端点 🆕
│   ├── petstore/                   # PetStore Mock 后端
│   │   ├── PetController.java
│   │   ├── StoreController.java
│   │   ├── UserController.java
│   │   ├── PetStoreService.java
│   │   └── model/
│   ├── service/
│   │   ├── AgentService.java       # 集成 ChatMemory
│   │   └── ProductService.java
│   └── model/
│
├── src/main/java/com/agui/         # AG-UI SDK 实现 🆕
│   ├── core/                       # 核心接口和事件
│   │   ├── agent/                  # Agent 接口
│   │   ├── event/                  # 事件类型（19个）
│   │   ├── message/                # 消息类型（6种）
│   │   ├── tool/                   # 工具定义
│   │   └── stream/                 # 事件流
│   ├── server/                     # 服务端实现
│   │   ├── spring/                 # Spring 集成
│   │   └── streamer/               # SSE 流输出
│   └── spring/ai/                  # Spring AI 集成
│       └── SpringAIAgent.java      # Agent 实现
│
├── frontend/                       # CopilotKit 前端 🆕
│   ├── app/
│   │   ├── api/copilotkit/
│   │   │   └── route.ts            # BFF 层（HttpAgent）
│   │   ├── layout.tsx              # CopilotKit Provider
│   │   ├── page.tsx                # 主页面
│   │   └── globals.css             # 样式
│   ├── package.json                # 依赖管理
│   ├── next.config.js              # Next.js 配置
│   ├── tailwind.config.js          # Tailwind CSS
│   └── README.md                   # 前端文档
│
├── src/main/resources/
│   ├── skills/
│   │   ├── search-products/SKILL.md
│   │   ├── get-product-detail/SKILL.md
│   │   ├── add-to-cart/SKILL.md
│   │   ├── checkout/SKILL.md
│   │   └── swagger-petstore-openapi-3-0/   # 分层技能示例
│   │       ├── SKILL.md
│   │       └── references/
│   │           ├── operations/
│   │           ├── resources/
│   │           └── schemas/
│   ├── petstore.yaml              # OpenAPI 3.0 规范
│   └── application.yml
│
├── data/                          # H2 数据库文件（自动创建，默认配置）
│   ├── chat-memory.mv.db          # 对话记忆数据库
│   └── chat-memory.trace.db       # 数据库跟踪日志
│
├── application-postgresql.yml      # PostgreSQL 配置文件 🆕
│
├── docs/drafts/                   # 文档草稿 🆕
│   ├── enterprise-agent-frontend-guide-v4.md  # 前端集成指南
│   └── spring-ai-agui-guide.md    # AG-UI 集成指南
│
├── COPILOTKIT_INTEGRATION.md      # CopilotKit 集成文档 🆕
└── TEST_REPORT.md                 # 测试报告 🆕
```

## 升级说明

从 Spring AI 1.0.0-M6 升级到 1.1.0 的主要变更：

| 变更类型 | M6 | 1.1.0 |
|---------|-----|-------|
| Artifact 命名 | `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` |
| Advisor API | `CallAroundAdvisor` | `CallAdvisor` |
| Request/Response | `AdvisedRequest`/`AdvisedResponse` | `ChatClientRequest`/`ChatClientResponse` |
| Chat Memory 包名 | `org.springframework.ai.chat.memory.jdbc` | `org.springframework.ai.chat.memory.repository.jdbc` |
| Chat Memory Starter | 无 | `spring-ai-starter-model-chat-memory-repository-jdbc` |
