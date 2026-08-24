# Spring AI 学习主线

> **目的**: 用一条可运行、可验证的路径理解本项目展示的 Spring AI 能力。
> **最后核对**: 2026-08-24
> **前置条件**: JDK 17+、Maven 3.8+；只有进入真实聊天步骤才需要模型 API key。

这个项目同时展示 REST、Tool Calling、运行时 Skills、记忆、RAG、多模态、SSE 和
AG-UI。不要从 CopilotKit 或多模态入口开始。先走普通后端链路，再按需打开高级能力，
这样可以明确哪些行为来自 Spring AI，哪些是本项目为业务 API 增加的协议层。

## 五分钟黄金路径

### 1. 先看业务 API

先从没有模型依赖的部分开始：

- Controller：[ProductController](../src/main/java/com/example/demo/controller/ProductController.java)
- 业务状态：[ProductService](../src/main/java/com/example/demo/service/ProductService.java)
- API 参考：[REST 与 SSE API](rest-api.md)
- 运行时指令：[商品 Skills](../src/main/resources/skills/)

启动一个 H2 本地后端并查询商品：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
curl -s http://localhost:8080/api/products
```

这一层展示的是 Spring MVC 和普通 Java 服务，不是 Agent 能力。先理解商品 API 的
认证边界、返回结构和购物车状态，后面才能判断模型是否正确调用了它。

### 2. 看最小 ChatClient 编排

普通聊天入口是：

```text
POST /api/chat/text
  -> MultimodalChatController
  -> AgentService
  -> ChatClient
  -> ChatModel
```

重点源码：

- Controller：[MultimodalChatController](../src/main/java/com/example/demo/controller/MultimodalChatController.java)
- 编排：[AgentService](../src/main/java/com/example/demo/service/AgentService.java)
- 模型配置：[SpringAiConfig](../src/main/java/com/example/demo/config/SpringAiConfig.java)

`AgentService` 使用 Spring AI 的 `ChatClient.Builder`、`ChatClient`、Advisor 和
`ToolCallingManager`。JDBC 短期记忆、可选向量记忆、RAG 和 Tool Calling 的顺序在
构造函数中明确写出，便于观察 Advisor 如何组合。

这一步需要模型 key 才能真实聊天；没有 key 时，先运行后面的 Mock 集成测试即可。

### 3. 观察 Spring AI Tool Calling 回合

普通链路注册的是 [SkillTools](../src/main/java/com/example/demo/agent/SkillTools.java)：

| Spring AI 概念 | 当前项目实现 |
|---|---|
| `@Tool` / `@ToolParam` | `SkillTools.loadSkill`、`httpRequest`、`buildHttpRequest`、`readSkillReference` |
| `ChatClient` tool registration | `AgentService` 的 `.defaultTools(skillTools)` |
| Tool execution loop | `ToolCallAdvisor` + `ToolCallingManager` |
| 每次调用的额外上下文 | `ChatClient` 的 `ToolContext` |

确定性的回合测试在
[BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)：

- 商品查询：`loadSkill -> httpRequest(GET) -> final answer`；
- 写操作：`loadSkill -> buildHttpRequest(POST) -> confirmation metadata`；
- 用户确认之后，测试才直接调用商品 POST，再验证购物车和 checkout。

运行这些测试不访问真实 LLM：

```bash
mvn -Dtest='BackendApiIntegrationTest,SkillToolsTest' test
```

这个边界很重要：模型只能提出写操作，`buildHttpRequest` 不会执行 POST。传统页面或
浏览器确认后才由客户端发出真正的写请求。

同步纯文本入口返回的 JSON 形状是：

```json
{
  "response": "已准备执行写操作 `POST /api/products/cart`，等待用户确认。",
  "confirmation": {
    "method": "POST",
    "url": "/api/products/cart",
    "queryParams": { "productId": "3" },
    "body": {}
  }
}
```

这是一个值得单独观察的 Spring AI 教学点：模型仍负责选择
`loadSkill -> buildHttpRequest`，但确认协议由 Java 应用从请求级 `ToolContext` 生成。
因此最终模型文本即使误报“已经加入购物车”，后端也不会把它当作业务事实。传统页面
还会重新读取 `/api/agui/skills/api-index`，校验 method/path/query/body 后才显示按钮；
取消只移除确认控件，确认才会发送带当前 token 的业务 POST，之后再请求结果解释端点。

旧客户端若只消费字符串，`POST /api/chat` 和普通 SSE 仍可使用
`[CONFIRM_REQUIRED]` 加 `http-request` 代码块的兼容格式。该格式是后端生成的兼容层，
不是要求模型自行复制 JSON。完整端点契约和验证命令见
[REST 与 SSE API](rest-api.md) 与 [验证手册](HARNESS.md)。

### 4. 观察 API 结果解释的展示层后处理

传统 Web UI 执行 `httpRequest` 后，会把真实请求的 method、URL、HTTP 状态和响应体提交
到 `/api/explain-result`。这条链路适合单独学习“确定性 API 文档 + Spring AI 自然语言
解释”的组合：

```text
真实 API 响应
  -> SkillRegistry API index 匹配
  -> 注入匹配的 API 描述或 Level 1 Skill 目录
  -> 独立 ChatClient 生成 Markdown 解释
  -> 模型失败时按原始 HTTP 状态确定性降级
