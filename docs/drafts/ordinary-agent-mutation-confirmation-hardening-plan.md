# 普通 Agent 写操作确认协议加固规划

> **状态**: 已实施
> **目的**: 让普通 `AgentService` 和嵌入式传统 Web UI 的写操作确认不再依赖 LLM
> 复制 Markdown/JSON，同时保留可观察、可测试的 Spring AI Tool Calling 教学链路。
> **最后核对**: 2026-08-24
> **前置基线**: [API 结果解释链路加固规划](api-result-explanation-hardening-plan.md)
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收、真实 LLM 和三轮收敛原则](../../AGENTS.md)

## 1. 决策摘要

本批采用“模型负责选择工具，应用代码负责确认协议”的边界：

1. `buildHttpRequest` 继续作为 Spring AI `@Tool`，负责校验写方法、Skill API index、
   URL 和参数；成功后把一个待确认请求记录到当前请求的 `ToolContext`。
2. 普通同步聊天增加结构化结果：`response` 提供后端生成的安全说明，
   `confirmation` 提供经过校验的 method、相对 URL、queryParams 和 body。传统页面优先
   消费结构化字段，不再要求模型把工具返回值复制进 Markdown。
3. 兼容只接收文本的普通聊天和流式入口：由 `AgentService` 根据请求级待确认状态生成
   `[CONFIRM_REQUIRED]`、安全说明和 `http-request` 代码块，而不是由 LLM 生成。同步
   结构化端点不需要解析该文本协议。
4. 浏览器在真正执行写请求前，再读取后端 Skill API index，校验 method 和相对 URL；
   同时拒绝跨站、非法、缺字段或非写方法的确认元数据。
5. 页面取消时不发送业务 POST；确认时使用点击瞬间从 `localStorage` 读取的最新 token，
   执行真实 API，再调用 `/api/explain-result`。最终 DOM 标记必须依据真实业务 HTTP
   状态，不能给失败结果添加成功前缀。
6. 本批只处理普通 `AgentService`、嵌入式传统页面及其教学文档。AG-UI/CopilotKit
   工具注册、SSE 生命周期和 UI 不在范围内。

这不是把 Tool Calling 移出 Spring AI。模型仍必须完成：

```text
用户意图
  -> loadSkill
  -> 读取 Skill API 契约
  -> buildHttpRequest
```

变化只发生在工具成功之后：是否存在待确认写操作由请求级 Java 状态确定，HTTP 响应协议
由应用生成，避免把安全边界交给模型的自由文本格式。

## 2. 当前事实与已确认问题

### 2.1 当前普通写操作链路

```text
POST /api/chat/text
  -> MultimodalChatController
  -> AgentService
  -> ChatClient + SkillsAdvisor + ToolCallAdvisor
  -> loadSkill("add-to-cart")
  -> buildHttpRequest("POST", "/api/products/cart", ...)
  -> 工具返回裸 JSON 给模型
  -> 模型应把 JSON 复制进 ```http-request 代码块
  -> static/index.html 解析代码块并显示确定/取消按钮
  -> 用户确认后浏览器执行 POST
  -> POST /api/explain-result
