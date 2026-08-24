# Skill 资源来源与可复用包加固规划

> **状态**: 已实施
> **范围**: 普通运行时 Skills、SkillRegistry、受限 reference reader、API index 资源解析；不覆盖 AG-UI/CopilotKit
> **最后核对**: 2026-08-24
> **前置规划**: [当前项目 SKILL 支持改进规划](skill-support-improvement-plan.md)
> **参考实现**: [社区 `SkillsTool` 审计报告](../spring-ai-agent-utils-audit.md)
> **长期规则**: 本规划遵循根目录 [AGENTS.md 的规划、验收和三轮收敛原则](../../AGENTS.md)

## 1. 目标与完成定义

本批把社区库审计中最值得借鉴、且不依赖 Spring AI 2.x 的资源扫描能力转化为当前项目
自己的后端实现。目标是让读者可以通过源码、测试和运行时目录 API 直接理解：

1. 当前项目的 `SKILL.md` 可以来自应用 classpath、文件系统目录或可复用的 JAR；
2. Skill 正文、`references/`、分层 operations 和 API index 必须来自同一个资源来源，
   不能出现“正文从 JAR 加载、reference 却回到主应用 classpath”的漂移；
3. JAR/文件系统只是资源来源，不因此获得通用文件写入、Shell 或任意路径读取能力；
4. 现有 frontmatter、目录名契约、links 图校验、API index 和普通 Agent ToolContext
   语义保持不变；
5. 读者可以用一个最小 fixture 测试看到“可复用 Skill 包如何被发现、加载和读取”。

完成定义：

- 默认 `classpath*:skills` 仍加载当前 6 个 Skill 和 24 个 API index；
- `file:` 目录 fixture 能发现 Skill、读取正文和 reference；
- 包含 `SKILL.md` 及 `references/` 的临时 JAR fixture 能发现 Skill、读取正文、
  分层 operation 并建立 API index；
- registry、reference reader、`getFullApiDescription` 不再硬编码只对主应用
  `classpath:skills/<name>` 有效；
- 非法、重复、目录漂移、悬空 link 和 API index 冲突仍 fail-fast；
- 默认离线 Maven 测试、后端硬门槛和本批相关集成测试通过；
- 实际 Spring Boot executable JAR 能从 `jar:nested:` 资源启动、建立 6/24 目录并读取
  分层 operation reference；
- 稳定文档明确配置入口、资源布局、JAR fixture 测试和安全边界；
- 不修改工具名称、Prompt 模式、业务端点、认证协议、向量库、AG-UI/CopilotKit 或
  社区子模块 gitlink。

## 2. 当前事实与缺口

### 2.1 实施前资源链路

本批实施前，`SkillRegistry` 的发现流程是：

```text
classpath:skills/*/SKILL.md
  -> parseSkillDocument
  -> registerSkill
  -> validateSkillLinks
  -> indexSkillApis
```

`SkillReferenceReader` 和 `SkillRegistry.getFullApiDescription` 随后重新拼接：

```text
classpath:skills/<skillName>/references/<relativePath>
```

这在主应用 `target/classes` 下可用，但没有保存“该 Skill 实际来自哪个资源根”。因此
当 `SKILL.md` 来自 JAR 或外部文件目录时，正文、reference、分层 operations 和结果
解释的 API 文档可能从不同来源读取。

### 2.2 社区库可借鉴边界

固定子模块 `spring-ai-agent-utils` `v0.10.0` 已证明以下工程做法有参考价值：

- 使用 Spring `Resource` 作为输入；
- 同时覆盖 filesystem、classpath 和 JAR URL；
- 对没有显式目录 entry 的 JAR 提供扫描 fallback；
- 使用临时目录和临时 JAR 做 deterministic fixture；
- 将资源发现测试与 Markdown 解析测试分开。

但本批不复制社区 `SkillsTool` 的通用工具协议，也不引入其 Spring AI 2.0 依赖。当前
项目继续使用自己的 Jackson YAML frontmatter、links 图、API index、URL allowlist、
请求级 Skill session 和受限 reference reader。

### 2.3 本批缺口

