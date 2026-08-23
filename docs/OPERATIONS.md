# 操作示例

> **目的**: 提供可以直接复制的 REST、聊天、记忆/RAG、多模态和 Docker 操作示例。
> 端点定义以 [REST 与 SSE API 参考](rest-api.md)、Controller 和运行时 Skill 为准。
> **最后核对**: 2026-08-23

## 使用前提

先按 [开发指南](DEVELOPMENT.md) 启动后端。本文示例默认：

- 后端地址：`http://localhost:8080`
- 当前运行使用非 `postgresql` profile 时，数据目录为 `./data`
- 模型、Embedding、视觉和转写能力均依赖外部服务；没有对应配置时，只能验证路由或得到外部服务错误
- 认证 Token 是 Demo Base64 token，不是 JWT

生成本地环境并加载变量：

```bash
cp .env.example .env
set -a && source .env && set +a
```

如果本机没有 PostgreSQL，显式选择 H2 和 `SimpleVectorStore`：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
```

## REST 冒烟

### 商品查询

```bash
# 全部商品
curl -s http://localhost:8080/api/products

# 关键词和价格过滤
curl -sG http://localhost:8080/api/products \
  --data-urlencode 'keyword=耳机' \
  --data-urlencode 'priceMax=3000'

# 商品详情
curl -s http://localhost:8080/api/products/3
```

### Demo 认证与购物车

登录接口使用内存中的演示用户，例如 `user1/password1`：

```bash
TOKEN=$(
  curl -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"username":"user1","password":"password1"}' |
  sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
)

curl -s http://localhost:8080/api/auth/verify \
  -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:8080/api/products/cart \
  -H "Authorization: Bearer $TOKEN"

curl -s -X POST 'http://localhost:8080/api/products/cart?productId=3' \
  -H "Authorization: Bearer $TOKEN"

curl -s -X POST http://localhost:8080/api/products/checkout \
  -H "Authorization: Bearer $TOKEN"
```

生产环境不要复用这组用户、密码或 Token 设计。需要完整端点列表时，使用 [REST 与 SSE API 参考](rest-api.md) 或 Swagger UI。

## Agent 聊天

普通同步聊天请求的字段是 `content` 和可选的 `conversationId`：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"content":"帮我找一款3000元以下的耳机","conversationId":"demo-search-001"}' \
  --max-time 120
```

一个完整的购物流程会让 Agent 依次加载商品 Skill，再调用相应业务 API。普通 `AgentService` 链路的受保护写操作需要可用认证上下文；AG-UI 链路的写操作由浏览器 `httpRequest` 执行。

### 文本 SSE

```bash
curl -sN -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"content":"介绍一下当前商品目录","conversationId":"demo-stream-001"}' \
  --max-time 120
```

普通聊天 SSE 使用 JSON chunk，完成时发送 `[DONE]`。AG-UI `/api/agui` 使用另一套 AG-UI 事件格式，不能混用。

## 记忆和 RAG

### JDBC 会话记忆

用相同 `conversationId` 发送两次请求，可以观察短期会话记忆：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"content":"你好，我叫张三","conversationId":"memory-demo-001"}'

curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"content":"你记得我叫什么名字吗？","conversationId":"memory-demo-001"}'
```

非 PostgreSQL profile 的 JDBC 数据位于 `./data/chat-memory.mv.db`。PostgreSQL profile 使用数据库中的 Chat Memory schema。

### 语义记忆

语义记忆和知识库都依赖 Embedding。运行专项脚本前配置有效的 `SILICONFLOW_API_KEY`：

```bash
./test-vector-store-memory.sh
```

非 PostgreSQL profile 关闭应用时会把向量保存到 `./data/vector-store.json`。PostgreSQL profile 使用 `PgVectorStore` 的 `vector_store` 表。

### 知识库路径

默认加载 `classpath:knowledge-base/*.md`。可以追加文件系统目录：

```bash
mkdir -p /tmp/test-knowledge-base
printf '%s\n' '# 商店营业时间' '- 周末上午 10:00 开门。' \
  > /tmp/test-knowledge-base/store-hours.md

SPRING_PROFILES_ACTIVE=local \
KNOWLEDGE_BASE_PATHS="classpath:knowledge-base/*.md,file:/tmp/test-knowledge-base/*.md" \
mvn spring-boot:run -DskipTests
```

上面的命令显式选择非 `postgresql` profile，适合没有 PostgreSQL 的本地验证。若当前 shell 已经有后端占用 8080，先停止旧进程或选择其他端口；不要在同一端口直接启动第二个实例。若你已经准备好 PostgreSQL，也可以去掉 `SPRING_PROFILES_ACTIVE=local`，但必须确认当前 profile、JDBC 凭证、`vector` 扩展和 Embedding 维度匹配。仅执行 `export KNOWLEDGE_BASE_PATHS=...` 后运行 `mvn spring-boot:run` 会继承根配置的 `postgresql` profile，仍可能尝试连接 PostgreSQL。

应用启动日志会报告各路径加载的文档数量。RAG 端到端验证：

```bash
./test-rag-knowledge-base.sh
```

知识库加载器支持 `classpath:` 和 `file:` 资源模式；不要把 `knowledge-extra/` 视为默认运行时知识库。

当前知识库问答只接入普通 `/api/chat` 链路；`/api/agui` 尚未注册 `QuestionAnswerAdvisor`。验证公司条款时优先使用本节的普通聊天请求，不要用 CopilotKit 界面是否命中知识库来判断 RAG 是否配置成功。完整实现和扩展步骤见 [知识库与运行时 Skills](knowledge-and-skills.md)。

## 多模态和转写

### 同步多模态聊天

图片字段为 `image`，音频字段为 `audio`：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -F 'query=请描述图片内容' \
  -F 'conversationId=multimodal-demo-001' \
  -F 'image=@/path/to/image.png;type=image/png' \
  --max-time 180
```

音频转写需要配置 `TRANSCRIPTION_*`：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -F 'query=请转写这段音频' \
  -F 'conversationId=audio-demo-001' \
  -F 'audio=@/path/to/audio.wav;type=audio/wav' \
  --max-time 300
```

### 多模态 SSE 与纯转写 SSE

```bash
curl -sN -X POST http://localhost:8080/api/chat/multimodal/stream \
  -F 'query=请描述图片' \
  -F 'conversationId=multimodal-stream-001' \
  -F 'image=@/path/to/image.jpg;type=image/jpeg' \
  --max-time 300

curl -sN -X POST http://localhost:8080/api/transcribe/stream \
  -F 'audio=@/path/to/audio.wav;type=audio/wav' \
  --max-time 300
```

多模态专项脚本从 `TEST_IMAGE_PATH`、`TEST_AUDIO_PATH` 读取文件：

```bash
./test-multimodal.sh --image /path/to/image.png --audio /path/to/audio.wav
./test-streaming.sh --all
./test-streaming-transcribe.sh
```

真实图片/音频验证分别需要视觉服务和转写服务；只配置聊天模型不能证明多模态链路可用。

## AG-UI 与前端

先启动 Java 后端，再启动 `frontend/`：

```bash
cd frontend
npm ci
npm run dev
```

前端地址是 `http://localhost:4000`。登录后，浏览器侧工具从 `localStorage.auth_token` 读取 Token；GET 自动执行，写操作通过 `useHumanInTheLoop` 确认。

基础检查：

```bash
curl -s http://localhost:8080/api/agui/health
curl -s http://localhost:8080/api/agui/info
curl -s http://localhost:8080/api/agui/skills/api-index
```

浏览器 E2E 需要后端、前端和 Playwright：

```bash
python test-e2e-frontend.py
```

## Docker

Dockerfile 使用 Amazon Corretto 21，而 Maven 编译目标是 Java 17。Compose 文件仍含有历史注释和默认值，启动前必须核对 profile、数据库和 Embedding URL。

```bash
docker compose up -d --build
docker compose logs -f app
docker compose ps
docker compose down
```

当前应用根配置显式激活 `postgresql`，而 Compose 中 PostgreSQL 服务和应用的 PostgreSQL 环境变量仍是注释状态；因此不能把 `docker compose up` 直接当作已验证的默认启动方式。若使用 PostgreSQL，请同时提供数据库服务、`SPRING_PROFILES_ACTIVE=postgresql`、JDBC URL、用户名、密码和 `vector` 扩展，并按 [配置参考](configuration.md) 核对实际生效配置。

仅使用 Dockerfile 时，至少需要显式传入模型配置：

```bash
docker build -t spring-ai-skills-demo .
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=your-api-key \
  -e OPENAI_BASE_URL=https://api.openai.com \
  -e OPENAI_MODEL=gpt-4o \
  spring-ai-skills-demo
```

不要在命令历史或版本库中写入真实密钥；数据持久化和 PostgreSQL 部署的细节以当前 Docker 配置和 [配置参考](configuration.md) 为准。