```

对应源码和测试：

- 普通编排：`src/main/java/com/example/demo/service/AgentService.java`
- 工具：`src/main/java/com/example/demo/agent/SkillTools.java`
- Prompt：`src/main/resources/prompts/skills-advisor/backend-mode-rules.template`
- 同步入口：`src/main/java/com/example/demo/controller/MultimodalChatController.java`
- 传统页面：`src/main/resources/static/index.html`
- 确定性工具回合：
  `src/test/java/com/example/demo/BackendApiIntegrationTest.java`
- 页面 Mock：
  `frontend/tests/traditional-ui-mock-e2e.mjs`

### 2.2 已确认的正确基础

- `httpRequest` 只执行经过 Skill API index 校验的 GET。
- `buildHttpRequest` 只接受 POST/PUT/PATCH/DELETE，且不会执行写请求。
- URL 必须是相对路径；绝对 URL、目录跳转、控制字符和未知 API 会被拒绝。
- 普通请求为每次调用创建独立 `SkillLoadSession`，已验证 token 通过
  `ToolContext` 传给只读工具。
- 商品写 API 由 Spring Security 保护；浏览器确认后必须带用户 token。
- `/api/explain-result` 已按真实 HTTP 状态提供成功/失败解释和确定性降级。
- 默认传统页面使用同步 `/api/chat/text`；用户可手动切换到流式模式。

### 2.3 当前缺口

| 缺口 | 影响 |
|---|---|
| 工具返回裸 JSON，Prompt 要求模型再次复制并包裹代码块 | 模型即使正确调用工具，也可能省略、改写或截断协议，页面没有确认按钮 |
| 后端测试只检查 ToolResponse 中存在元数据 | 没有证明真实 HTTP 响应携带页面可直接消费的确认动作 |
| 同步响应只有 `response` 字符串 | 页面无法区分普通文本和经过后端校验的待确认状态 |
| 流式确认依赖模型最终文本 | 同样受自由文本不稳定性影响 |
| 页面解析任意 `http-request` 文本后仅做同源检查 | 模型生成的同源未知 API 理论上仍可进入确认界面 |
| `executeHttpRequest` 对任意业务状态都在结果外层加 `✅ 操作结果` | 4xx/5xx 即使被解释为 `❌`，页面仍可能显示矛盾的成功前缀 |
| 现有 Playwright 只覆盖登录和只读聊天 | 没有自动证明取消不写、确认带 token、解释端点被调用和失败不误报 |

真实 `grok-4.5` 传统页面实验已经证明：只读 Tool Calling 能完成，但“模型复制
`http-request` 代码块”没有在限定观察窗口内稳定完成。因此本批不继续把增加 Prompt
措辞作为主修复。

## 3. 实施设计

### 3.1 请求级待确认状态

新增一个普通 Agent 专用、非 Spring 单例状态对象，例如：

```text
MutationConfirmationSession
  -> 最多登记一个 PendingHttpRequest
  -> register 成功后可由 AgentService 读取
  -> 第二次 buildHttpRequest 被拒绝
```

`PendingHttpRequest` 使用明确字段，不存储认证头：

| 字段 | 规则 |
|---|---|
| `method` | 仅 POST/PUT/PATCH/DELETE，规范化大写 |
| `url` | 已替换 pathParams 的同源相对路径，不包含 query string |
| `queryParams` | 可为空的字符串 map |
| `body` | 可为空的 JSON object |

`SkillTools.buildHttpRequest` 增加特殊参数 `ToolContext`。Spring AI
`MethodToolCallback` 会从工具 schema 中排除该参数并在执行时注入，现有
`loadSkill` 已使用同一机制。

登记顺序固定为：

```text
method/URL/参数校验
  -> pathParams 替换
  -> resolved API index 校验
  -> 创建不可变 PendingHttpRequest
  -> 原子登记到 MutationConfirmationSession
  -> 返回序列化 JSON 给 ToolResponse