| 缺口 | 风险/教育影响 | 事实来源 |
|---|---|---|
| 资源发现和资源读取分散在 `SkillRegistry`/`SkillReferenceReader` | JAR/外部目录只修一半会出现来源漂移 | `SkillRegistry.java`、`SkillReferenceReader.java` |
| 没有可配置 Skill root | 读者无法演示可复用 Skill 包或企业外部 Skill 目录 | `application.yml`、README、配置文档 |
| 没有 JAR/文件系统 fixture | 资源来源契约只能靠 classpath happy path 推断 | `SkillRegistryTest`、`SkillReferenceReaderTest` |
| `getFullApiDescription` 使用主应用 classpath 拼接 | 结果解释对外部/打包 Skill 不可靠 | `ExplainResultService`、`SkillRegistry` |

## 3. 推荐设计与实施边界

### P0-A：统一 `SkillResourceCatalog`

新增：

- `src/main/java/com/example/demo/agent/SkillResourceCatalog.java`
- `src/main/java/com/example/demo/config/SkillResourceProperties.java`
- 必要时新增内部 `SkillResource`/`SkillResourceEntry` record

职责：

1. `SkillResourceProperties` 使用 `@ConfigurationProperties(prefix = "app.skills")`
   将 locations 绑定为 `List<String>`；生产环境由 Spring 构造注入，测试可显式构造；
2. `SkillResourceProperties` 通过应用配置类的 `@EnableConfigurationProperties`
   显式注册；`SkillResourceCatalog` 作为 `@Component` 通过构造注入
   `ResourceLoader` 和上述 properties，并在内部创建与该 loader 绑定的
   `ResourcePatternResolver`，不依赖一个并不存在的 resolver Bean；
3. 按配置的 Skill roots 发现 `**/SKILL.md`；
4. 为每个 Skill 保存：
   - canonical Skill name；
   - `SKILL.md` 的 Spring `Resource`；
   - 所属 root/source 和用于相对解析的 resource base；
   - source 描述；
   - 能从同一 root 解析相对资源的句柄；
5. 按 canonical name 和 source 稳定排序；
6. 提供：
   - `List<SkillResource> discover()`
   - `Optional<SkillResource> find(String skillName)`
   - `Resource resolve(String skillName, String relativePath)`
   - `Resource[] findResources(String skillName, String relativePattern)`

`SkillResourceProperties` 必须由应用配置显式注册（例如在配置类上使用
`@EnableConfigurationProperties`），不能依赖测试恰好扫描到一个普通 Bean 才成立。

`findResources` 必须基于该 Skill 保存的 resource base 扫描，不能重新从主应用
`classpath:skills/<name>` 拼接。当 Spring resolver 对 JAR 目录 entry 不完整时，catalog
在自己的 source context 内按 JAR entry 前缀过滤并构造 `UrlResource`，保持与 `resolve`
相同的来源。对于 `classpath*:` 和显式 `jar:file:` root，手动 JAR 扫描必须作为
resolver 结果的补充并按资源 URL/source 去重，不能只在 resolver 返回 0 个结果时执行；
否则主应用 Skill 已被发现时仍可能漏掉无目录 entry 的依赖 JAR，显式 JAR root 也可能
漏掉其内部 Skill。手动扫描只在发现阶段打开 `JarFile`，遍历结束后关闭，不把打开的
`JarFile` 或临时解压目录保存到 Bean 状态。

`classpath*:` fallback 只能扫描当前 `ClassLoader` 能枚举到的 classpath JAR；本批采用
与社区库相同的 `META-INF/MANIFEST.MF` 枚举策略，因此可复用 Skill JAR 的发布契约是
包含标准 manifest（Maven 构建的普通 JAR 默认满足）。没有 manifest 或运行时 classloader
无法枚举的特殊打包物，必须通过显式 `jar:file:` location 配置，不能在文档中承诺
`classpath*:` 对所有非标准容器都可见。