```

重点源码：

- 结果解释端点：[ExplainResultController](../src/main/java/com/example/demo/controller/ExplainResultController.java)
- 解释服务：[ExplainResultService](../src/main/java/com/example/demo/service/ExplainResultService.java)
- 端点与失败降级集成测试：[BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)
- 服务契约单元测试：[ExplainResultServiceTest](../src/test/java/com/example/demo/service/ExplainResultServiceTest.java)

这里的解释客户端不是第二个 Agent：它不执行 `loadSkill`、不重新调用业务 API，也不改变
对话记忆。已知 API 优先使用 Skill API index 的确定性正文；只有找不到匹配 API 时才
提供当前真实 Skill 的 Level 1 name/description 目录。模型不可用时，降级标记仍依据
原始业务 HTTP 状态，因此不会把 4xx/5xx 解释成“操作已完成”。完整请求字段和响应语义见
[REST 与 SSE API](rest-api.md) 的“结果解释”章节。

### 5. 理解运行时 Skills 的渐进式披露

当前项目的 Skills 不是 Spring Bean，也不是 Spring AI 自动扫描的标准类型，而是本项目
建立在 Spring AI Tool Calling 之上的资源协议：

```text
src/main/resources/skills/*/SKILL.md
  -> SkillRegistry：解析 frontmatter、校验 links、建立 API index
  -> SkillsAdvisor：注入 Level 1 name/description
  -> loadSkill：返回 Level 2 SKILL.md 正文
  -> readSkillReference：返回 Level 3 references 文件
  -> SkillTools：按 API index 校验并执行请求
```

对应源码：

- 注册与索引：[SkillRegistry](../src/main/java/com/example/demo/agent/SkillRegistry.java)
- Prompt Advisor：[SkillsAdvisor](../src/main/java/com/example/demo/agent/SkillsAdvisor.java)
- 请求级 Skill 状态：[SkillLoadSession](../src/main/java/com/example/demo/agent/SkillLoadSession.java)
- 分层参考读取：[SkillReferenceReader](../src/main/java/com/example/demo/agent/SkillReferenceReader.java)

Skill 的当前契约是：

- frontmatter 至少包含 `name` 和 `description`；
- 对可解析的 classpath/文件系统来源，资源目录名必须等于 frontmatter `name`；
- `links` 必须指向已注册、非自身且不重复的 Skill；
- Skill 正文描述真实 Controller 的 method、path、参数、认证和返回结构；
- API index 是 Java 工具和浏览器 URL 校验的边界，不允许模型凭记忆猜测 URL。

frontmatter、links、reference 安全和 API index 的确定性测试见
[SkillRegistryTest.java](../src/test/java/com/example/demo/agent/SkillRegistryTest.java) 和
[SkillReferenceReaderTest.java](../src/test/java/com/example/demo/agent/SkillReferenceReaderTest.java)。

### 6. 走一遍认证和用户确认

Demo 认证不是生产 JWT，而是经过校验的 Base64 `username:password`。普通链路把当前已
验证的 token 放进请求级 `ToolContext`；商品 Controller 只从
`Authentication` 取得用户名。

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"password1"}' |
  sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -s -X POST 'http://localhost:8080/api/products/cart?productId=3' \
  -H "Authorization: Bearer $TOKEN"
```

完整的登录、确认、购物车、checkout 操作见 [操作示例](OPERATIONS.md)；认证限制见
[REST API 参考](rest-api.md)。这个示例的教育重点是“模型上下文中的身份”和“业务 API
执行时的身份”必须来自同一个经过验证的请求边界。

## 继续学习

### 7. JDBC 记忆和 RAG

Spring AI 提供 Chat Memory、VectorStore 和 `QuestionAnswerAdvisor`；本项目负责配置
数据源、加载 Markdown、生成稳定文档 ID，并把知识库 VectorStore 与语义聊天记忆
VectorStore 分开。

知识库 Markdown 按 UTF-8 读取，以 normalized source 去重并排序后写入 VectorStore。
因此多个 glob 重复命中同一文件不会在单次启动中重复嵌入，配置顺序也不会改变导入
顺序；这部分由 `KnowledgeBaseInitializerTest` 在不访问真实 Embedding 的情况下验证。

- 源码和边界：[知识库与运行时 Skills](knowledge-and-skills.md)
- 配置：[configuration.md](configuration.md)
- 运行和专项脚本：[OPERATIONS.md](OPERATIONS.md)
- 组合位置：[AgentService](../src/main/java/com/example/demo/service/AgentService.java)

公司保修、服务条款、配送政策等“事实内容”放到 `knowledge-base/`，不要伪装成可执行
Skill。查询订单、申请退款、创建售后单等动作才放到运行时 Skill；两者如何组合见
[知识库与运行时 Skills](knowledge-and-skills.md)。

RAG 和向量记忆默认关闭。只配置聊天模型时，仍可学习普通 Skills；打开 RAG 或语义记忆
时，还需要 Embedding 和对应的 profile/VectorStore。

### 8. 普通 SSE 和多模态

普通文本流使用 `ChatController` 的 Spring MVC `SseEmitter`：

- [ChatController](../src/main/java/com/example/demo/controller/ChatController.java)
- [ChatControllerTest](../src/test/java/com/example/demo/controller/ChatControllerTest.java)
- [REST/SSE 参考](rest-api.md)

普通 SSE 的 data 是接近 OpenAI 的 `choices[0].delta.content` JSON，最后是 `[DONE]`。
它与 AG-UI 的事件流不是同一协议。图片、音频和转写需要额外模型配置，适合在普通文本
链路稳定后再学习。

### 9. AG-UI/CopilotKit 高级链路

AG-UI 是独立的高级链路，不应作为普通 Skills 的第一课：

```text
Next.js/CopilotKit
  -> BFF
  -> /api/agui
  -> SpringAIAgent
  -> 后端 loadSkill/readSkillReference
  -> 浏览器 httpRequest 和确认
```

该链路的后端只注册核心 Skill 工具，浏览器负责带用户 Token 执行 HTTP 请求。详细边界
见 [系统架构](ARCHITECTURE.md)、[前端指南](../frontend/README.md) 和
[CopilotKit 集成说明](../COPILOTKIT_INTEGRATION.md)。AG-UI 的 singleton 状态、异步
上下文和 CopilotKit 前端行为不应被普通 `AgentService` 的测试结果替代。

## 验证顺序

推荐顺序是：

```text
mvn clean compile test-compile
-> 默认 Maven Mock/确定性测试
-> 普通页面 Mock Playwright
-> 真实 LLM 普通页面闭环（获得授权后）
-> PostgreSQL/RAG、多模态
-> AG-UI/CopilotKit 专项
```

完整命令和外部依赖见 [验证手册](HARNESS.md)。前端验收使用 DOM、网络、JSON 和自动化
断言，不使用截图；真实 LLM 只在 Mock 门槛通过后作为补充证据。
