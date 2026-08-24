# 运行时 Skill 发现 API 与 Demo 可观察性加固规划

> **状态**: 已实施
> **目的**: 为运行时 Skills 提供中性的只读发现 API，使开发者可以直接观察 Level 1
> 目录、Level 2 指令和 API index，同时保留旧 AG-UI 路径兼容。
> **最后核对**: 2026-08-24
> **前置基线**: [普通 Agent 写操作确认协议加固规划](ordinary-agent-mutation-confirmation-hardening-plan.md)
> **长期规则**: 遵循根目录 [AGENTS.md](../../AGENTS.md) 的规划、验收、真实 LLM 和三轮收敛流程

## 1. 决策摘要

本批建立一个与 Agent 传输协议无关的运行时 Skill 发现面：

1. 新增 `GET /api/skills`，返回按名称排序的 Level 1 Skill 目录；只包含发现所需的
   name、description、version、links、是否分层和 API 数量，不返回正文。
2. 新增 `GET /api/skills/{name}`，返回指定 Skill 的 Level 2 正文和该 Skill 对应的
   API index 条目；未知 Skill 返回 HTTP 404。
3. 新增 `GET /api/skills/api-index`，返回当前 `SkillRegistry` 的完整、稳定排序 API
   index，作为普通页面和新客户端的中性 URL 校验入口。
4. 保留 `GET /api/agui/skills/api-index`，但让它委托同一 catalog service；新旧端点
   的 JSON 必须深度相等，避免兼容路径与主路径漂移。
5. 嵌入式传统 Web UI 改用 `/api/skills/api-index`。Next.js/CopilotKit 当前仍可使用
   旧别名，本批不以 AG-UI/CopilotKit 改造为目标。
6. Level 3 reference 内容不通过 HTTP 暴露；仍由
   `SkillTools.readSkillReference` / `SkillCoreTools.readSkillReference` 经
   `SkillReferenceReader` 受限读取。Level 2 详情可以展示 API index 中已有的
   `referencePath`，帮助读者理解下一步，但不能绕过读取边界。
7. Swagger/OpenAPI 标题和描述更新为整个 Spring AI Skills Demo，而不是只描述商品
   API；新增发现端点必须出现在 `/v3/api-docs`。

本批的教育主线是：

```text
GET /api/skills
  -> 观察 Level 1 name/description 目录
  -> GET /api/skills/{name}
  -> 观察 Level 2 SKILL.md 正文与 links
  -> GET /api/skills/api-index
  -> 观察应用如何把 Skill 指令收敛为确定性 method/path allowlist
  -> 模型需要 Level 3 时仍调用 readSkillReference
```

## 2. 当前事实与问题

### 2.1 当前事实来源

运行时 Skill 的事实来源是：

- `src/main/resources/skills/*/SKILL.md`
- `src/main/resources/skills/*/references/**`
- `src/main/java/com/example/demo/agent/SkillRegistry.java`
- `src/main/java/com/example/demo/agent/SkillReferenceReader.java`

`SkillRegistry` 已经完成：

- classpath/文件系统 Skill 扫描；
- YAML frontmatter 解析；
- name、description、目录名和 links 校验；
- 平面和分层 API index；
- method/path 规范化、冲突检测和稳定排序；
- `all()` 的只读名称排序视图、`get(name)` 查询，以及 `getApiIndex()` 的稳定排序快照。

`getApiIndex()` 返回的新 `LinkedHashMap` 不会直接暴露 registry 内部 Map，但快照本身不是
不可变对象；catalog service 必须把条目映射为自己的 DTO，并返回保序的不可变副本。

因此新增发现 API 不应创建第二套扫描、解析或索引逻辑。

### 2.2 当前唯一 HTTP 发现入口命名错误

当前只有：

```text
GET /api/agui/skills/api-index
```

它定义在 `AgUiController` 中，但被以下非 AG-UI 组件使用：

- 嵌入式传统 Web UI；
- 普通 Agent 写操作确认的浏览器二次校验；
- 后端集成测试；
- 操作文档和排障文档。

API index 实际属于运行时 Skill 领域，不属于 AG-UI 协议。继续把它只放在 `/api/agui`
下会产生三个问题：

1. 读者容易误以为普通 Agent 必须依赖 AG-UI；
2. `AgUiController` 同时承担 Agent SSE 与通用 Skill catalog 职责；
3. 新客户端只能依赖一个带历史实现细节的路径。