`resolve` 只接受 catalog 内部生成的规范相对路径：拒绝绝对路径、反斜杠、空段、`.`、
`..`、URI scheme 和编码后的路径逃逸，再从已登记的 Skill base 解析。`findResources`
另使用受限 glob 校验：只允许相对路径段和末段 `*.md`，拒绝 `**`、绝对路径、反斜杠、
`.`、`..`、URI scheme 和编码后的路径逃逸。即使调用方漏做校验，也不能逃出该 Skill
目录。对 filesystem root，若目标已存在，还必须校验其 real path 仍位于 Skill root
之下，拒绝通过符号链接逃出 root。

`resolve` 对已注册 Skill 的合法但不存在的相对资源返回一个 `exists() == false` 的
Resource，供 reader 继续生成稳定的“文件不存在”结果；Skill 不存在、路径不合法或
资源 scheme 不支持则抛出包含 Skill/source/location 的 `SkillDefinitionException`。
`findResources` 对合法 pattern 没有匹配返回空数组；发现阶段的 I/O 或配置错误必须
失败启动，不能静默返回空目录。

默认资源位置由 `application.yml` 把环境变量映射到类型化配置：

```yaml
app:
  skills:
    locations: ${SKILL_LOCATIONS:classpath*:skills}
```

`locations` 是按顺序绑定的 `List<String>` 配置。环境变量使用逗号分隔的
location 列表，例如：

```text
SKILL_LOCATIONS=classpath*:skills,file:/opt/company-skills
```

默认值为 `classpath*:skills`；空白 location 会被忽略，但如果最终没有任何有效
location，应用必须 fail-fast。

`classpath*:skills` 表示扫描主应用和依赖 JAR 中的 `skills/**/SKILL.md`；file/JAR 使用者
可以传入例如：

```text
file:/opt/company-skills
jar:file:/opt/company-skills.jar!/skills
classpath*:META-INF/skills
```

实现可以把 root 规范化为以 `/` 结尾的 resource URL，再使用同一个 resource scheme
解析相对路径。若某种 Resource 无法安全解析 relative resource，必须返回诊断错误，
不能回退到主应用 `classpath:skills/<name>`。

配置的 locations 是唯一发现来源：每个非空 root 都必须至少发现一个 Skill；任一 root
找不到资源、或所有 root 最终为空时，启动必须以包含 location 的
`SkillDefinitionException` 失败，不再偷偷回退到 `src/main/resources/skills`。这样
开发态、打包态和可复用 JAR 的行为保持可诊断；默认 `classpath*:skills` 已覆盖当前
应用资源。

默认理由：

- 单一 catalog 消除正文、reference、operation 和 API description 的来源漂移；
- `Resource` 保留 JAR/file/classpath 的部署差异，避免手写 `File` 逻辑；
- 配置仍是只读 root，不开放模型传入路径；
- 不需要把 Skill 内容复制到临时目录或暴露物理路径给模型。

### P0-B：改造 `SkillRegistry`

修改：

- `src/main/java/com/example/demo/agent/SkillRegistry.java`

规则：

1. `init()` 从 `SkillResourceCatalog.discover()` 读取 Skill；
2. 现有 frontmatter、links、目录名、重复 name、API definition、API index 冲突校验
   全部保留；
3. `indexSkillApis` 使用 catalog 的 operation resources；
4. `getFullApiDescription` 使用 catalog 解析 hierarchical reference；
5. 现有 `registerSkill(String source, Skill skill)` 继续保留 package-private，方便纯
   frontmatter/graph 单测；资源 entry 版本负责保存来源句柄；
6. 外部 source 无法可靠解析目录名时保持现有宽松边界，不能误拒绝未来 provider。
7. 保留一个面向测试的显式 catalog 构造路径；生产 Bean 使用构造注入，避免测试继续
   通过反射写入 `SkillRegistry.resourceLoader`。

不会改变：

- `SkillRegistry.all()`、`get()`、`getApiIndex()` 的返回形状；
- Skill API index 的 key 和 method/path matching；
- 运行时 REST API；
- 普通 Agent 的 `SkillTools` 方法签名和 ToolContext。

### P0-C：改造 `SkillReferenceReader`

修改：

- `src/main/java/com/example/demo/agent/SkillReferenceReader.java`

规则：

