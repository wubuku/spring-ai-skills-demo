# Prompt 资源与 fallback 契约加固规划

> **状态**: 已实施、验证、三轮审查、待提交
> **目的**: 消除普通 Agent 提示词资源与 `PromptLoader` Java fallback 的行为漂移，
> 使资源缺失时的降级路径仍然符合当前 Spring AI Tool Calling、Skills 渐进式披露和写操作
> 确认协议，并为读者提供可复现的实现与测试入口。
> **最后核对**: 2026-08-24
> **范围**: 普通 Agent PromptLoader、SkillsAdvisor 提示词资源、确定性测试和稳定文档导航
> **明确排除**: AG-UI/CopilotKit 运行时行为、模型 Provider 升级、Skill 文件格式、业务 API
> 和前端交互。
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收和三轮收敛原则](../../AGENTS.md)。

## 1. 决策摘要

本批采用以下默认方案：

1. 保留 `src/main/resources/prompts/` 中的 classpath 模板作为正常运行时的唯一事实来源。
2. 保留 `PromptLoader` 中的 Java fallback，作为资源打包错误、测试 fixture 或受限运行环境
   下的可用降级路径；不把资源缺失降级成空字符串。
3. 将 fallback 中的 SkillsAdvisor system、frontend mode rules 和 backend mode rules
   与当前资源模板逐字对齐。
4. 新增 `PromptLoaderTest`，使用正常 classpath ResourceLoader 和“所有资源均不存在”的
   ResourceLoader，逐个比较三份 SkillsAdvisor 模板；测试先于生产修改编写并确认当前失败。
5. 在稳定文档中明确 Prompt 的事实来源、fallback 的用途、测试入口和修改同步规则，让读者
   能从学习主线发现“提示词、工具 schema、后端门禁必须一起理解”。

不采用“删除 fallback”作为本批方案。删除 fallback 会让资源缺失时得到空提示词，故障更
隐蔽，也削弱这个项目对 Prompt 外置和可测试降级的教学价值。也不引入新的 Prompt DSL、
配置中心或运行时热加载；这些变化与本批缺口无关且扩大回滚面。

## 2. 当前实现上下文

### 2.1 正常加载链路

`SkillsAdvisor` 在每次 ChatClient 请求前：

```text
SkillsAdvisor.before(...)
  -> 根据 execution-mode 选择规则模板
  -> PromptLoader.getPrompt(rulesPath)
  -> PromptLoader.getPrompt(system-prompt.template, placeholders)
  -> 将完整 system message 注入 ChatClient request
```

普通 `AgentService` 在 advisor context 中传入：

```text
skills.execution-mode = backend
```

因此普通 Agent 使用：

- `prompts/skills-advisor/system-prompt.template`
- `prompts/skills-advisor/backend-mode-rules.template`

未传 `skills.execution-mode=backend` 时，`SkillsAdvisor` 选择：

- `prompts/skills-advisor/system-prompt.template`
- `prompts/skills-advisor/mode-rules.template`

后者是 AG-UI/浏览器侧 `httpRequest` 的兼容规则。本批只修复其 fallback 文本与资源版本
的一致性，不改变 AG-UI 执行器。

### 2.2 PromptLoader 当前行为

文件：[PromptLoader.java](../../src/main/java/com/example/demo/service/PromptLoader.java)

当前 `getPrompt(resourcePath, placeholders)` 的优先级是：

1. `templateCache`；
2. `classpath:` 资源；
3. `defaultPrompts` 中以同一路径登记的 Java fallback；
4. 没有 fallback 时返回空字符串并记录 warning。

当前存在的具体缺口：

- `DEFAULT_SKILLS_ADVISOR_MODE_RULES` 仍使用旧的 `params/body` 字符串协议和旧示例，
  资源模板已经包含分层 Skill、`readSkillReference`、单次业务 API 和结果呈现的当前规则。
- `DEFAULT_SKILLS_ADVISOR_BACKEND_MODE_RULES` 没有资源模板中的请求级已加载 Skill 门禁说明、
  `readSkillReference(skillName, relativePath)` 调用顺序和更具体的 JSON 对象示例。