### 2.3 当前没有可直接观察的 Level 1/Level 2 API

开发者若想理解渐进式披露，目前必须：

1. 阅读 Java `SkillRegistry`；
2. 手工打开 `src/main/resources/skills/`；
3. 或真实调用 LLM 并从日志推断 `loadSkill` 输出。

这不利于 Demo 教育作用。Level 1/Level 2 都是确定性的本地资源视图，应能在无 LLM、
无数据库、无认证的情况下通过 `curl` 或 Swagger 观察。

### 2.4 不应扩大 Level 3 权限

`readSkillReference` 当前只允许读取已注册 Skill 的 `references/` 下合法相对路径，并
拒绝绝对路径、目录跳转、编码绕过、反斜杠和超长路径。新增 HTTP catalog 时不能为了
“方便”增加：

- 任意 `GET /api/skills/{name}/references/**`；
- 文件系统路径参数；
- Shell/helper file 执行；
- 直接下载未登记资源。

Level 3 的权限边界必须保持不变。

## 3. API 与数据模型设计

### 3.1 `GET /api/skills`

返回 JSON array，按 `name` 升序：

```json
[
  {
    "name": "add-to-cart",
    "description": "将指定商品加入用户购物车",
    "version": "1.0",
    "links": [
      {
        "name": "checkout",
        "description": "结算购物车中的商品"
      }
    ],
    "hierarchical": false,
    "apiCount": 1
  }
]
```

规则：

- 不返回 Skill body，保持 Level 1 紧凑；
- links 顺序保持 SKILL.md 声明顺序；
- 没有 links 时返回空 array，不返回 `null`；
- `version` 可空；
- `hierarchical=true` 表示至少一个 API index 条目来自 operation reference；
- `apiCount` 是该 Skill 在当前 API index 中拥有的条目数；
- 不返回解析器内部 source path、未知 additional metadata 或认证信息。

### 3.2 `GET /api/skills/{name}`

返回：

```json
{
  "name": "search-products",
  "description": "搜索商品目录，支持关键词、分类、价格范围过滤",
  "version": "1.0",
  "links": [
    {
      "name": "get-product-detail",
      "description": "获取单个商品的详细信息"
    },
    {
      "name": "add-to-cart",
      "description": "将商品加入购物车"
    }
  ],
  "hierarchical": false,
  "apiCount": 1,
  "body": "# 商品搜索技能\n...",
  "apis": [
    {
      "skillName": "search-products",
      "path": "/api/products",
      "method": "GET",
      "description": "搜索商品目录，支持关键词、分类、价格范围过滤",
      "hierarchical": false
    }
  ]
}
```

规则：

- name 必须精确匹配已注册 Skill；
- 未知 name 返回 404 `ProblemDetail`，不返回空对象；
- body 是 `SkillRegistry` 已解析的 Markdown body，不含 YAML frontmatter；
- apis 按 `"METHOD path"` key 排序；
- 分层条目可包含现有 `referencePath`，但不包含 reference 文件正文；
- 不在 Controller 中重新解析 Markdown。

### 3.3 `GET /api/skills/api-index`

保持现有 JSON object 形状：

```json
{
  "GET /api/products": {
    "skillName": "search-products",
    "path": "/api/products",
    "method": "GET",
    "description": "搜索商品",
    "hierarchical": false
  }
}
```

必须满足：

- key 按字典序稳定；
- 字段与旧 `/api/agui/skills/api-index` 一致；
- 分层条目保留 `referencePath`；
- 新旧端点响应 JSON 深度相等。

## 4. 代码设计

### 4.1 Catalog service

新增：

```text
src/main/java/com/example/demo/service/RuntimeSkillCatalogService.java
```

职责：

- 只依赖 `SkillRegistry`；
- 把 registry 的 domain object 映射为只读 DTO；
- 集中构造 Level 1、Level 2 和 API index 视图；
- 保证排序与字段兼容；
- 找不到 Skill 时抛出 `ResponseStatusException(HttpStatus.NOT_FOUND, ...)`。

不把该逻辑放进 `AgUiController`，也不在 Controller 之间复制 map 构造。

### 4.2 DTO

新增明确的 API DTO，建议放在 `dto/`：

- `RuntimeSkillSummary`
- `RuntimeSkillDetail`
- `RuntimeSkillLink`
- `RuntimeSkillApiEntry`