- 保留现有 skill name、相对路径、编码逃逸、大小和返回长度校验；
- 校验通过后调用
  `SkillResourceCatalog.resolve(skillName, "references/" + relativePath)`；模型参数始终
  相对 `references/`，不能读取 Skill 根目录中的 `SKILL.md` 或其他同级资源；
- 只允许已注册 Skill 对应的 catalog entry；
- 不允许根据模型输入创建任意 `file:`、`jar:`、`classpath:` location；
- resource 不存在、不可读、读取异常继续映射为稳定的中文错误，不暴露绝对主机路径。
- 单元测试通过 mock/stub catalog 返回 `ByteArrayResource` 覆盖超大文件和读取异常；
  生产构造不再接受任意 `ResourceLoader`，避免测试 seam 变成运行时任意资源入口。

### P0-D：可配置入口和教育文档

修改：

- `src/main/resources/application.yml`
- `.env.example`；本地 `.env` 含真实密钥且被 Git 忽略，不修改、不记录其值
- `docs/configuration.md`
- `docs/knowledge-and-skills.md`
- `docs/learning-path.md`
- `docs/ARCHITECTURE.md`
- `docs/HARNESS.md`
- `AGENTS.md`
- `docs/drafts/README.md`

默认配置保持主应用 classpath 行为；新增配置只用于显式演示额外来源。文档必须说明：

- 运行时 Skill 与 `.agents/skills` 不同；
- JAR 目录约定和 Maven dependency 分发方式；
- `references/` 仍由项目受限 reader 读取；
- 不会因为 JAR 来源而允许 Shell/FileSystemTools；
- Skill source 只读，修改资源后需重启应用重新构建 API index；
- 资源来源测试是 deterministic，不需要 LLM/Embedding/PostgreSQL。

## 4. 一次性验收测试设计

先增加测试，再修改生产代码。测试不调用真实 LLM、Embedding、PostgreSQL、浏览器或
AG-UI。

### 4.1 `SkillResourceCatalogTest`

新增 `src/test/java/com/example/demo/agent/SkillResourceCatalogTest.java`：

1. 文件系统目录 fixture：
   - 创建 `skills/file-skill/SKILL.md`；
   - 创建 `references/guide.md`；
   - catalog 发现 canonical name；
   - resolve 返回正文；
   - 不允许通过 relative path 逃逸到 root。
2. JAR fixture：
   - 使用包含 `META-INF/MANIFEST.MF` 的 `JarOutputStream` 创建临时 JAR；
   - 可有意不创建目录 entry，直接写 `skills/jar-skill/SKILL.md` 和
     `references/operations/list.md`；
   - 将 JAR 加入隔离 `URLClassLoader`，同时保留主应用 classpath Skill，证明 resolver
     已返回其他结果时补扫仍能发现 JAR Skill；
   - 再用同一 JAR 构造显式 `jar:file:` root，证明无目录 entry 时该入口也能发现；
   - `resolve` 能读取 reference；
   - 在所有读取和断言完成前保持临时 JAR 存在；测试先关闭隔离 `URLClassLoader`，
     再在 `finally` 中清理 JAR 和临时目录；catalog 不复制、解压或持久化 Skill 内容，
     也不产生测试目录之外的副作用。
3. 多来源稳定排序与重复名：
   - file + JAR 同时提供不同 Skill；
   - 同名 Skill 触发既有 duplicate name 错误；
   - discover 结果按 name/source 稳定排序。

### 4.2 `SkillRegistryTest` 扩展

- 使用 file fixture 初始化 registry，验证 Skill body 和 reference API description 来自
  fixture，而不是主应用 classpath；
- 使用 JAR fixture 初始化 registry，验证 hierarchical operation 被索引，`getFullApiDescription`
  返回 JAR 中 operation 正文；
- 当前默认 classpath 回归仍断言 6 Skill、24 API index。

### 4.3 `SkillReferenceReaderTest` 扩展

- 使用 catalog fixture 读取 file/JAR reference；
- 非法路径仍拒绝；
- 不存在 Skill/不在 references 目录的路径仍返回稳定错误；
- ordinary `SkillTools` 和 AG-UI `SkillCoreTools` 仍共享同一 reader 合同。