- 正常启动读取资源，因此多数开发者不会立即看到该问题；但构建包缺资源、测试自定义
  `ResourceLoader` 或未来资源路径误改时，fallback 会改变模型行为。

### 2.3 现有边界和不可重复事实

- `PromptLoader` 不是 Spring AI 内置类，是本项目用于外置提示词和降级的服务。
- `SkillsAdvisor` 的 backend/frontend 模式分别对应两套工具注册边界；不能把两套规则合并
  成一份模糊提示词。
- 普通 `SkillTools` 现在要求请求级 `SkillLoadSession`，并强制执行：

```text
loadSkill -> readSkillReference（分层 Skill）-> httpRequest/buildHttpRequest
```

- 资源模板是模型行为的直接输入；Java fallback 必须遵守同一工具名、参数形状和门禁语义。
- 不应在 fallback 中包含真实 API key、用户 token、完整用户输入或可变环境信息。

## 3. 实施设计

### 3.1 生产代码改动

修改：

- `src/main/java/com/example/demo/service/PromptLoader.java`

只调整以下三个常量：

1. `DEFAULT_SKILLS_ADVISOR_SYSTEM_PROMPT`
2. `DEFAULT_SKILLS_ADVISOR_MODE_RULES`
3. `DEFAULT_SKILLS_ADVISOR_BACKEND_MODE_RULES`

要求：

- fallback 与对应 classpath 模板的公开返回值逐字一致，包括中文标点、代码示例和内部
  换行；由于当前资源读取使用 `BufferedReader.lines()`，资源文件末尾换行不会进入返回值，
  Java text block 常量必须使用 `.stripTrailing()` 去掉仅由 text block 语法产生的尾部换行；
- Java text block 保持 ASCII 代码标识符和现有中文文件编码；
- 不修改 `getPrompt` 的缓存、占位符替换和未知资源行为；
- 不改变 `defaultPrompts` 的 key；
- 不把资源读取改成反射、文件系统绝对路径或机器本地目录。

“逐字一致”指 `PromptLoader` 返回的字符串与资源读取后返回的字符串一致。测试比较服务
公开返回值，不比较 Java 源码字面量，也不把资源文件末尾换行当作模板正文的一部分。

### 3.2 验收测试

新增：

- `src/test/java/com/example/demo/service/PromptLoaderTest.java`

测试设计：

1. `classpathResourceAndFallbackStayIdenticalForSkillsAdvisorTemplates`
   - 用 `DefaultResourceLoader` 创建正常实例；
   - 用 Mockito `ResourceLoader` 创建 fallback-only 实例；
   - 对三份 SkillsAdvisor 模板调用 `getPrompt(path)`；
   - 断言三份返回值完全相等。
2. `replacesPlaceholdersAfterLoadingTemplate`
   - 使用已存在的 system prompt 资源；
   - 替换 `{{SKILL_LIST}}`、`{{API_BASE_URL}}`、`{{HTTP_TOOL_NAME}}`、
     `{{LOADED_CONTEXT}}` 和 `{{MODE_RULES}}`；
   - 断言占位符被替换且未误删无关文本。
3. `cachesTheSelectedTemplateUntilCacheIsCleared`
   - 使用可控的 mock ResourceLoader；
   - 首次读取资源后改变 Resource 返回内容；
   - 断言缓存仍返回第一次内容；
   - 调用 `clearCache()` 后断言重新加载。

fallback-only ResourceLoader 必须返回 `Resource.exists() == false` 或
`isReadable() == false`，不得通过修改 classpath、删除 target/classes 或写临时源码来
制造测试条件。测试必须不依赖真实模型、数据库或网络。

阶段顺序：

```text
写 PromptLoaderTest
  -> 运行新增测试，当前 fallback 漂移应失败
  -> 对齐 PromptLoader fallback
  -> 重新运行测试
```

不新增前端测试；本批生产行为只影响普通/AG-UI prompt 资源的 fallback，现有前端 Mock
Playwright 不会覆盖资源缺失分支。