`RuntimeSkillDetail` 可以组合 summary 字段、body 和 `List<RuntimeSkillApiEntry>`。DTO
使用 Java record，map/list 在 service 构造时复制为不可变视图。
`RuntimeSkillApiEntry` 使用 `@JsonInclude(JsonInclude.Include.NON_NULL)`，确保平面条目
不序列化 `"referencePath": null`，从而与旧端点手工 Map 的 JSON shape 保持一致。
API index 使用 `Collections.unmodifiableMap(new LinkedHashMap<>(...))` 一类保序只读副本，
不使用迭代顺序未承诺的普通 `Map.copyOf` 作为公开 JSON 的直接来源。

不直接序列化 `Skill`/`SkillMeta`，原因是：

- domain object 包含 `additionalMetadata`；
- 公开 API 不应随 YAML parser 模型变化；
- Level 1/Level 2 字段边界需要显式稳定。

### 4.3 Controller

新增：

```text
src/main/java/com/example/demo/controller/RuntimeSkillController.java
```

使用：

```text
@RestController
@RequestMapping("/api/skills")
@Tag(name = "运行时 Skills", ...)
```

端点：

- `GET /api/skills`
- `GET /api/skills/api-index`
- `GET /api/skills/{name}`

`/api/skills/api-index` 使用静态路径，Spring MVC 会优先于 `{name}`；测试仍应显式证明
它没有被当作 Skill name。

`AgUiController.apiIndex()` 改为委托 `RuntimeSkillCatalogService.apiIndex()`，保留原路径
和字段形状。AG-UI run/info/health 不变。

### 4.4 Security 与 OpenAPI

`SecurityConfig` 增加：

```text
GET /api/skills
GET /api/skills/**
```

公开只读访问。不得用宽泛的所有 method permit；当前 Controller 只有 GET，但 security
规则仍应显式限制 `HttpMethod.GET`。

`OpenApiConfig` 更新为：

- title：`Spring AI Skills Demo API`
- description：说明商品/PetStore API、运行时 Skill 发现和 Agent 演示；
- version 保持 `1.0`。

### 4.5 客户端迁移与兼容

迁移：

- `src/main/resources/static/index.html`
- `frontend/tests/traditional-ui-mock-e2e.mjs`
- `frontend/tests/traditional-ui-mutation-e2e.mjs`

从 `/api/agui/skills/api-index` 到 `/api/skills/api-index`。

本批不修改：

- `frontend/hooks/useHttpRequestTool.tsx`
- AG-UI/CopilotKit 工具注册；
- AG-UI SSE；
- `ag-ui-4j` 子模块。

原因：旧路径继续兼容，CopilotKit 迁移没有本批后端/Demo 教育主线的增量价值。稳定文档
会把旧路径标成兼容别名，并要求新代码使用中性路径。

## 5. 一次性验收测试设计

生产代码修改前，先完成以下测试。

### 5.1 后端集成测试

扩展 `BackendApiIntegrationTest`，一次覆盖：

1. 未认证 `GET /api/skills` 返回 200；
2. Level 1 array 按 name 排序，包含 6 个当前 Skill；
3. Level 1 不包含 `body`、`metadata`、`additionalMetadata` 或 `license`；
4. `search-products` 的 version 为 `1.0`、links 依次为
   `get-product-detail`/`add-to-cart`、`hierarchical=false`、`apiCount=1`；
5. `GET /api/skills/search-products` 返回 Level 2 body 和
   `GET /api/products` API 条目；
6. `GET /api/skills/swagger-petstore-openapi-3-0` 返回
   `hierarchical=true`，apis 中包含 `operations/getPetById.md` 等
   `referencePath`，但不包含 reference 正文；
7. `GET /api/skills/missing-skill` 返回 404 `ProblemDetail`；
8. `GET /api/skills/api-index` 没有被 `{name}` 捕获；
9. 新旧 API index JSON 深度相等；
10. `/v3/api-docs` 包含三个新端点。
11. 匿名 `POST /api/skills` 不会被只读公开规则放行。

已有“每个 API index 条目对应 Spring MVC handler”的断言继续保留。

### 5.2 Catalog service 单元测试

如果集成测试不能清楚定位排序/映射失败，新增
`RuntimeSkillCatalogServiceTest`。默认优先依赖集成测试，避免重复断言简单 getter。

只有以下逻辑值得单测：

- summary 的 `hierarchical`/`apiCount` 聚合；
- detail API 条目稳定排序；
- domain metadata 不泄漏到 DTO。

