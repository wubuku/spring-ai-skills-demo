# API 结果解释链路加固规划

> **状态**: 已实施
> **目的**: 加固传统 Web UI 在真实 API 请求之后调用 `/api/explain-result` 的后端闭环，
> 让读者能清楚看到“Skill API index 提供确定性描述，LLM 负责自然语言解释，失败时有
> 状态正确的降级”的 Spring AI Demo 用法。
> **最后核对**: 2026-08-24
> **前置基线**: [后端知识库与 Skill 资源契约加固规划](backend-knowledge-resource-hardening-plan.md)
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收和三轮收敛原则](../../AGENTS.md)

## 1. 决策摘要

本批只处理 API 结果解释这一条后端/Demo 教学主线，不扩展到 CopilotKit、AG-UI、商品
库存模型、RAG 或多模态：

1. **修正降级语义**：当解释模型调用抛异常、返回 `null` 或空白时，根据真实 HTTP 状态
   返回 `✅`（2xx）或 `❌`（非 2xx）的简洁说明；不再把失败 API 伪装成“操作已完成”。
2. **修正 Skill 发现提示**：直接匹配不到 API 文档时，兜底 prompt 从当前
   `SkillRegistry` 生成真实的 Skill 目录，删除不存在的 `product-store` 示例，并明确
   该专用解释 ChatClient 不执行工具调用，避免 prompt 承诺一个未注册的 `loadSkill`。
3. **补齐可证明测试**：新增 ExplainResultService 的确定性单元测试，并在后端端到端
   集成测试中验证 `/api/explain-result` 在模型失败时仍返回正确的成功/失败标记。
4. **补齐学习入口**：在稳定学习主线和 REST API 参考中明确结果解释是“已执行 API 的
   展示层后处理”，并链接到对应源码和测试，避免读者只看到端点而发现不了实现边界。

推荐默认是保留当前“解释服务不参与 Agent 对话记忆和 Tool Calling”的架构。结果解释
只接收前端已经执行完的 API 结果；它不应重新执行业务 API，也不应通过另一个模型工具
循环改变业务状态。

## 2. 当前事实与问题

### 2.1 当前请求链路

```text
传统 Web UI 执行 httpRequest
  -> 收集 method、url、queryParams、statusCode、responseBody
  -> POST /api/explain-result
  -> ExplainResultController
  -> ExplainResultService
       -> SkillRegistry 精确/路径参数匹配 API index
       -> getFullApiDescription(entry) 取得 Skill API 文档
       -> PromptLoader 渲染 explain-result prompt
       -> 专用 ChatClient 调用 LLM
  -> 返回 Markdown 解释；前端失败时展示原始响应
```

已确认的源码位置：

- Controller：[ExplainResultController](../../src/main/java/com/example/demo/controller/ExplainResultController.java)
- 服务：[ExplainResultService](../../src/main/java/com/example/demo/service/ExplainResultService.java)
- 请求模型：[ExplainRequest](../../src/main/java/com/example/demo/model/ExplainRequest.java)
- API 描述来源：[SkillRegistry](../../src/main/java/com/example/demo/agent/SkillRegistry.java)
- Prompt 模板：
  `src/main/resources/prompts/explain-result/api-explanation-prompt.template`
- 传统页面调用：
  `src/main/resources/static/index.html` 的 `executeHttpRequest`
- 当前端点说明：[REST API 参考](../rest-api.md)
- 学习入口：[Spring AI 学习主线](../learning-path.md)

### 2.2 已确认的问题

1. `ExplainResultService.explainResult` 的 catch 分支固定返回
   `✅ 操作已完成\n\n` 加原始响应体。当前 API 返回 4xx/5xx 而解释 LLM 又失败时，
   用户会看到与真实状态相反的成功符号。
2. `.content()` 返回 `null` 或空白时当前服务直接返回该值，没有稳定降级。
3. `buildPrompt` 在精确和模式匹配都失败时写入：
   `请使用 loadSkill 工具查找相关的 Skill 文档。可用的技能包括:
   product-store、swagger-petstore-openapi-3-0 等。`
   其中 `product-store` 不是当前注册 Skill。