### 3.3 文档改动

更新：

- `AGENTS.md`
- `docs/learning-path.md`
- `docs/knowledge-and-skills.md`
- `docs/HARNESS.md`
- `docs/drafts/README.md`

文档目的：

- 在 Agent 导航中增加“Prompt 资源与 fallback 契约”近距离入口；
- 在学习主线的 Skills 步骤中说明：
  - `SkillsAdvisor` 选择模式模板；
  - `PromptLoader` 优先读取 classpath 资源；
  - fallback 只用于资源不可用时；
  - `PromptLoaderTest` 证明两条路径一致；
- 在知识与 Skills 文档中把 Prompt、工具签名和后端门禁列为同一教育契约；
- 在验证手册中增加 `PromptLoaderTest` 的默认离线测试命令；
- 在草稿索引中登记本规划的状态和实施结果。

不复制完整模板正文；文档只链接到模板、源码和测试，避免形成第二份事实来源。

## 4. 风险、默认值和回滚边界

| 风险/未知项 | 推荐默认值 | 理由 | 可逆边界 |
|---|---|---|---|
| 模板资源与 Java fallback 需要长期双写 | 保留双写，并用 exact-equality 测试锁定 | 资源提供可编辑性，fallback 保证缺失时仍可用 | 未来可将 fallback 生成化，但需保持同一测试契约 |
| Java text block 与 Markdown 尾换行不同 | fallback 常量使用 `.stripTrailing()`，以 `getPrompt()` 输出做 exact-equality | 当前资源读取不保留尾换行；避免把格式差异误判为语义差异 | 只要公开返回值一致即可 |
| fallback 可能永远未被正常启动使用 | 仍然测试 fallback-only 分支 | 资源丢失是低频但高影响的部署/打包故障 | 可单独删除 fallback，但必须先设计空资源的 fail-fast |
| frontend mode rules 属于 AG-UI 边界 | 只对齐现有资源，不改工具执行器 | 本批不扩大 AG-UI 加固范围 | 未来 AG-UI 重构另开规划 |
| 新增测试使用 Mockito mock Resource | 只控制 ResourceLoader，不 mock PromptLoader | 测试真实加载、缓存和 fallback 分支 | 不影响生产 Bean 装配 |

安全边界：

- 不把 fallback 日志提升为完整 Prompt 日志；
- 不输出 API key、Authorization、完整用户请求或模型响应；
- 不因修复 fallback 而放宽 API index、SkillLoadSession 或 HTTP header 校验。

## 5. 验证矩阵

### 5.1 修改前/测试先行

在生产代码修改前运行：

```bash
mvn -Dtest=PromptLoaderTest test
```

预期当前失败原因是 backend/frontend fallback 与 classpath 资源不一致；若测试未失败，
必须重新检查测试是否真的强制 fallback-only ResourceLoader，不能直接进入实现。

### 5.2 基本集成硬门槛

```bash
mvn clean compile test-compile
mvn -Dtest='PromptLoaderTest,SkillRegistryTest,SkillToolsTest,SkillReferenceReaderTest,BackendApiIntegrationTest,ChatControllerTest' test
mvn test
git diff --check
```

本批不改前端源码，因此不重复执行前端全量门槛；如果启动真实传统页面做补充验证，则
仍沿用 `frontend` 的 Mock/真实 Playwright 入口，并确认服务停止、端口释放。

实际结果（2026-08-24）：

- `git diff --check`：通过。
- `mvn clean compile test-compile`：通过；仅有仓库既有的 deprecated/unchecked 警告。
- 相关后端测试：`44/44` 通过，包含 `PromptLoaderTest`、Skill registry/tools/reference、
  `BackendApiIntegrationTest` 和普通 SSE `ChatControllerTest`。
- 全量默认 Maven 测试：`63/63` 通过；Surefire 仍排除 `live-llm` 和 `container`。

### 5.3 真实 LLM 补充验证

