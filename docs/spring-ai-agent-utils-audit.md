# Spring AI Community `SkillsTool` 审计报告

> **状态**: 已完成；作为当前项目的外部实现评估基线
> **审计日期**: 2026-08-23
> **对应社区库**: `spring-ai-community/spring-ai-agent-utils`
> **对应子模块路径**: `spring-ai-agent-utils/`
> **对应 release**: `v0.10.0`
> **对应 git commit**: `7f8bc47de1bc5a306b6cb078fa6b191ff7845572`
> **发布**: 2026-06-12
> **许可证**: Apache-2.0

本文不是对漂移中的 `main` 分支的泛泛介绍，而是对本仓库 Git 子模块所固定的
`v0.10.0` 代码、文档、测试和 Maven 配置的可复核报告。更新子模块指针后，必须重新
执行审计并更新本文顶部基线、结论和变更记录。

## 如何复核

```bash
git submodule status
git -C spring-ai-agent-utils describe --tags --exact-match HEAD
git -C spring-ai-agent-utils rev-parse HEAD

sed -n '1,260p' \
  spring-ai-agent-utils/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/tools/SkillsTool.java
sed -n '1,360p' \
  spring-ai-agent-utils/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/utils/Skills.java
sed -n '1,220p' \
  spring-ai-agent-utils/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/utils/MarkdownParser.java
```

子模块是多模块 Maven 仓库，生产模块的源码根是：

```text
spring-ai-agent-utils/spring-ai-agent-utils/
├── src/main/java/org/springaicommunity/agent/tools/SkillsTool.java
├── src/main/java/org/springaicommunity/agent/utils/Skills.java
├── src/main/java/org/springaicommunity/agent/utils/MarkdownParser.java
├── src/test/java/org/springaicommunity/agent/tools/SkillsToolTest.java
└── src/test/java/org/springaicommunity/agent/utils/MarkdownParserTest.java
```

该子模块只用于参考和审计。当前项目的 `pom.xml` 没有把它加入 Maven reactor，也没有把
社区 artifact 加入运行时依赖。

## 版本与工程基线

固定 release 的上游 `pom.xml` 声明：

| 项目 | `spring-ai-agent-utils` `v0.10.0` | 当前项目 |
|---|---:|---:|
| Java 编译目标 | 17 | 17 |
| Spring AI | 2.0.0 | 1.1.2 |
| Spring Framework | 7.0.8 | 由 Spring Boot 3.4.2 管理 |
| 发布阶段 | 0.x 正式版本 | 示例项目当前生产/演示链路 |
| 依赖关系 | 上游独立多模块仓库 | 不依赖社区库 |

社区库 `v0.10.0` 是当前审计时的最新正式 release；上游 `main` 的快照开发不能作为
当前项目的可复现生产基线。即使两边都使用 Java 17，Spring AI 2.0 与本项目 Spring AI
1.1.2 的 API、Spring Boot 兼容矩阵和工具调用行为仍不能直接假定兼容。

## `SkillsTool` 实际做了什么

在 `v0.10.0` 中，`SkillsTool.builder().build()` 返回一个名为 `Skill` 的
`FunctionToolCallback`。它的输入只有一个 `command` 字符串：

```text
模型看到 Skill 工具描述中的 available_skills
  -> 选择某个 skill name
  -> 调用 {"command":"skill-name"}
  -> SkillsFunction 从内存 Map 取出 Skill
  -> 返回 base directory + 完整 SKILL.md 正文
```

因此它具备以下特点：

- Level 1 是工具 description 中渲染的 Skill frontmatter；
- Level 2 是一次 `Skill` 工具调用返回的完整 Markdown 正文；
- “语义匹配”主要是模型根据 Skill description 自行选择 `command`，不是独立的向量
  检索器、排序器或 embedding matcher；
- Skill 由 name 去重放入 `Map`，同名 Skill 的冲突策略是后加入者覆盖；
- Skill 内容中提供的 base directory 让模型可以继续使用其他文件或 Shell 工具；
- 社区库没有为当前项目的 API index、HTTP 方法/路径白名单、认证透传或写操作确认
  建立业务领域模型。

## 资源扫描与测试质量

这是社区库目前最值得借鉴的部分。`Skills.loadResource()` 依次处理：