4. `ExplainResultService` 通过 `ChatClient.Builder.build()` 创建独立客户端，当前构造
   没有设置 `SkillsAdvisor`、`SkillTools` 或 `ToolCallAdvisor`。因此 fallback prompt
   不应暗示这个客户端可以自动调用 `loadSkill`。
5. 当前测试没有直接覆盖 `ExplainResultService`，也没有通过 HTTP 端点验证“模型失败时
   成功/失败状态标记保持正确”。现有 `BackendApiIntegrationTest` 只覆盖聊天、Skill
   API index、认证和购物车确认闭环。

### 2.3 保留的正确行为

- 已匹配 API 时，优先使用 `SkillRegistry.getFullApiDescription`，不要让模型自行猜测
  API 文档。
- `ExplainRequest` 仍由 Jakarta Validation 限制 method/url、状态码和响应体大小。
- 解释服务不调用业务 HTTP API，不写入购物车，不改变 Agent 对话记忆。
- LLM 正常返回非空解释时，保持原有模型输出，不额外包裹或改写 Markdown。
- 前端 `/api/explain-result` 失败时继续回退展示原始 response body；本批不改变前端协议。

## 3. 实施范围与默认决策

### P0-A：状态正确的降级输出

**修改文件**

- `src/main/java/com/example/demo/service/ExplainResultService.java`
- `src/test/java/com/example/demo/service/ExplainResultServiceTest.java`
- `src/test/java/com/example/demo/BackendApiIntegrationTest.java`

**规则**

1. 把模型输出赋值给局部变量；非空白时原样返回。
2. 抛异常、`null` 或空白输出都调用同一个状态感知 fallback。
3. fallback 使用 `200 <= statusCode < 300` 判定成功：
   - 成功以 `✅` 开头；
   - 非成功以 `❌` 开头。
4. fallback 文本包含 method、url、HTTP 状态和原始 response body；不声称未被证据支持的
   业务成功。
5. 保留现有日志级别和“返回解释而非抛给前端”的容错策略。

**默认理由**

- HTTP 状态是该服务唯一可靠的成功/失败信号，不能依赖 LLM 是否可用；
- 同一 fallback 处理异常和空输出，避免两个失败路径语义分叉；
- 结果解释是展示层能力，解释失败不应让已经完成的真实 API 请求在 UI 上变成 500。

**回滚边界**

如果未来前端需要结构化错误码，可在响应模型中新增字段，但本批继续返回纯文本，避免
改变传统页面和 CopilotKit 外部调用方的协议。

### P0-B：真实 Skill 目录的解释 prompt

**修改文件**

- `src/main/java/com/example/demo/service/ExplainResultService.java`
- `src/test/java/com/example/demo/service/ExplainResultServiceTest.java`
- `src/main/resources/prompts/explain-result/api-explanation-prompt.template`（仅在需要
  时；优先由服务注入动态提示）

**规则**

1. 保留已匹配 API 的完整文档注入逻辑。
2. 未匹配时，从 `SkillRegistry.all()` 按稳定名称顺序生成 `<available_skills>` 目录，
   内容为当前 frontmatter name 和 description。
3. 未匹配提示明确“当前解释客户端不执行工具；目录仅用于理解上下文”，不得写入
   不存在的 Skill 名称或可执行的 `loadSkill` 承诺。
4. 不把完整所有 Skill 正文注入 prompt；未匹配分支只注入 Level 1 metadata，避免重新
   破坏渐进式披露的 Demo 教学边界。
5. 已匹配分支的 API 文档、状态、响应体和查询参数占位符继续由 PromptLoader 处理。

**默认理由**

- `SkillRegistry` 是当前唯一真实 Skill 来源，动态读取能避免提示词与资源漂移；
- 解释客户端不需要工具调用，直接提供轻量目录比注册第二套 Agent 工具循环更低风险；
- Level 1 目录符合本项目“先发现、后加载”的教学模型，同时不会把所有 API 细节塞进
  每次解释请求。

**回滚边界**

如果未来要让解释服务真正读取 Skill references，应单独引入受限的只读工具客户端并补
工具循环测试；不能把本批的目录提示改回虚假的 `loadSkill` 承诺。