### 4.4 配置/集成边界

- 增加 `SkillRegistry` 的构造/fixture helper，避免测试依赖不可见的主应用 singleton；
- 增加 `SkillResourceProperties` 绑定测试，覆盖 YAML/default 和逗号分隔
  `SKILL_LOCATIONS`，证明当前 Spring Boot 版本实际得到有序 `List<String>`；
- 生产 Spring Context 使用默认位置启动并保持 6/24 回归；
- 配置 root 为空或无 Skill 时验证启动失败且错误包含配置 location；不会静默切回开发
  源码目录；
- 不新增 Controller 端点；离线资源契约测试不要求真实 LLM，但整体验收必须按第 5 节
  完成一次真实传统页面只读闭环；
- 配置绑定测试必须使用实际 `application.yml` 入口，不能只验证手工构造的 properties。

## 5. 实施顺序

1. 完成本规划三轮连续无修改审查；
2. 新增 `SkillResourceCatalogTest` 和 JAR/file fixture 测试，并扩展 registry/reference
   测试；让测试先表达目标行为；
3. 实现 `SkillResourceCatalog`；
4. 改造 `SkillRegistry`、`SkillReferenceReader` 和相关构造/测试 fixture；
5. 更新配置、稳定文档和本规划状态；
6. 执行基本硬门槛：

   ```bash
   git diff --check
   mvn clean compile test-compile
   mvn -Dtest='SkillResourceCatalogTest,SkillRegistryTest,SkillReferenceReaderTest,SkillToolsTest,BackendApiIntegrationTest,ChatControllerTest' test
   mvn test
   ./test-executable-jar.sh
   ```

7. 执行前端基本门槛和 Mock Playwright，因为本批不改变前端代码，只确认静态运行资源
   没有被后端资源配置改动破坏：

   ```bash
   cd frontend
   npx tsc --noEmit
   npm run build
   npm run test:skills
   npm run test:e2e:traditional:mock
   ```

8. 在上述 Mock/构建门槛通过后，使用用户已授权的 `.env` 做一次传统页面真实只读
   Playwright 闭环；本批改变 Skill 发现和读取路径，因此该真实验证是必需项，不是
   条件项。使用 `dev.sh` 启动后端，观察启动日志中的 Skill 数量/API index，以及
   浏览器 DOM、网络响应和最终结果，确认没有认证失败、重复工具调用或错误来源回退；
9. 完成三轮固定范围代码审查；发现实质问题即修复并重置计数；
10. 更新实施记录，commit、push，确认主仓库和子模块干净。

## 6. 风险、回滚与中断恢复

### 风险

- `classpath*:` 在某些打包 JAR 中没有显式目录 entry，Spring resolver 可能漏发现；
  catalog 必须有手动 JAR entry fallback，且 fixture 要覆盖无目录 entry JAR。
- 同名 Skill 来自多个 dependency JAR 时如果静默覆盖，模型目录和 API index 会不确定；
  继续 fail-fast。
- `Resource#getURL()` 对特殊 URL scheme 可能不可用；目录来源不能因此回退到主应用
  classpath。错误应在启动时带 source 诊断。
- Spring Boot executable JAR 使用 `jar:nested:`，不能交给只接受物理 `file:` URL 的
  `JarFile` scanner；该路径必须委托 Spring resolver，并用真实 `java -jar` smoke 验证。
- 文件系统 root 是部署配置，不是模型输入；不能为了测试便利放宽 `readSkillReference`
  的路径校验。

### 回滚边界

- 可只回滚 `SkillResourceCatalog` 接入，保留现有 classpath discovery；
- 配置和文档可与生产代码独立回滚，但不能保留超前的 JAR 支持声明；
- 不回滚或修改 `ag-ui-4j`、`spring-ai-agent-utils` 子模块；
- 不改变 Skill Markdown 内容和业务 API。

### 中断恢复

恢复时先运行：

```bash
git status --short --branch
git diff -- docs/drafts/skill-resource-provider-hardening-plan.md
```

