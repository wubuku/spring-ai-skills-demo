# 配置参考

> **目的**: 记录环境变量、默认值、Profile 和外部依赖，不包含任何真实密钥。
> **最后核对**: 2026-08-23

## 配置优先级

主要来源：

1. `src/main/resources/application.yml`
2. `src/main/resources/application-<profile>.yml`
3. 环境变量和启动参数
4. 配置类中的 Bean 条件和默认值

根 `application.yml` 不设置 `spring.profiles.active`。直接运行 Maven 且不指定 profile
时使用 H2；根目录 `dev.sh` 在未覆盖时默认注入 `postgresql`，因此两种启动方式的默认
profile 不同。

## LLM Provider

| 环境变量 | 用途 | 默认/示例 |
|---|---|---|
| `LLM_PROVIDER` | 选择 `openai`、`anthropic` 或 `minimax` | `openai` |
| `OPENAI_API_KEY` | OpenAI-compatible API key | 无安全默认值 |
| `OPENAI_BASE_URL` | OpenAI-compatible base URL | `https://api.openai.com` |
| `OPENAI_MODEL` | 模型名 | `gpt-4o` |
| `ANTHROPIC_API_KEY` | Anthropic-compatible API key | 无安全默认值 |
| `ANTHROPIC_BASE_URL` | Anthropic base URL | `https://api.anthropic.com` |
| `ANTHROPIC_MODEL` | 模型名 | `claude-3-5-sonnet-20241022` |
| `SPRING_AI_MINIMAX_API_KEY` | MiniMax API key | 无安全默认值 |
| `SPRING_AI_MINIMAX_BASE_URL` | MiniMax base URL | `https://api.minimax.chat` |
| `SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL` | MiniMax 模型名 | `abab6.5g-chat` |

Spring AI 自动配置的 chat 开关在基础 YAML 中被关闭，实际 ChatModel 由 `SpringAiConfig` 根据 `LLM_PROVIDER` 条件化创建。

## Embedding、记忆和向量库

| 环境变量 | 用途 | 默认/注意 |
|---|---|---|
| `SILICONFLOW_API_KEY` | Embedding API key | 非空才具备真实向量能力 |
| `SILICONFLOW_URL` | Embedding base URL | 不要带 `/v1` |
| `SILICONFLOW_MODEL` | Embedding 模型 | `BAAI/bge-m3` |
| `SILICONFLOW_DIMENSIONS` | provider 支持时请求的目标向量维度 | `1024`，默认不发送 |
| `SILICONFLOW_DIMENSIONS_ENABLED` | 是否向 Embedding API 发送 `dimensions` | `false` |
| `SPRING_PROFILES_ACTIVE` | 选择数据库和 VectorStore profile | 直接 Maven 默认不指定；`dev.sh` 默认 `postgresql` |
| `SPRING_DATASOURCE_URL` | JDBC 连接 | 可覆盖 PostgreSQL profile 中的本地 5432 地址 |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户 | 导出后可覆盖 profile 中的 `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | 导出后可覆盖 profile 中的 `123456` |

非 PostgreSQL profile：

- JDBC Chat Memory 使用 H2 文件 `./data/chat-memory.mv.db`。
- VectorStore 使用两个独立的 `SimpleVectorStore`，分别持久化到
  `./data/vector-store.json` 和 `./data/chat-memory-vector-store.json`。
- H2 控制台为 `/h2-console`。

PostgreSQL profile：

- 需要 PostgreSQL 和 `vector` 扩展。
- 当前 `application-postgresql.yml` 的本地默认值是数据库 `spring-ai-skills-demo`、用户 `postgres`、密码 `123456`；这些值仅适用于本地 Demo，不能作为生产凭证。
- 直接运行 Maven 时，建议提供 `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，或通过启动参数提供对应的 `spring.datasource.*` 配置。使用根目录 `./dev.sh` 时，脚本会把 `.env` 中的 `POSTGRES_USER`、`POSTGRES_PASSWORD` 映射为这两个 Spring 变量。
- 使用两个独立的 `PgVectorStore`，默认表 `vector_store` 和
  `chat_memory_vector_store`。
