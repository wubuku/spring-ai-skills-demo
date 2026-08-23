# REST 与 SSE API 参考

> **目的**: 提供当前 Controller 层的端点地图；详细业务参数以 Swagger/OpenAPI 和运行时 Skill 为准。
> **最后核对**: 2026-08-23

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
| `POST` | `/api/chat/text` | `application/json` | 多模态 Controller 提供的纯文本兼容入口 |
| `POST` | `/api/chat/stream` | `application/json` | 普通文本 SSE |
| `POST` | `/api/chat/multimodal/stream` | `multipart/form-data` | 图片、音频和文本 SSE |
| `POST` | `/api/transcribe/stream` | `multipart/form-data` | 音频字段 `audio`，流式转写 SSE |

聊天 SSE 使用 `text/event-stream`。普通聊天 chunk 形状接近：

```json
{"choices":[{"delta":{"content":"文本片段"}}]}
```

结束时发送 `[DONE]`。多模态流额外带有 `type` 字段；转写流发送 `transcribed`、`error` 和完成事件。

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

运行时 Skill 的 API 路径必须与这些 Controller 保持一致；前端 `httpRequest` 会通过 `/api/agui/skills/api-index` 校验路径。

## AG-UI

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/agui` | CopilotKit BFF 调用的 AG-UI SSE Agent 端点 |
| `GET` | `/api/agui/health` | 返回服务运行状态 |
| `GET` | `/api/agui/info` | 返回 Agent id、名称和描述 |
| `GET` | `/api/agui/skills/api-index` | 返回运行时 Skill 注册的 API 索引，供 URL 校验 |

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
| `POST` | `/api/explain-result` | 根据前端刚执行的 API 结果生成解释文本 |

## 变更同步清单

修改端点时至少同步检查：

1. Controller 和 DTO。
2. `src/main/resources/skills/` 中的 API 指令。
3. `SkillRegistry` 生成的 API index。
4. `SkillsAdvisor`/prompt 模板。
5. 前端 `useHttpRequestTool.tsx` 的 schema 和 URL 校验。
6. Swagger 描述及相关 `test-*.sh`/Playwright 脚本。