然后从第 5 节第一个未完成步骤继续。不要重新创建第二份规划，也不要把 AG-UI/CopilotKit
重新纳入本批。

## 7. 规划检查记录

规划审查从 `0` 开始。每轮都重新交叉检查源码、配置、测试、文档和边界；发现实质问题
立即修正文档并将计数归零。三轮无修改后才允许实施。

无发现轮次不在本节提前回写，避免破坏“连续三轮无修改”的终止条件。实际检查时间、范围、
发现和计数由当前任务进度记录；实施完成后再整理摘要。

## 8. 实施记录

### 8.1 实际实现

- 新增 `SkillResourceCatalog` 和 `SkillResourceProperties`，由
  `DemoApplication` 显式启用类型化配置；
- `SkillRegistry`、`SkillReferenceReader`、分层 operation index 和
  `getFullApiDescription` 统一通过 catalog 使用同源资源；
- 默认 `SKILL_LOCATIONS=classpath*:skills`，支持 filesystem、普通 classpath/显式 JAR、
  无目录 entry JAR 和 Spring Boot `jar:nested:`；
- 新增 filesystem/JAR fixture、配置绑定测试和 `test-executable-jar.sh`；
- 更新 AGENTS、架构、配置、知识/Skills、学习路径、验证手册、社区库审计和草稿索引；
- `CLAUDE.md` 未修改，继续只跳转到 `AGENTS.md`；两个子模块 gitlink 未修改。

### 8.2 规划偏差与处理

最初设计覆盖普通 `jar:file:` 和无目录 entry JAR。实际执行 `java -jar` 时发现 Spring
Boot 3.5.16 将主应用资源表示为 `jar:nested:`，原 `JarScope` 只接受物理 `file:` URL，
导致 operation 扫描启动失败。修复采用 Spring resolver-backed scope：

- 普通 `jar:file:` 保留手工 `JarFile` 扫描和 entry-prefix 约束；
- non-file/nested JAR 由 Spring `ResourcePatternResolver` 扫描，并以已发现
  `SKILL.md` 的目录作为相对读取 base；
- 不手工解析 Spring Boot nested JAR 格式，不回退到其他 classpath source；
- executable JAR smoke 使用本地 Mock LLM 调用 `/api/explain-result`，证明 nested
  operation 不仅能建立 index，也能由 `getFullApiDescription` 实际读取。

### 8.3 验证证据

2026-08-24 已完成：

| 验证 | 结果 |
|---|---|
| `git diff --check`、`bash -n test-executable-jar.sh` | 通过 |
| `mvn clean compile test-compile` | 通过 |
| 资源专项 `SkillResourceCatalogTest,SkillResourcePropertiesTest,SkillRegistryTest,SkillReferenceReaderTest` | 26/26 通过 |
| 本批相关后端集合 | 63/63 通过 |
| 默认 `mvn test` | 80/80 通过，`live-llm`/`container` 按默认配置排除 |
| `./test-executable-jar.sh` | 通过：6 Skills、24 API entries、nested reference read |
| `npx tsc --noEmit` | 通过 |
| `npm run build` | 通过；仅有既有 CopilotKit 动态依赖 warning |
| `npm run test:skills` | 通过 |
| `npm run test:e2e:traditional:mock` | 通过 |
| `npm run test:e2e:traditional` | 通过：真实 `/api/chat/text` 200，DOM 渲染商品结果 |

真实浏览器验收使用主工作区 `.env`、`postgresql` profile 和 OpenAI-compatible
`grok-4.5`，启动日志确认 PostgreSQL 连接、6 个 Skill 和 24 项 API index。验收只使用
DOM、网络响应和自动化断言，没有以截图作为证据，也没有输出或提交凭证。服务已在验收后
停止。executable JAR smoke 默认自动选择空闲应用/Mock LLM 端口，避免干扰工作站上其他
仓库的本地服务。

### 8.4 收敛与交付约束

实现后必须完成三轮固定范围只读审查，再 commit、push 并确认主仓库及两个子模块干净。
具体轮次、提交和远端状态记录在对应任务报告；若审查发现实质缺陷并修改代码，则重新
执行硬门槛并将审查计数归零。