```

非法请求不得污染 session。每轮第二个写操作返回明确拒绝信息，防止一个用户回复对应
多个待确认动作。

为保持现有 Spring AI ToolResponse 兼容，`buildHttpRequest` 返回给模型的 JSON 仍暂时
保持当前字段和绝对 `url` 形状；新增的 `PendingHttpRequest`、同步 `confirmation`
字段和后端生成的兼容文本统一使用相对 URL。这样既不改变既有工具回合的输入/输出
契约，也不让浏览器把模型复制的绝对 URL 当成新的安全依据。

### 3.2 同步结构化响应

新增服务层结果类型，例如：

```text
AgentChatResult {
  response: String
  confirmation: PendingHttpRequest?
}
```

`AgentService.chatResult(...)` 创建同一个 ToolContext map，执行完整 ChatClient 工具
回合，然后检查 `MutationConfirmationSession`：

- 没有待确认动作：保留模型正常文本，`confirmation=null`。
- 有待确认动作：忽略模型可能声称成功的最终文本，返回后端生成的安全说明和结构化
  `confirmation`。
- 工具已经登记待确认动作、但模型在生成最终文本时异常或返回空白：仍返回待确认动作；
  如果 session 为空，则保留现有异常传播语义。

`MultimodalChatResponse` 增加可空 `confirmation` 字段，并保留单参数构造方式，避免修改
现有多模态调用点。JSON 中没有确认动作时不输出该字段，保持调用方兼容。

现有 `AgentService.chat(...)` 继续返回字符串，供 `/api/chat` 等旧调用方使用；当存在
确认动作时，忽略模型最终文本，由后端返回完整兼容协议：

````text
[CONFIRM_REQUIRED]
已准备执行写操作 `POST /api/products/cart`。确认后浏览器才会发送请求。

```http-request
{"method":"POST","url":"/api/products/cart","queryParams":{"productId":"3"},"body":{}}
```
````

安全说明只包含已校验 method/path 和“尚未执行”的事实，不使用模型自由文本。

### 3.3 流式兼容

本批不重新设计 SSE schema。`AgentService.streamChat(...)` 继续返回 `Flux<String>`：

1. 正常转发模型 token；
2. 流完成时，如果 session 中存在待确认动作，追加一个由后端生成的
   `[CONFIRM_REQUIRED] + 安全说明 + http-request 代码块` token；
3. 如果最终模型流在待确认动作登记后失败，降级为追加同一完整协议；session 为空时
   保留原错误；
4. Prompt 改为明确要求模型在 `buildHttpRequest` 后停止工具调用、不要自行生成协议
   代码块、不要声称成功。

传统页面会在流结束后解析完整文本，并用确认界面替换临时 assistant 文本。同步端点是
本批首要教学路径；流式代码块只是当前 SSE 响应格式下的兼容方案，未来若升级协议，可
增加明确的 `confirmation` SSE event，而不改变 `PendingHttpRequest` 核心模型。
解析器必须从最后一个 `[CONFIRM_REQUIRED]` 标记开始查找代码块，并只使用该标记与
代码块之间的后端安全说明；不得把标记之前的模型 token 当作确认描述。

### 3.4 传统页面消费与安全校验

同步页面按以下优先级处理：

```text
data.confirmation 存在
  -> 校验 shape
  -> 显示确认
否则
  -> 优先解析最后一个 [CONFIRM_REQUIRED] 后的合法 http-request 代码块
  -> 仅为旧响应兼容，再尝试最后一个无标记的合法代码块
  -> 显示确认或普通文本
