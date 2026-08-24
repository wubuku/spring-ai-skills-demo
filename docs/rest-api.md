# REST 与 SSE API 参考

> **目的**: 提供当前 Controller 层的端点地图；详细业务参数以 Swagger/OpenAPI 和运行时 Skill 为准。
> **最后核对**: 2026-08-24

## 基础地址

后端默认：`http://localhost:8080`

- Swagger UI：`/swagger-ui.html`
- OpenAPI JSON：`/v3/api-docs`
- 静态页面：`/`

## 认证

这是 Demo 认证，不是生产 JWT：

1. `POST /api/auth/login` 提交 `username`、`password`。
2. 响应中的 `token` 通过 `Authorization: Bearer <token>` 使用。
3. 登录 API 返回的 Token 载荷是 Base64 编码的 `username:password`，例如 `user1:password1`。
4. `validateToken()` 会校验用户名和密码；错误密码、篡改第二段或未知用户都会被拒绝。
5. 当前实现没有签名、过期时间或可靠的完整性校验，不能作为生产认证方案。

公开商品查询不需要认证；购物车和结算由 `@PreAuthorize("isAuthenticated()")` 保护。浏览器前端会从 `localStorage` 读取 token，AG-UI BFF 转发 Authorization。

## 认证端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/auth/login` | Demo 登录，返回 `success`、`token`、用户名和显示名 |
| `GET` | `/api/auth/verify` | 使用 Authorization 验证 Token |

示例：

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"user1","password":"password1"}'
```

## 聊天和多模态

| 方法 | 路径 | Content-Type | 说明 |
|---|---|---|---|
| `GET` | `/api/chat/test` | - | 观察日志测试入口，会调用模型 |
| `POST` | `/api/chat` | `application/json` | 普通同步聊天，字段为 `content`、可选 `conversationId` |
| `POST` | `/api/chat` | `multipart/form-data` | 多模态同步聊天，字段为 `query`、`conversationId`、可选 `image`、`audio` |
| `POST` | `/api/chat/text` | `application/json` | 传统页面使用的纯文本结构化入口，字段为 `query`、可选 `conversationId` |
| `POST` | `/api/chat/stream` | `application/json` | 普通文本 SSE |
| `POST` | `/api/chat/multimodal/stream` | `multipart/form-data` | 图片、音频和文本 SSE |
| `POST` | `/api/transcribe/stream` | `multipart/form-data` | 音频字段 `audio`，流式转写 SSE |

聊天 SSE 使用 `text/event-stream`。普通聊天 chunk 形状接近：

```json
{"choices":[{"delta":{"content":"文本片段"}}]}
```

结束时发送 `[DONE]`。多模态流额外带有 `type` 字段；转写流发送 `transcribed`、`error` 和完成事件。

### 普通 Agent 写操作确认

普通 Agent 不直接执行 POST、PUT、PATCH 或 DELETE。模型调用
`buildHttpRequest` 后，应用会在当前请求的 `ToolContext` 中登记一个经 Skill API index
校验的 `PendingHttpRequest`，等待用户确认。

`POST /api/chat/text` 在普通文本请求中返回：

```json
{
  "response": "已准备执行写操作 `POST /api/products/cart`，等待用户确认。确认后浏览器才会发送真实请求。",
  "confirmation": {
    "method": "POST",
    "url": "/api/products/cart",
    "queryParams": {
      "productId": "3"
    },
    "body": {}
  }
}
```

只读对话没有待确认动作时，响应只包含 `response`；`confirmation` 不会序列化为
`null`。这个字段是应用生成的结构化协议，不是模型自由文本，且不包含认证头。

传统页面在显示按钮和真正执行前都会读取 `/api/skills/api-index`，拒绝未知 API、
绝对 URL、跨站 URL、非法方法和非法参数。用户点击取消时不会发送业务请求，也不会调用
`/api/explain-result`；点击确认时才用点击瞬间的 `localStorage.auth_token` 发送请求，
然后把真实 HTTP 状态和响应体交给结果解释端点。

兼容端点 `/api/chat` 仍把待确认动作编码为：

````text
[CONFIRM_REQUIRED]
已准备执行写操作 `POST /api/products/cart`，等待用户确认。

```http-request
{"method":"POST","url":"/api/products/cart","queryParams":{"productId":"3"},"body":{}}
```
````

普通 SSE 也使用同一后端生成的兼容文本。标记前的模型文本不属于确认说明，不能作为
“写操作已经成功”的证据。该确认链路的后端集成测试和传统页面 Playwright 测试见
[验证手册](HARNESS.md)。

## 商品 API

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/api/products` | 否 | 按 `keyword`、`category`、`priceMin`、`priceMax` 查询 |
| `GET` | `/api/products/{id}` | 否 | 查询商品详情 |
| `GET` | `/api/products/cart` | 是 | 查询当前用户购物车 |
| `POST` | `/api/products/cart?productId={id}` | 是 | 加入购物车 |
| `POST` | `/api/products/checkout` | 是 | 结算当前用户购物车 |

