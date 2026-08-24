# 故障排查

> **目的**: 按用户可见症状定位配置、依赖、工具、SSE 和前端问题。
> **最后核对**: 2026-08-24

## 应用无法启动

### 症状

启动时出现 PostgreSQL 连接失败、pgvector 不存在或 Chat Memory schema 错误。

### 检查

```bash
rg -n 'profiles:|SPRING_PROFILES_ACTIVE|datasource|pgvector' \
  src/main/resources/application*.yml
```

根 `application.yml` 不自动激活 profile。直接 Maven 启动默认使用 H2；如果需要显式
选择 H2 运行：

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run -DskipTests
```

使用 PostgreSQL 时确认数据库、凭证、`vector` 扩展和 1024 维 embedding 配置。
如果 `.env` 使用 `POSTGRES_USER`/`POSTGRES_PASSWORD`，优先通过 `./dev.sh` 启动；
它会补齐 Spring datasource 用户名/密码。直接运行 Maven 时需显式设置
`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD`。

## 模型调用失败

### 症状

出现 401、404、超时或 `No ChatModel configured for provider`。

### 检查

```bash
printf '%s\\n' "$LLM_PROVIDER" "$OPENAI_BASE_URL" "$OPENAI_MODEL"
java -version
mvn -version
```

确认 `LLM_PROVIDER` 与对应的密钥、base URL、model 一致。OpenAI-compatible 服务使用 `OPENAI_*` 变量；Anthropic 和 MiniMax 使用各自变量。不要把真实密钥写入日志或提交。

Embedding 404 时重点检查 `SILICONFLOW_URL`：它不应包含 `/v1`，Spring AI 会自动追加路径。

Embedding 返回 `400 parameter is invalid` 时，检查是否发送了不被当前模型支持的
`dimensions` 参数。`BAAI/bge-m3` 的推荐配置是保留 `SILICONFLOW_DIMENSIONS=1024`
但不要设置 `SILICONFLOW_DIMENSIONS_ENABLED=true`；应用会使用模型返回的原生维度。

## Skill 或工具调用失败

### 症状

模型编造 Skill 名称、调用错误 URL、重复调用 `httpRequest` 或无法读取 OpenAPI 参考文件。

### 检查顺序

1. 确认 Skill 文件位于 `src/main/resources/skills/<name>/SKILL.md`。
2. 确认 frontmatter 的 `name`、`description` 和 `links`。
3. 先调用 `loadSkill`，分层 Skill 再调用 `readSkillReference`。
4. 检查 `/api/agui/skills/api-index` 是否包含目标方法和路径。
5. 检查 Controller 映射、`/api/agui/skills/api-index` 和前端
   `frontend/lib/api-index-validation.mjs`。

普通 Agent 和 AG-UI Agent 的 HTTP 工具不同：

- 普通 `AgentService` 使用 `SkillTools`，可包含后端 HTTP 工具。
- AG-UI 只由后端执行 `loadSkill`/`readSkillReference`，浏览器执行 `httpRequest`。

不要让前后端同时注册同名 HTTP 工具；这会导致模型选择歧义或空工具结果循环。

## 购物车或写操作失败

### 症状

购物车接口返回 401/403，或前端没有显示确认界面。

### 检查

- 是否先通过 `/api/auth/login` 登录。
- 浏览器是否存在 `localStorage.auth_token`。
- 请求是否带 `Authorization: Bearer <token>`。
- 当前是否经过 AG-UI 前端 `useHumanInTheLoop`，而不是旧的 `buildHttpRequest`/确认模式。
- `/api/products/cart`、`/api/products/checkout` 是否使用当前认证用户。

当前 Token 是 Demo Base64 token，不是 JWT。旧报告中使用“JWT”一词的内容属于历史命名，不能据此修改实现。

### 登录后 AG-UI 仍提示未认证

这是当前前端状态同步的已知限制：

- `CopilotProvider` 初始化时读取一次 `localStorage.auth_token`，并监听 `storage` 与 `auth-changed` 事件。
- `AuthProvider` 当前登录和登出逻辑没有派发 `auth-changed` 自定义事件。
- 浏览器侧 `httpRequest` 每次读取 localStorage，可能仍能使用新 Token；但 CopilotKit BFF 的 headers 可能仍保留页面初始化时的旧值。

先刷新前端页面，再重新发起 AG-UI 请求。如果刷新后仍然 401/403，再检查 `JAVA_BACKEND_URL`、`NEXT_PUBLIC_JAVA_BACKEND_URL`、`Authorization` 头和后端认证日志。

## SSE 卡住、空响应或 `INCOMPLETE_STREAM`

### 检查

1. 后端端口是否为 8080，前端端口是否为 4000。
2. `/api/agui/health` 是否返回成功。
3. `/api/agui` 是否直接返回 `SseEmitter`，不要随意改为 `ResponseEntity<SseEmitter>`。
4. BFF 是否仍指向 `${JAVA_BACKEND_URL}/api/agui`。
5. CopilotKit v2 Provider 是否设置 `useSingleEndpoint`。
6. `SpringAIAgent` 是否在前端工具路径完成当前 run 并等待 `respond()`。
7. 是否达到 `maxToolCalls=5`。

普通 SSE 端点发送 JSON chunk 和 `[DONE]`；AG-UI SSE 由 `ag-ui-4j` 事件模型负责，不能混用两种事件格式。

## 前端构建失败

检查：

```bash
cd frontend
test -f scripts/transform-v2-css.mjs
test -f patches/copilotkit-v2-v3.css
npm run test:repository
npm run build
```

当前 CopilotKit 1.60.x 的 v2 CSS 需要项目跟踪的转换脚本和 webpack alias。
`npm run test:repository` 会验证生成 CSS 与锁定依赖一致；支持文件缺失或过期时，先运行
`npm ci --registry=https://registry.npmjs.org`，再重新执行该检查。