### P1：确定性和端到端验收

**新增/修改测试**

| 场景 | 测试 | 通过标准 |
|---|---|---|
| 成功 fallback | `ExplainResultServiceTest` | LLM 异常时 2xx 结果以 `✅` 开头并含端点/状态/正文 |
| 失败 fallback | `ExplainResultServiceTest` | LLM 异常时 4xx/5xx 结果以 `❌` 开头，不含“操作已完成”成功语义 |
| 空模型输出 | `ExplainResultServiceTest` | `null`/空白输出进入同一 fallback |
| 未匹配 Skill 目录 | `ExplainResultServiceTest` | prompt 含当前真实 Skill name/description，不含 `product-store`，不承诺 `loadSkill` |
| 已匹配 API 回归 | `ExplainResultServiceTest` | prompt 仍含 API index 对应的完整 API 描述 |
| HTTP 端点闭环 | `BackendApiIntegrationTest` | `/api/explain-result` 在模拟模型异常时对 2xx/5xx 返回正确标记 |

离线测试使用 Mockito ChatModel，不依赖真实 LLM、Embedding、PostgreSQL 或浏览器；
端到端 Playwright 仍在基本门槛阶段复跑已有传统页面 Mock 套件。实现完成后使用真实
LLM 做一次传统页面查询闭环，确认正常模型输出路径没有回归。

### P1：稳定文档与发现路径

**修改文件**

- `docs/learning-path.md`
- `docs/rest-api.md`
- `docs/drafts/README.md`（本规划已在规划阶段加入索引）

**文档要求**

- 在学习主线中说明：`httpRequest` 完成真实 API 请求后，传统页面可把结果交给
  `/api/explain-result`；解释服务优先从 Skill API index 读取确定性文档，再调用独立
  ChatClient 生成自然语言，失败时按 HTTP 状态降级。
- 在 REST API 参考中补充请求字段、纯文本响应、模型失败仍返回 2xx HTTP 响应的展示层
  语义，并链接 `ExplainResultController`、`ExplainResultService` 和测试。
- 不把结果解释描述成 Agent 工具调用、RAG 或业务 API 执行；明确 AG-UI/CopilotKit
  不在本批范围。

## 4. 实施顺序

1. 先完成本文三轮连续无修改规划审查；
2. 一次性新增 `ExplainResultServiceTest` 的验收断言，并扩展已有后端集成测试；
3. 实施状态感知 fallback 和空输出回退；
4. 实施动态 Skill Level 1 目录提示，删除 stale `product-store`/虚假工具承诺；
5. 更新稳定学习主线和 REST API 参考；
6. 运行基本硬门槛：

   ```bash
   git diff --check
   mvn clean compile test-compile
   mvn -Dtest='ExplainResultServiceTest,BackendApiIntegrationTest,ProductServiceTest,SkillRegistryTest,KnowledgeBaseInitializerTest' test
   mvn test
   ```

7. 运行前端 `npm run test:repository`、`npx tsc --noEmit`、`npm run build`、
   `npm run test:skills`、`npm run test:e2e:traditional:mock`；
8. 使用 `.env` 的真实 LLM 配置启动 `./dev.sh --backend-only`，执行真实传统页面
   Playwright，观察日志确认 `/api/explain-result` 正常或状态正确降级；
9. 硬门槛通过后，对本批生产类、测试、prompt、稳定文档和本规划执行三轮固定范围只读审查；发现
   会影响正确性、成本、安全、兼容性或数据一致性的缺陷，修复后从硬门槛和第 1 轮重新
   开始；
10. 更新本文实施记录，提交、push，并确认主工作区和子模块干净。

## 5. 不纳入本批

- CopilotKit/AG-UI 端点、SSE 事件、前端 `httpRequest` 工具实现；
- ProductService 库存扣减、订单持久化、金额类型重构和购物车 API 重新设计；
- Spring AI 版本升级或社区 `SkillsTool` 替换；
- 真实 API 结果解释质量 benchmark、提示词注入专项治理和多语言输出；
- 为解释服务注册新的 Tool Calling 循环或让它重新执行业务 API；
- ExplainResultController 的响应格式改造和前端视觉重构。