实际请求示例：

```bash
curl -s 'http://localhost:8080/api/products?category=手机'
curl -s -H 'Authorization: Bearer <token>' \
  'http://localhost:8080/api/products/cart'
```

运行时 Skill 的 API 路径必须与这些 Controller 保持一致；普通 Agent 和前端
`httpRequest` 都会通过同一 `SkillRegistry` 生成的 API index 校验路径。

## 运行时 Skill 发现

这些端点公开、只读，不需要模型或认证：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/skills` | Level 1 目录；不返回 Markdown body |
| `GET` | `/api/skills/{name}` | Level 2 body、links 和该 Skill 的 API 条目；未知名称返回 404 ProblemDetail |
| `GET` | `/api/skills/api-index` | 中性的稳定 method/path allowlist |

Level 2 详情可以包含分层条目的 `referencePath`，但不会返回 reference 正文。
Level 3 仍通过 `readSkillReference` 的受限工具边界读取。

这些观察端点不代表当前普通 Agent 已加载对应 Skill。普通 Agent 的
`httpRequest`、`buildHttpRequest` 和 `readSkillReference` 会在各自的请求级
`SkillLoadSession` 中再次检查 Skill 所属关系；未加载、加载错误 Skill 或缺少上下文
都会被工具拒绝，且 GET 不会发送下游请求，写操作不会登记确认元数据。这个门禁是
Tool Calling 协议约束，不是 `/api/products` 的用户认证替代品。

## AG-UI

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/agui` | CopilotKit BFF 调用的 AG-UI SSE Agent 端点 |
| `GET` | `/api/agui/health` | 返回服务运行状态 |
| `GET` | `/api/agui/info` | 返回 Agent id、名称和描述 |
| `GET` | `/api/agui/skills/api-index` | 旧 API index 兼容别名；JSON 与 `/api/skills/api-index` 相同 |

`POST /api/agui` 的请求体由 `ag-ui-4j` 的 `AgUiParameters` 定义，包含 thread/run、messages 和前端工具 schema；不要手工维护一份与协议类重复的完整 schema。

## PetStore Mock

| 资源 | 基础路径 | 主要操作 |
|---|---|---|
| Pet | `/api/v3/pet` | 增删改、按状态/标签查询、详情、表单更新、上传图片 |
| Store | `/api/v3/store` | inventory、下单、订单详情、删除订单 |
| User | `/api/v3/user` | 创建、批量创建、登录、登出、详情、更新、删除 |

完整参数以 [Swagger UI](http://localhost:8080/swagger-ui.html)、`src/main/resources/petstore.yaml` 和运行时 OpenAPI Skill 为准。

## 结果解释

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/explain-result` | 根据前端刚执行的 API 结果生成解释文本；解释模型失败时仍返回纯文本降级结果 |

请求体：

```json
{
  "method": "GET",
  "url": "/api/products",
  "queryParams": {
    "category": "耳机"
  },
  "statusCode": 200,
  "responseBody": "[{\"id\":3,\"name\":\"Sony WH-1000XM5\"}]"
}
```

该端点是传统 Web UI 的展示层后处理：前端先完成真实 API 请求，再把 method、URL、HTTP
状态和原始响应体提交给它。服务优先从运行时 Skill API index 读取确定性的 API 描述，
然后用独立 `ChatClient` 生成 2-3 句 Markdown；它不会重新执行业务 API，也不参与
`loadSkill`/Tool Calling 循环。

解释模型返回非空文本时，端点原样返回模型内容；模型异常、返回空内容或调用超时时，
仍以 HTTP 200 返回展示文本，但会依据原始状态码使用 `✅`（2xx）或 `❌`（非 2xx）。
这表示“解释服务本身可用”，不把业务 API 的失败状态伪装成成功。实现与确定性测试见：

- [ExplainResultController](../src/main/java/com/example/demo/controller/ExplainResultController.java)
- [ExplainResultService](../src/main/java/com/example/demo/service/ExplainResultService.java)
- [ExplainResultServiceTest](../src/test/java/com/example/demo/service/ExplainResultServiceTest.java)
- [BackendApiIntegrationTest](../src/test/java/com/example/demo/BackendApiIntegrationTest.java)

## 变更同步清单

修改端点时至少同步检查：

1. Controller 和 DTO。
2. `src/main/resources/skills/` 中的 API 指令。
3. `SkillRegistry` 生成的 API index。
4. `SkillsAdvisor`/prompt 模板。
5. 前端 `useHttpRequestTool.tsx` 的 schema 和 URL 校验。
6. Swagger 描述及相关 `test-*.sh`/Playwright 脚本。