```

确认元数据在显示和执行前至少校验：

- 必须是普通 JSON object；
- method 必须是写方法；
- URL 必须可解析为当前 origin，禁止用户名、密码和 fragment；
- path 必须以 `/api/` 开头；
- queryParams/params 必须是简单 object；
- body 必须是 object 或省略；
- 从 `/api/agui/skills/api-index` 读取当前后端 API index，method/path 必须精确或按
  `{parameter}` 模板匹配。

这里复用现有 API index 端点，不修改 AG-UI Agent 行为。后续可以把端点迁移到更中性的
`/api/skills/api-index`，但本批不为命名重构扩大范围。

执行结果改为结构化内部返回：

```text
{ ok: response.ok, status: response.status, text: explanationOrRawBody }
```

页面只在解释文本本身没有 `✅`/`❌` 前缀时，依据 `ok` 补充正确标记；不再无条件添加
成功前缀。取消按钮移除确认控件并显示取消消息，不发送业务 API 或解释请求。

### 3.5 Prompt

后端模式 Prompt 修改为：

- 写操作仍必须调用 `buildHttpRequest`；
- `buildHttpRequest` 已由应用记录待确认动作；
- 工具返回后立即停止，不重复调用业务工具；
- 不要自行输出 `http-request` 代码块；
- 在真实 API 结果到达前禁止声称成功。

`PromptLoader` 中的 hardcoded fallback 必须同步修改，避免 classpath 模板缺失时恢复旧
协议。

## 4. 一次性验收测试设计

测试必须在生产实现修改前一次性补齐；测试失败应对应本规划明确的目标，而不是在代码
审查阶段零碎追加。

### 4.1 `SkillToolsTest`

新增或改写以下断言：

1. 合法写操作继续向模型返回现有字段和绝对 URL 的 ToolResponse JSON，同时登记一份
   相对 URL 的 `PendingHttpRequest` 到当前 `MutationConfirmationSession`。
2. GET、未知 URL、未解析 pathParams、非法参数不会登记待确认动作。
3. 同一 session 第二次构建写操作被拒绝，保留第一条元数据。
4. 不同 ToolContext 的待确认状态隔离。
5. 兼容文本 formatter 生成 `[CONFIRM_REQUIRED]`、安全说明和单个合法代码块，不包含
   模型声称成功的文本。

### 4.2 `BackendApiIntegrationTest`

使用现有 Scripted `ChatModel`，把最终模型文本故意设为“已成功添加”，证明应用边界会
覆盖不可信文本：

1. 登录并带 token 调用 `/api/chat/text`；
2. 模型执行 `loadSkill -> buildHttpRequest`；
3. HTTP JSON 的 `response` 必须表示“等待确认”，不得包含“已成功”；
4. `confirmation.method/url/queryParams` 与生产工具校验结果一致，且 confirmation URL
   为相对路径；ToolResponse 仍保持既有绝对 URL 兼容形状；
5. 收到确认响应前购物车仍为空；
6. 模拟用户确认后用同一 token 调用真实 POST、GET cart、POST checkout；
7. Prompt 明确要求模型不生成代码块并停止继续工具调用。

如当前 Spring AI Mock streaming 能稳定驱动 ToolCallAdvisor，则同一测试类增加
`/api/chat/stream` 场景，断言后端追加 `[CONFIRM_REQUIRED]` 和确认代码块且 `[DONE]`
在其后；如果框架 Mock streaming 无法复用确定性 tool loop，则用服务层测试锁定
formatter/session，前端 Playwright 锁定流式协议消费，不用脆弱的 provider 模拟替代。

### 4.3 传统页面 Mock Playwright

扩展 `frontend/tests/traditional-ui-mock-e2e.mjs`，在一个隔离 Mock server 中覆盖：

1. 登录和现有只读聊天仍通过；
2. 结构化写操作响应出现确定/取消按钮；
3. 点击取消后没有 POST 业务请求，也没有 `/api/explain-result`；
4. 再次发起写操作并确认：
   - 页面先读取 Skill API index；
   - POST URL/query 正确；
   - `Authorization` 使用最新 localStorage token；
   - 随后调用 `/api/explain-result`；
   - DOM 显示解释结果；
5. 业务 API 返回非 2xx 时，DOM 显示失败标记且没有矛盾的成功前缀；
6. 流式兼容文本即使在标记前包含模型“已成功”字样，确认界面也只显示标记后的后端
   安全说明；
7. 非法/未知/跨站确认元数据不会出现可执行确认按钮；
8. 浏览器 console 没有 error。

不使用截图作为验收证据。

## 5. 修改范围

### 生产代码

- 新增 `src/main/java/com/example/demo/agent/MutationConfirmationSession.java`
- 新增 `src/main/java/com/example/demo/model/PendingHttpRequest.java`
- 新增 `src/main/java/com/example/demo/service/AgentChatResult.java`
- 修改 `src/main/java/com/example/demo/agent/SkillTools.java`
- 修改 `src/main/java/com/example/demo/service/AgentService.java`
- 修改 `src/main/java/com/example/demo/dto/MultimodalChatResponse.java`
- 修改 `src/main/java/com/example/demo/controller/MultimodalChatController.java`
- 修改 `src/main/resources/prompts/skills-advisor/backend-mode-rules.template`
- 修改 `src/main/java/com/example/demo/service/PromptLoader.java`
- 修改 `src/main/resources/static/index.html`

具体类型名可在实现时按现有 package 语义微调，但不得改变本节的责任边界。

### 测试

- 修改 `src/test/java/com/example/demo/agent/SkillToolsTest.java`
- 修改 `src/test/java/com/example/demo/BackendApiIntegrationTest.java`
- 必要时修改 `src/test/java/com/example/demo/service/AgentServiceTest.java`
- 修改 `frontend/tests/traditional-ui-mock-e2e.mjs`
- 修改 `frontend/tests/traditional-ui-e2e.mjs`，增加真实写操作可选场景或独立脚本入口

### 文档

- 更新 `docs/ARCHITECTURE.md`
- 更新 `docs/learning-path.md`
- 更新 `docs/rest-api.md`
- 更新 `docs/HARNESS.md`
- 更新 `docs/drafts/README.md`
- 完成后更新本文状态、实施记录、验证证据和剩余边界

`CLAUDE.md` 不改；根 `AGENTS.md` 已包含长期流程，本批没有新的通用 agent 规则时不重复
扩写。

## 6. 验证硬门槛

### 后端与仓库

```bash
git diff --check
mvn clean compile test-compile
mvn -Dtest='SkillToolsTest,AgentServiceTest,BackendApiIntegrationTest,ChatControllerTest,ExplainResultServiceTest' test
mvn test
```

后端必须能够通过 `./dev.sh --backend-only` 使用 `.env` 和 PostgreSQL profile 启动。

### 前端和浏览器

```bash
cd frontend
npx tsc --noEmit
npm run build
npm run test:repository
npm run test:skills
npm run test:e2e:traditional:mock
```

### 真实 LLM

Mock 和构建门槛全部通过后：

1. 使用主工作区 `.env` 启动 `./dev.sh --backend-only`；
2. 持续观察后端日志，不输出 key；
3. 运行传统页面 Playwright 写操作场景；
4. 断言真实模型调用 `loadSkill` 和 `buildHttpRequest` 后，页面出现确认按钮；
5. 先执行一次取消，确认购物车未变；
6. 再执行一次确认，检查 POST、token、`/api/explain-result` 和 DOM 结果；
7. 用 GET cart 或只读数据库/API 查询确认业务状态；
8. 清理购物车并停止服务。

真实模型是否生成代码块不再是通过条件；通过条件是模型正确选择工具，后端据请求级状态
生成稳定确认协议。

## 7. 三轮实现收敛审查

只有第 6 节全部硬门槛通过后才开始，范围固定且互不重叠：

1. **正确性与兼容性**：ToolContext/session 生命周期、同步/流式响应、旧调用方和
   `MultimodalChatResponse` JSON 兼容。
2. **安全与数据一致性**：API index、相对 URL、跨站拒绝、重复待确认、token 时点、
   取消无副作用、非 2xx 展示。
3. **测试与教学交付**：测试是否覆盖本批代码、真实证据是否充分、稳定文档与源码是否
   一致、提交范围和子模块是否干净。

任一轮发现影响正确性、安全、兼容性、成本或数据一致性的问题，立即修复并重新通过全部
硬门槛，审查计数归零。格式和可选重构不触发发散式修改。

## 8. 风险、默认值和可逆边界

| 风险/未知项 | 推荐默认 | 理由与可逆边界 |
|---|---|---|
| 同一回复多个写操作 | 只接受第一个 | 一个确认按钮对应一个授权动作；未来需要批量操作时应设计显式事务/批量 DTO |
| 同步 API 增加字段 | 可空且不序列化 null | 对只读取 `response` 的调用方是加法兼容 |
| 流式协议仍是代码块 | 后端确定性生成 | 不扩大 SSE schema；未来可升级为专用 event |
| 模型最终文本与待确认状态冲突 | 后端安全说明覆盖 | 业务状态不能由模型自由文本决定 |
| API index 端点位于 `/api/agui` | 本批复用 | 只读取共享索引，不修改 AG-UI；后续可增加中性别名 |
| 真实模型不调用 `buildHttpRequest` | 记录为模型选择失败并继续改进 Prompt/Skill | 不允许页面绕过工具直接执行；Mock 仍证明应用协议正确 |
| 多模态写操作 | 保持字符串兼容协议 | 本批学习主线是纯文本普通 Agent；后续可让多模态响应也暴露结构化字段 |

## 9. 中断恢复与提交

若任务中断：

1. 运行 `git status --short --branch`；
2. 阅读本文状态和最后实施记录；
3. 从第一个未完成的实施步骤继续；
4. 不重新发明第二套确认 DTO、session 或计划。

完成标准：

- 规划连续三轮无修改审查通过；
- 一次性验收测试先于生产实现完成；
- 所有硬门槛、真实 LLM 和传统页面闭环通过，或外部失败被准确分类；
- 实现连续三轮无修改审查通过；
- 本文和稳定文档更新为当前事实；
- commit、push 成功；
- 主仓库、`ag-ui-4j` 和 `spring-ai-agent-utils` 均干净。

## 10. 实施结果

### 10.1 实际实现

本规划已按设计实施，主要结果如下：

1. `SkillTools.buildHttpRequest` 使用 Spring AI 1.1.8 的
   `@Tool(returnDirect = true)`，在完成写请求校验和登记后结束模型工具循环。
2. 新增请求级 `MutationConfirmationSession`、不可变 `PendingHttpRequest` 和
   `AgentChatResult`；每个普通 ChatClient 调用最多登记一个待确认动作。
3. `/api/chat/text` 返回后端生成的安全说明和可选 `confirmation`；旧 `/api/chat`
   与普通 SSE 由 `AgentService` 生成 `[CONFIRM_REQUIRED]` 兼容文本。
4. 传统页面在显示和执行前分别读取 `/api/agui/skills/api-index`，拒绝未知 API、
   跨站 URL、非法 method/query/body；执行时读取点击瞬间的最新 token。
5. 取消不会发送业务写请求或结果解释请求；确认后页面按真实 HTTP 状态显示结果，
   不再给失败解释添加成功前缀。
6. 新增 `frontend/tests/traditional-ui-mutation-e2e.mjs`，作为真实模型、传统页面和
   商品写 API 的独立 Playwright 闭环。

稳定事实已同步到：

- [系统架构](../ARCHITECTURE.md)
- [Spring AI 学习主线](../learning-path.md)
- [REST 与 SSE API](../rest-api.md)
- [验证手册](../HARNESS.md)
- [根协作指南](../../AGENTS.md)

### 10.2 离线与构建验证

2026-08-24 运行并通过：

| 命令 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| `mvn clean compile test-compile` | 通过 |
| `mvn test` | 47/47 通过 |
| `cd frontend && npx tsc --noEmit` | 通过 |
| `cd frontend && npm run test:repository` | 通过 |
| `cd frontend && npm run build` | 通过；仅有既存第三方动态依赖 warning |
| `cd frontend && npm run test:skills` | 通过 |
| `cd frontend && npm run test:e2e:traditional:mock` | 通过 |

Mock/确定性证据覆盖：

- `loadSkill -> buildHttpRequest` Tool Calling 回合；
- 模型误报“已成功”时后端安全说明覆盖；
- ToolResponse 绝对 URL 兼容与结构化 confirmation 相对 URL；
- session 隔离、重复写操作拒绝和非法请求不污染状态；
- 取消无副作用、确认 token 时点、API index 二次校验；
- 业务非 2xx 结果不显示矛盾的成功前缀。

### 10.3 真实 LLM 与浏览器闭环

2026-08-24 使用主工作区 `.env`，通过 `./dev.sh --backend-only` 以
`postgresql` profile 启动后端：

- ChatModel：OpenAI-compatible `grok-4.5`；
- 数据库：本地 PostgreSQL `localhost:5432`；
- 浏览器入口：嵌入式传统 Web UI `http://localhost:8080/`；
- 测试命令：`cd frontend && npm run test:e2e:traditional:mutation`。