### 5.3 传统页面 Mock Playwright

更新 `traditional-ui-mock-e2e.mjs`：

- Mock 新 `/api/skills/api-index`；
- 确认显示和执行阶段都请求新路径；
- 断言没有请求旧 `/api/agui/skills/api-index`；
- 既有取消、最新 token、失败状态和非法 confirmation 场景保持通过。

`frontend/tests/skills-url-mock.mjs` 测试的是 CopilotKit hook，继续使用旧兼容路径，不因
本批修改。

### 5.4 真实 LLM

Mock 和构建硬门槛通过后，使用 `.env`：

1. `./dev.sh --backend-only` 启动 PostgreSQL profile；
2. `curl` 验证 `/api/skills`、`/api/skills/{name}`、新旧 API index；
3. 运行 `npm run test:e2e:traditional:mutation`；
4. 观察真实模型完成 `loadSkill -> buildHttpRequest`；
5. 确认页面网络请求使用新 `/api/skills/api-index`；
6. 确认写操作、结果解释和购物车清理仍通过；
7. 停止服务并确认端口释放。

本批不需要额外真实 PetStore LLM 调用，因为 Level 3 Tool Calling 未改变；分层 Skill 的
真实 `readSkillReference` 回合留给下一批专项规划。

## 6. 文档更新

稳定文档同步：

- `README.md`：在首屏能力/文档入口中加入无需 LLM 的运行时 Skill 观察路径；
- `AGENTS.md`：运行时 Skill 发现端点和新旧兼容边界；
- `docs/README.md`：把运行时 Skill 发现连接到架构、API 和学习路径；
- `docs/ARCHITECTURE.md`：catalog service 与 Level 1/2/API index 观察路径；
- `docs/knowledge-and-skills.md`：说明发现 API 与
  `loadSkill`/`readSkillReference` 的 Level 1/2/3 对应关系；
- `docs/learning-path.md`：无需 LLM 的 `curl` 渐进式披露实验；
- `docs/rest-api.md`：三个新端点和旧兼容别名；
- `docs/OPERATIONS.md`：中性端点命令；
- `docs/HARNESS.md`：后端集成与真实传统 UI 验证；
- `docs/troubleshooting.md`：优先检查新 API index；
- `docs/drafts/README.md`：索引本规划。

历史已实施规划中出现旧路径时不批量改写；它们是当时事实。只在当前稳定文档和本批
实施记录中说明新旧关系。

## 7. 验证硬门槛

### 后端

```bash
git diff --check
mvn clean compile test-compile
mvn -Dtest='BackendApiIntegrationTest,SkillRegistryTest,SkillReferenceReaderTest' test
mvn test
```

### 前端

```bash
cd frontend
npx tsc --noEmit
npm run test:repository
npm run build
npm run test:skills
npm run test:e2e:traditional:mock
```

### 真实环境

```bash
./dev.sh --backend-only
curl -s http://localhost:8080/api/skills
curl -s http://localhost:8080/api/skills/search-products
curl -s http://localhost:8080/api/skills/api-index
(cd frontend && npm run test:e2e:traditional:mutation)
./dev.sh --stop
```

验收证据不使用截图。

## 8. 三轮实现收敛审查

硬门槛和真实验证通过后，执行互不重叠的三轮：

1. **API 正确性与兼容**：DTO shape、排序、404、OpenAPI、新旧 index 深度相等、
   Controller 职责。
2. **安全与边界**：只读公开规则、metadata 泄漏、Level 3 不被 HTTP 暴露、路径变量、
   旧 AG-UI 行为不变。
3. **测试与教学交付**：集成测试、Mock/真实浏览器、新文档发现性、提交和子模块状态。

发现正确性、安全、兼容、成本或数据一致性问题时立即修复，重跑硬门槛并将实现审查
计数重置为 0。风格或后续可选增强不触发发散式修改。

## 9. 风险、默认值和可逆边界

| 风险/未知项 | 推荐默认 | 理由与可逆边界 |
|---|---|---|
| 是否直接返回 `Skill` domain object | 否，使用显式 DTO | 防止 parser metadata 意外成为 API 契约 |
| Level 1 是否返回 body | 否 | 保持渐进式披露和紧凑目录 |
| Level 2 是否返回 frontmatter 原文 | 否 | 返回已验证字段和 body，避免重复解析契约 |
| 是否 HTTP 暴露 references | 否 | 保持受限 `readSkillReference` 权限边界 |
| 旧 AG-UI index 是否删除 | 否 | 保持 Next.js/CopilotKit 和外部调用兼容 |
| 是否本批迁移 CopilotKit hook | 否 | 旧别名可用，本批聚焦后端和普通 Demo |
| 是否需要 ETag/缓存 | 暂不增加 | 当前 6 个 Skill、Demo 规模；未来可在不改 JSON shape 下增加 |
| 是否增加写管理 API | 否 | Skills 是 classpath/文件系统启动资源，不是运行时 CRUD 数据 |