- 当前 YAML 使用余弦距离、HNSW 索引、1024 维。
- Chat Memory JDBC schema 使用 PostgreSQL platform。

`BAAI/bge-m3` 等 provider 可能不接受 OpenAI `dimensions` 参数，但会返回原生 1024 维；
因此默认不发送该参数。只有确认实际 provider 支持时，才设置
`SILICONFLOW_DIMENSIONS_ENABLED=true`。

## 知识库

| 配置 | 用途 |
|---|---|
| `KNOWLEDGE_BASE_PATHS` | 逗号分隔的 classpath/file glob |
| 默认值 | `classpath:knowledge-base/*.md` |

不要把 `knowledge-extra/` 自动当作运行时知识库；是否加载由配置路径决定。

想了解“新增一份公司保修/服务条款后，如何进入 RAG 问答”，见 [知识库与运行时 Skills](knowledge-and-skills.md)。

## 运行时 Skill 资源

| 配置 | 用途 | 默认值 |
|---|---|---|
| `SKILL_LOCATIONS` | 逗号分隔的只读 Skill resource roots | `classpath*:skills` |
| `app.skills.locations` | 对应的 Spring Boot `List<String>` 属性 | 由上面的环境变量映射 |

支持的 root 示例：

```text
classpath*:skills
file:/opt/company-skills
jar:file:/opt/company-skills.jar!/skills
classpath*:META-INF/skills
```

`classpath*:` 会扫描主应用和 classloader 可枚举的依赖 JAR；标准 Maven JAR 应包含
`META-INF/MANIFEST.MF`。没有 manifest 或不能由当前 classloader 枚举的特殊 JAR，应使用
显式 `jar:file:` root。应用自身打成 Spring Boot executable JAR 后，默认 root 会由
Spring resolver 以 `jar:nested:` 方式读取，不需要把该内部 URL 写入
`SKILL_LOCATIONS`。多个 root 按逗号分隔，例如：

```bash
SKILL_LOCATIONS="classpath*:skills,file:/opt/company-skills"
```

每个非空 root 必须至少发现一个 `SKILL.md`；空 root、无匹配资源、重复 Skill name、
非法 frontmatter/link/API index 或 filesystem 符号链接越界都会使应用启动失败。资源
在启动时扫描并构建 API index，修改后需要重启。配置路径不能来自模型输入，JAR/file
来源也不会开放通用文件工具。目录布局和打包方式见
[知识库与运行时 Skills](knowledge-and-skills.md#可复用-skill-资源包)。

## 视觉和语音转写

| 环境变量 | 用途 |
|---|---|
| `VISION_BASE_URL` | 视觉模型 OpenAI-compatible base URL |
| `VISION_API_KEY` | 视觉模型密钥 |
| `VISION_MODEL` | 视觉模型名称 |
| `TRANSCRIPTION_BASE_URL` | 转写服务 base URL |
| `TRANSCRIPTION_API_KEY` | 转写服务密钥 |
| `TRANSCRIPTION_MODEL` | 转写模型名称 |

多模态聊天和流式转写只有在相应外部服务可用时才可以进行真实验证。

## 应用和网络

| 配置 | 当前值/规则 |
|---|---|
| `server.port` | `8080` |
| `app.api.base-url` | `http://localhost:${server.port}` |
| 文件上传上限 | 单文件 20MB，请求 25MB |
| `JAVA_BACKEND_URL` | Next.js BFF 访问 Java 后端 |
| `NEXT_PUBLIC_JAVA_BACKEND_URL` | 浏览器 `httpRequest` 访问 Java 后端 |

## Docker 注意事项

`Dockerfile` 使用 Java 17 构建和运行，`docker-compose.yml` 默认配置 PostgreSQL profile。
使用 Docker 前应检查 Compose 环境变量是否覆盖了应用 profile、数据库和实际 Embedding
配置。

任何密钥只放在本地 `.env` 或部署系统的 Secret 中，不提交到仓库。