1. 可直接映射为文件系统目录的 Spring `Resource`；
2. `jar:` URL，通过 `JarURLConnection` 扫描；
3. `ClassPathResource` 指向 JAR 内部目录时，先使用 Spring
   `PathMatchingResourcePatternResolver`，再回退到手动扫描 classpath JAR；
4. 通过 `SKILL.md` 文件名递归发现技能。

`SkillsToolTest` 覆盖了：

- 文件系统目录；
- `FileSystemResource`；
- 多个嵌套目录；
- 不存在目录和没有 Skill 的错误；
- 自建 JAR 的 `jar:` URL；
- JAR 中的单个 Skill；
- classpath SkillsJar；
- 正常调用、未知 Skill 和返回 base directory。

`MarkdownParserTest` 覆盖了基础键值 frontmatter、引号、冒号、空行、正文提取和无
frontmatter 文档。

这套测试组织方式比当前项目现有的 Skill 测试基础更完整，尤其适合作为资源扫描、
可复用 Skill 包和确定性工具 callback 测试的参考。不过它没有证明社区实现天然适合
当前项目的安全边界或业务 API 语义。

### 本机验证限制

本次审计在本机 Java `23.0.1`、Maven `3.9.14` 环境下尝试构建社区多模块项目时，
Maven Compiler Plugin 抛出 `ConcurrentModificationException`，因此未能完成上游
全绿构建验证。该结果只记录为本机工具链下的验证限制，不能单独定性为社区源码缺陷；
后续若要把社区库用于 PoC 或生产依赖，仍需在项目目标 JDK、Maven 和 Spring Boot
基线上重新执行构建与测试。

## Frontmatter 与当前 Skill 格式的兼容性

社区 `MarkdownParser` 是逐行轻量解析器：

- 只在文档以 `---` 开始时寻找下一个 `---`；
- 每一行按第一个冒号拆成 `String -> String`；
- 不使用完整 YAML parser；
- 嵌套对象、列表和多行值不会被解析为结构化数据；
- `SkillsTool.Skill.name()` 只要求 frontmatter 中存在 `name`；
- 其他字段主要被原样拼接到工具 description 的 XML-like 文本中。

当前项目的平面 Skill 使用：

```yaml
links:
  - name: checkout
    description: 结算购物车中的商品
```

而 `swagger-petstore-openapi-3-0/SKILL.md` 还使用嵌套 `metadata`。因此当前 Skill 文件
不能原样交给社区 parser 后期待保留本项目的结构化关系。审计时读取
`add-to-cart/SKILL.md` 已观察到：轻量 parser 会把后续关联 Skill 行当成同名键，导致
原本的 `description` 被覆盖，`links` 也不会变成结构化列表。

这不是说社区库的 parser 对其目标格式一定不可用，而是说明它与本项目当前 frontmatter
契约不兼容。若未来采用社区库，必须先定义格式迁移或适配层，并为迁移后的数据建立
回归测试。

## references 与 helper files 的真实边界

社区文档把 `reference files` 和 `helper scripts` 作为 Skill 包能力介绍，但
`SkillsTool` 本身主要返回 Skill 正文和 base directory。模型要读取 reference 或执行
脚本，通常还要额外注册 `FileSystemTools`、`ShellTools` 等工具。

这对通用代码 Agent 很灵活，但对本项目的业务 API Skill 有两个直接风险：

1. “读取某个 Skill 的 references 下单个文件”会扩大成通用本地文件读取边界；
2. “辅助脚本”会引入命令执行能力，而不是当前项目明确的业务 API 工具边界。

当前项目的 `SkillTools.readSkillReference()` 与 `SkillCoreTools.readSkillReference()` 已
委托到同一个受限 reader：只读已注册 Skill 的 `references/` 资源，拒绝绝对路径、目录
穿越、反斜杠、空路径段和编码的点号/分隔符，并对读取和返回分别设上限。未来改进仍应
优先扩展这个受限 API 的测试和资源来源适配，而不是直接改用通用 FileSystem/Shell 工具。

## 与当前项目的能力矩阵