如果安装阶段报告 Node engine 不匹配，确认使用 Node.js 22.19+。如果仓库检查报告
lockfile 包含 `registry.npmmirror.com`，使用 npm 官方 registry 重新生成 lockfile，
不要依赖机器本地镜像配置。不要随意删除 `package.json` 中 Next 和旧 AI SDK 的
安全 overrides；它们分别覆盖 PostCSS/Sharp 和 Undici 的已知高危版本。生产依赖审计命令为：

```bash
npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
```

如果 Playwright 报告 Chromium executable 不存在，先运行
`npx playwright install chromium`。下载源不可用且机器已有 Chrome 时，可给测试命令
增加 `PLAYWRIGHT_BROWSER_CHANNEL=chrome`；不要因此跳过 DOM/网络断言。

传统内嵌页面的真实浏览器验证：

```bash
./dev.sh --backend-only
(cd frontend && npm run test:e2e:traditional)
./dev.sh --stop
```

该测试使用 `/api/chat/text`，不是 Next.js/CopilotKit 的 AG-UI `/api/agui`。若页面加载和
登录通过但聊天超时，先看 `DEV_RUNTIME_DIR` 下的后端日志，确认是否到达模型、是否执行
`loadSkill`/`httpRequest`，再检查外部模型响应。

## 多模态或转写失败

- 图片请求字段：`image`；音频请求字段：`audio`。
- 流式转写必须使用 `POST /api/transcribe/stream` 和 multipart 字段 `audio`。
- 确认 `VISION_*`、`TRANSCRIPTION_*` 配置完整。
- 真实验证需要 `TEST_IMAGE_PATH` 或 `TEST_AUDIO_PATH`。
- 观察临时文件是否在流完成/错误后被清理。

## 资料冲突

当文档与代码不一致时，按以下优先级处理：

1. Controller、Service、配置类和 `package.json`。
2. `src/main/resources/skills/`、prompt 和 API index。
3. 测试脚本与最新可复现结果。
4. 稳定指南。
5. `docs/drafts/`、旧报告和历史经验。

先修正事实来源或在稳定指南中明确边界，再补充新的诊断文字。