用户已授权使用根目录 `.env` 中的真实模型 key。由于本批只修复资源缺失 fallback，真实
模型调用的增量证据有限，不把“资源缺失环境下的真实调用”作为默认必跑项；必须先完成
确定性测试。若实现后需要证明普通 Agent 正常资源链路未被破坏，运行最小只读传统页面
场景：

```bash
set -a && source .env && set +a
RUN_LIVE_LLM_TESTS=true \
  mvn -Dtest.excluded-groups=container \
  -Dtest=OpenAiCompatibleApiLiveTest test
(cd frontend && npm run test:e2e:traditional)
./dev.sh --stop
```

真实调用期间观察后端日志和 Playwright 的 HTTP/DOM 断言，不打印凭证。若外部模型、
PostgreSQL 或 embedding 初始化失败，应记录为环境阻塞，不把未运行的真实测试报告为通过。

实际结果（2026-08-24）：

- `RUN_LIVE_LLM_TESTS=true mvn -Dtest.excluded-groups=container -Dtest=OpenAiCompatibleApiLiveTest test`：
  `Tests run: 1`，通过。
- `./dev.sh --backend-only`：使用根目录 `.env` 以 `postgresql` profile 启动成功；
  6 个 Skill、24 个 API index、PostgreSQL 向量表和 OpenAI-compatible
  `grok-4.5` 均初始化成功。
- `(cd frontend && npm run test:e2e:traditional)`：通过。嵌入式页面完成登录，真实
  `/api/chat/text` 返回 JSON，DOM 显示真实商品结果。
- `./dev.sh --stop` 后 8080 端口已释放。
- 未运行 AG-UI/CopilotKit 端点；本批范围明确排除该链路。

## 6. 实现后三轮固定范围审查

基本门槛通过后，按以下互不重叠范围审查：

1. **Prompt/行为正确性**
   - 三份资源与 fallback 是否 exact-equality；
   - backend/frontend 规则是否仍与各自工具注册边界一致；
   - `PromptLoader` 占位符、缓存和未知资源行为是否未被改变。
2. **安全/兼容性**
   - fallback 是否含敏感信息或错误的认证/URL 放行说明；
   - 普通 Skill 门禁、写操作确认和 AG-UI 工具边界是否无意变更；
   - Java 17 编译与现有 PromptLoader 使用方是否兼容。
3. **测试/文档交付**
   - 新测试是否在 fallback-only 环境实际覆盖资源缺失；
   - 测试命令、文档链接、规划状态和 git diff 是否一致；
   - 未产生 `.env`、target、日志或构建产物变更。

计数器从 0 开始。任一轮发现会影响正确性、安全、兼容性、成本或数据一致性的缺陷，
立即修复，重新通过基本门槛，并将计数器重置为 0。连续三轮无问题且期间没有代码修改
后，才能标记本批完成。

### 实际审查记录

三轮审查均在基本门槛和真实补充验证通过后进行；审查期间没有修改生产代码、测试或
运行时资源。

| 时间 | 范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|
| 2026-08-24 15:16 | Prompt/行为正确性：资源与 fallback 等价性、backend/frontend 模式、占位符、缓存和使用方 | 无 | 无 | 通过 |
| 2026-08-24 15:17 | 安全/一致性：fallback 敏感信息、Skill/API 门禁、认证/确认边界、AG-UI 隔离和端口清理 | 无 | 无 | 通过 |
| 2026-08-24 15:18 | 测试/文档交付：验收测试、验证命令、导航链接、提交范围和忽略文件 | 无 | 无 | 通过 |

三轮连续无问题，计数器达到 3；不需要重置或追加修复。

## 7. 实施完成后的记录模板

完成时将本文件更新为：

```text
状态：已实施、验证、三轮审查、提交并推送
提交：<commit>
测试：<确定性/硬门槛/真实补充证据>
规划评审：<连续三轮无修改的日期、范围、结果>
实现评审：<连续三轮无修改的日期、范围、结果>
工作区：主仓库和子模块 clean，origin/master 同步
```

下一批候选仍需重新探索源码后决定，不在本规划中预先承诺 Prompt 以外的生产改造。