Playwright 在不使用截图的前提下证明：

1. 真实模型完成 `loadSkill -> buildHttpRequest` 并产生结构化确认按钮；
2. 第一次点击取消，没有业务 POST，也没有 `/api/explain-result`；
3. 第二次确认发送 `POST /api/products/cart?productId=3`；
4. 请求使用点击前写入 `localStorage` 的 `user2` 最新 token；
5. `/api/explain-result` 匹配 `POST /api/products/cart`，DOM 显示成功结果；
6. 受保护的 GET cart 返回商品 3，随后 checkout 清理购物车；
7. 测试 `finally` 再次清理 `user1`/`user2` 购物车，服务停止后 8080/4000 均空闲。

真实调用期间持续观察后端日志，没有出现模型认证失败、数据库异常、工具重复循环或
服务无响应。

### 10.4 实现收敛审查

在全部硬门槛和真实闭环通过后，连续完成三轮只读审查，期间没有修改实现代码：

| 时间（Asia/Shanghai） | 范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-24 13:31 | 正确性与 API 兼容 | 无 | 同步/流式、旧端点、DTO、Prompt 和多模态复用边界一致，计数 1 |
| 2026-08-24 13:32 | 安全与数据一致性 | 无 | API index、相对 URL、token 时点、取消/确认和清理边界成立，计数 2 |
| 2026-08-24 13:33 | 测试、文档与交付 | 无 | 验证分层、真实证据、导航、启动命令和子模块边界完整，计数 3 |

三轮终止条件已满足。本批未修改 AG-UI/CopilotKit 工具注册或协议实现。