## 10. 中断恢复与完成标准

若任务中断：

1. `git status --short --branch`；
2. 阅读本文状态和最后实施记录；
3. 从 plan 工具第一个未完成项继续；
4. 不新增第二套 Skill parser、API index 或 reference reader。

完成标准：

- 规划连续三轮无修改审查通过；
- 验收测试先于生产实现完成；
- 新中性端点、旧兼容端点和传统页面迁移完成；
- 后端/前端硬门槛、真实 LLM 传统 UI 闭环通过；
- 实现连续三轮无修改审查通过；
- 稳定文档和本文实施记录更新；
- commit、push 成功；
- 主仓库和两个子模块干净。

## 11. 实施记录

### 11.1 已完成范围

- 新增 `RuntimeSkillController`、`RuntimeSkillCatalogService` 和 4 个显式 DTO；
- 新增 Level 1 `/api/skills`、Level 2 `/api/skills/{name}` 与中性
  `/api/skills/api-index`；
- 旧 `/api/agui/skills/api-index` 已委托同一 catalog service，新旧 JSON 深度相等；
- Security 只公开两个 `GET /api/skills` matcher，不公开写方法；
- OpenAPI 标题更新为 `Spring AI Skills Demo API`，三个新路径已进入 `/v3/api-docs`；
- 嵌入式传统页面迁移到中性 index，CopilotKit hook 继续使用旧兼容别名；
- README、AGENTS 和稳定架构/API/知识/学习/运维/验证/排障文档已同步。

### 11.2 确定性与 Mock 证据

2026-08-24 已通过：

| 验证 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| `mvn clean compile test-compile` | 通过；131 个主源码、14 个测试源码 |
| `mvn -Dtest='BackendApiIntegrationTest,SkillRegistryTest,SkillReferenceReaderTest' test` | 25/25 |
| `mvn test` | 52/52 |
| `cd frontend && npx tsc --noEmit` | 通过 |
| `cd frontend && npm run test:repository` | 通过 |
| `cd frontend && npm run build` | 通过；只有既有 AI SDK dynamic dependency warning |
| `cd frontend && npm run test:skills` | 通过 |
| `cd frontend && npm run test:e2e:traditional:mock` | 通过 |

`BackendApiIntegrationTest` 现在覆盖目录排序、字段隔离、平面/分层详情、19 个 PetStore
operation 指针、404 ProblemDetail、静态 index 路由、新旧 JSON 相等、OpenAPI 路径和
只读 Security。传统页面 Mock 证明显示与执行阶段都请求中性 index，且不请求旧别名。

### 11.3 真实环境证据

2026-08-24 使用主工作区 `.env` 通过 `./dev.sh --backend-only` 启动：

- profile：`postgresql`；
- PostgreSQL `localhost:5432` 连接成功；
- ChatModel：OpenAI-compatible `grok-4.5`；
- 启动加载 6 个 Skill、24 个 API index 条目；
- `curl`/JSON 断言验证 Level 1 目录为 6 项、`search-products` 有 1 个 API、
  PetStore 有 19 个 API、新旧 index 均有 24 项且深度相等；
- `/v3/api-docs` 的标题和三个 Runtime Skill 路径正确；
- `npm run test:e2e:traditional:mutation` 通过真实
  `loadSkill -> buildHttpRequest -> 确认按钮 -> 业务 POST -> 结果解释 -> 购物车清理`
  闭环，并证明取消不发送业务请求、确认使用最新浏览器 token；
- `RUN_LIVE_LLM_TESTS=true mvn -Dtest.excluded-groups=container
  -Dtest=OpenAiCompatibleApiLiveTest test` 明确执行 1 个真实 provider 测试并通过。

测试结束后已执行 `./dev.sh --stop`，8080 和 4000 端口均已释放。没有运行额外 PetStore
真实 LLM 回合，因为本批未修改 `readSkillReference` 或 Level 3 工具执行链路。