| 能力 | 社区 `SkillsTool` `v0.10.0` | 当前项目 | 结论 |
|---|---|---|---|
| 文件系统 Skill 发现 | 递归扫描 `SKILL.md` | classpath 优先、文件系统回退 | 借鉴社区扫描测试 |
| Classpath/JAR 发现 | 多策略扫描并有测试 | 当前实现主要服务应用 classpath | 社区实现更系统 |
| frontmatter | 轻量键值 parser | Jackson YAML + `SkillMeta.links` | 当前项目更贴合现有格式 |
| Level 1/2 | tool description + Skill 调用 | `SkillsAdvisor` + `loadSkill` | 目标相似，编排不同 |
| Level 3 references | 依赖额外文件工具 | `readSkillReference` | 当前项目边界更窄、更适合业务 |
| links 图 | 无结构化 links API | `SkillMeta.links` + 提示词提示 | 当前项目更强 |
| API index | 无 | `SkillRegistry` 建索引并给 AG-UI/前端校验 | 社区库不能替代 |
| URL/方法校验 | 无业务白名单 | Java/AG-UI/浏览器多处校验 | 社区库不能替代 |
| 认证透传与人工确认 | 无 | AG-UI 浏览器 Token 和写操作确认 | 社区库不能替代 |
| 可复用 SkillsJar | 有 builder 和 JAR 扫描思路 | 尚未抽象 SkillProvider | 应作为 P1 参考 |
| Spring AI 兼容基线 | 2.0.0 | 1.1.2 | 不能直接混用 |

## 质量判断

**总体评价：中上质量的 0.x 社区库，适合参考、实验和内部工具；不应直接作为当前
项目生产 Skill 链路的替换实现。**

### 优点

- Apache-2.0；
- 有独立模块、CI、发布流程和 Maven Central artifact；
- JAR/ClassPath 资源扫描思路比当前项目完整；
- `SkillsToolTest` 和 `MarkdownParserTest` 提供了可借鉴的测试分组；
- API 面小，PoC 成本较低；
- 支持把可复用 Skill 包作为依赖分发。

### 风险

- `0.x` API 仍可能变化；
- 资源扫描虽有多策略，但复杂 fat JAR、多个 SkillsJar、native image 等部署形态
  仍需按目标版本重新验证；
- “语义匹配”不是独立检索能力；
- frontmatter parser 不是完整 YAML parser；
- `allowed-tools`、`model` 等文档字段在 `SkillsTool` 中没有对应的权限执行或模型
  路由机制，不能被当作安全控制；
- references/helper scripts 的能力依赖其他通用文件和 Shell 工具，权限范围需要应用
  自己治理；
- 没有当前项目的 API index、认证和确认协议；
- 社区库与当前项目主版本不同，直接引入会把 Skill 迁移和 Spring AI 升级绑定。

## 是否切换

当前项目**不直接切换**到社区 `SkillsTool`。理由是替换它仍需要保留大部分本项目领域
层：

1. `SkillRegistry` 的 API index、路径参数匹配和冲突策略；
2. `SkillsAdvisor` 的当前 Level 1/2 prompt 编排；
3. `links` 和 OpenAPI 分层 references 的结构化解析；
4. `readSkillReference` 的受限读取；
5. 普通 `AgentService` 与 AG-UI/CopilotKit 的双工具边界；
6. 浏览器认证 Token、写操作确认和前端 URL 校验；
7. Spring Boot 3.4.2 / Spring AI 1.1.2 到上游 2.0 基线的兼容迁移。

短期收益主要是资源扫描和现成测试；短期代价是主版本升级、格式迁移和安全边界
重写，收益不足以覆盖风险。

## 推荐策略

1. 当前继续使用项目自有 `SkillRegistry` / `SkillsAdvisor`。
2. 借鉴社区库的 JAR 资源扫描、可复用 Skill 包和测试组织方式，但不复制实现、不把
   子模块当 Maven 依赖。
3. 如果未来升级到 Spring AI 2.x，单独做一个平面 Skill PoC，不直接替换生产链路。
4. 优先补充当前项目自己的 frontmatter、API index、URL 校验和 `readSkillReference`
   单元测试。
5. 将详细实施顺序、验收条件、回滚边界和未来 PoC 约束维护在
   [SKILL 支持改进规划](drafts/skill-support-improvement-plan.md)。

## 审计记录

| 日期 | 基线 | 结果 |
|---|---|---|
| 2026-08-23 | 子模块 `v0.10.0` / `7f8bc47` | 完成源码、测试、Maven 基线和当前项目兼容性审计 |

更新子模块后，先同步本文顶部的版本和提交，再重新核对源码路径、测试覆盖、版本基线、
格式兼容性和推荐策略；不要只修改版本号而保留旧结论。