## 6. 中断恢复与回滚

- 若中断，先检查 `git status --short`，从本文第 4 节第一个未完成步骤继续；
- 如果测试已写而实现未写，优先运行 `ExplainResultServiceTest`，确认失败原因仍对应本批
  契约；
- fallback 改动可以独立回滚，不影响正常 LLM 输出和 SkillRegistry API index；
- 动态 Skill 目录只改变未匹配 API 的解释 prompt，不改变业务 API、工具协议或 Agent
  记忆；
- 本规划的验证记录必须在提交前更新，不能用“代码 review 通过”替代自动化证据。

## 7. 实施记录

### 实施结果

- `ExplainResultService` 已统一处理模型异常、`null` 和空白输出：
  - 原始 API 状态为 2xx 时以 `✅` 降级；
  - 原始 API 状态为非 2xx 时以 `❌` 降级；
  - 降级文本包含 method、URL、HTTP 状态和原始响应体。
- 未匹配 API 时从 `SkillRegistry.all()` 生成稳定排序的 Level 1 Skill 目录；
  解释客户端明确为只生成说明、不执行工具调用，不再引用不存在的 `product-store`。
- 已匹配 API 仍使用 Skill API index 的完整 API 区域；非空模型输出原样返回。
- `docs/learning-path.md` 和 `docs/rest-api.md` 已增加结果解释学习入口与边界说明。

### 自动化验证

以下验证均在 2026-08-24 完成：

| 验证 | 结果 |
|---|---|
| `git diff --check` | 通过 |
| `mvn -Dtest=ExplainResultServiceTest test` | 8/8 通过 |
| `mvn -Dtest=BackendApiIntegrationTest test` | 6/6 通过 |
| `mvn clean compile test-compile` | 通过 |
| 本批相关 Maven 回归 | 32/32 通过 |
| `mvn test` | 45/45 通过 |
| `cd frontend && npx tsc --noEmit` | 通过 |
| `cd frontend && npm run build` | 通过；只有既存依赖动态依赖 warning |
| `cd frontend && npm run test:repository` | 通过 |
| `cd frontend && npm run test:skills` | 通过 |
| `cd frontend && npm run test:e2e:traditional:mock` | 通过 |

### 真实 LLM 与浏览器验证

- 使用 `.env` 启动 `./dev.sh --backend-only`，实际 profile 为 `postgresql`；
  PostgreSQL、Skill API index（24 个端点）和 `grok-4.5` 均成功初始化。
- 真实 `POST /api/explain-result` 调用返回非空 Markdown；相对 URL 和前端实际产生的同源
  绝对 URL 均已验证。绝对 URL 调用返回了
  `✅ **成功查询产品列表**` 和 Sony WH-1000XM5 结果，日志确认命中
  `GET http://localhost:8080/api/products?category=耳机` 的 Skill API 描述。
- 真实嵌入式传统 Web UI Playwright 通过：页面加载、登录、
  `POST /api/chat/text`、DOM 商品结果和浏览器无 console error。
- 额外尝试真实写操作确认闭环时，模型没有在限定观察窗口内稳定产出页面可解析的确认
  元数据；已主动终止等待，避免把模型行为不稳定误判为本批结果解释缺陷。该实验不影响
  已通过的结果解释真实 HTTP 调用和传统页面只读聊天证据；写操作确认链路属于后续普通
  Agent/Demo 教学加固候选。

### 三轮收敛审查与交付

三轮审查在硬门槛通过后进行，审查范围限定为本规划、ExplainResultService、
ExplainResultServiceTest、BackendApiIntegrationTest、两份稳定文档和相关 prompt。
在修复绝对 URL 匹配缺陷并重新通过硬门槛后，连续三轮均无需要修改的正确性、兼容性、
安全、成本或数据一致性问题：

1. 12:47：正确性与 API 兼容性，无修改。
2. 12:47：安全与一致性，无修改。
3. 12:48：测试与交付，无修改。

提交和 push 完成后补录 commit。
