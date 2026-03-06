# Spring AI Skills Demo

一个 Spring Boot 示例项目，展示如何使用 **Spring AI Skills 渐进式披露**机制构建 AI 购物助手 Agent。

核心思路：LLM 初始只看到简短的技能目录（Level 1 元数据），需要时再调用 `loadSkill` 按需加载完整 API 指令（Level 2），相比一次性注入完整 OpenAPI 规范可减少 60-75% 的 token 消耗。

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 3.4.0 |
| Spring AI | 1.0.0-M6 |
| Java | 17+ |
| springdoc-openapi | 2.8.6 |
| Maven | 3.8+ |

兼容任何 OpenAI API 兼容的 LLM 服务（OpenAI、DeepSeek 等）。

## 架构

### 请求流程

```
用户消息
  │
  ▼
ChatController (POST /api/chat)
  │
  ▼
AgentService.chat()
  ├── 重置已加载技能（无状态）
  │
  ▼
SkillsAdvisor (CallAroundAdvisor, HIGHEST_PRECEDENCE)
  ├── 注入 System Prompt：
  │   ├── Level 1: 所有技能名称 + 描述（来自 SkillRegistry）
  │   └── Level 2: 当前对话已加载技能的完整指令
  │
  ▼
LLM 决策
  ├── 调用 SkillTools.loadSkill(name)  → 获取完整 Markdown 指令 + 关联技能提示
  ├── 调用 SkillTools.httpRequest(...)  → 请求本地 REST API（或返回确认请求）
  │
  ▼
返回结果给用户（确认模式下，变更操作返回确认请求由前端执行）
```

### 核心组件

| 组件 | 职责 |
|------|------|
| `agent/SkillRegistry` | 启动时读取 `resources/skills/*/SKILL.md`，解析 YAML frontmatter + Markdown body |
| `agent/SkillTools` | `@Tool` 方法：`loadSkill`（渐进式披露入口）和 `httpRequest`（通用 HTTP 调用） |
| `agent/SkillsAdvisor` | `CallAroundAdvisor`，构建含 Level 1 目录 + Level 2 已加载内容的 System Prompt |
| `service/AgentService` | 编排 `ChatClient` + Advisor + Tools，每次请求重置技能状态 |
| `service/ProductService` | 内存商品目录和购物车（无数据库），预置 5 条示例数据 |

### Skills 格式

每个技能位于 `src/main/resources/skills/<skill-name>/SKILL.md`：

```yaml
---
name: search-products
description: 搜索商品目录，支持关键词、分类、价格范围过滤
version: 1.0
links:
  - name: get-product-detail
    description: 获取单个商品的详细信息
---

# 商品搜索技能
## API 端点
GET http://localhost:8080/api/products
...
```

- YAML frontmatter（`---` 分隔）：`name`、`description`、`version`、可选 `links`（关联技能）
- Markdown body：API 端点、参数、示例、下一步建议

技能通过 `links` 形成有向图，支持链式发现：`search-products → get-product-detail → add-to-cart → checkout`。

## 快速开始

### 前置要求

- JDK 17+
- Maven 3.8+
- OpenAI 兼容 API Key（OpenAI、DeepSeek 等）

### 配置

在项目根目录创建 `.env` 文件（已在 `.gitignore` 中排除）：

```bash
OPENAI_API_KEY=your-api-key
OPENAI_BASE_URL=https://api.deepseek.com   # 或 https://api.openai.com
OPENAI_MODEL=deepseek-chat                  # 或 gpt-4o
```

### 构建与运行

```bash
# 加载环境变量
set -a && source .env && set +a

# 构建
mvn clean package -DskipTests

# 运行
mvn spring-boot:run -DskipTests

# 运行（开启确认模式：变更操作需用户手动确认）
mvn spring-boot:run -DskipTests '-Dspring-boot.run.arguments=--app.confirm-before-mutate=true'
```

### 访问地址

| 功能 | URL |
|------|-----|
| 聊天界面 | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |

## 测试验证

以下是经过验证的测试步骤，可完整复现。

### 1. REST API 测试

启动应用后，验证商品 API 是否正常：

```bash
# 查询全部商品
curl -s http://localhost:8080/api/products
```

预期返回 5 条商品数据（iPhone 15、华为 MatePad Pro、Sony WH-1000XM5、小米电视 65寸、MacBook Air M3）。

```bash
# 按关键词搜索
curl -s "http://localhost:8080/api/products?keyword=耳机&priceMax=3000"
```

预期仅返回 Sony WH-1000XM5。

```bash
# 查看商品详情
curl -s http://localhost:8080/api/products/3
```

```bash
# 加入购物车
curl -s -X POST "http://localhost:8080/api/products/cart?userId=1&productId=3"
```

预期返回 `{"success":true,"message":"已添加到购物车","cartSize":1}`。

```bash
# 结算
curl -s -X POST "http://localhost:8080/api/products/checkout?userId=1"
```

预期返回 `{"success":true,"message":"订单已提交","totalAmount":2499.0,"itemCount":1}`。

### 2. Agent 聊天测试

测试完整的 Agent 流程（LLM → loadSkill → httpRequest → 回复）：

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"帮我找一款3000元以下的耳机"}' \
  --max-time 60
```

预期行为：
1. Agent 调用 `loadSkill("search-products")` 加载搜索技能
2. Agent 调用 `httpRequest(GET /api/products?priceMax=3000)` 或类似请求
3. 返回包含 Sony WH-1000XM5（2499 元）的推荐回复

```bash
# 测试完整购物流程
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"把 Sony WH-1000XM5 加入购物车并结算"}' \
  --max-time 60
```

预期行为：Agent 会依次加载 `add-to-cart` 和 `checkout` 技能，完成加购和结算。

### 3. 确认模式测试

以 `confirm-before-mutate=true` 启动应用后，Agent 对变更操作（POST/PUT/DELETE）不会直接执行，而是返回包含 `` ```http-request `` 代码块的确认请求：

```bash
# 以确认模式启动
mvn spring-boot:run -DskipTests '-Dspring-boot.run.arguments=--app.confirm-before-mutate=true'

# 发送加购请求
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"content":"请把商品ID为3的商品加入购物车（用户ID=1）"}' \
  --max-time 90
```

预期行为：
1. Agent 不直接执行加购操作
2. 返回操作说明 + `` ```http-request `` 代码块（含 method、url、params 等元数据）
3. 前端展示说明文本和「确定」/「取消」按钮
4. 用户点击「确定」后，前端根据元数据构造 HTTP 请求并发送到后端 API

### 4. Web 界面测试

在浏览器打开 http://localhost:8080 ，在聊天框中输入自然语言，如：

- "找一款3000元以内的耳机"
- "第一个商品的详细信息"
- "把它加入购物车"
- "帮我结算"

开启确认模式后，加购和结算等变更操作会显示确认按钮，需用户手动确认后才执行。

### 自动化测试

项目包含回归测试脚本，覆盖 REST API、Agent 聊天、确认模式（前端执行）等场景：

```bash
./test.sh
```

测试脚本会自动启动应用、运行全部测试用例（含确认模式重启验证），最后输出通过/失败汇总。

### 已验证环境

- macOS + JDK 23 + Maven 3.9
- DeepSeek API (`deepseek-chat` 模型)
- Spring AI 1.0.0-M6 + Spring Boot 3.4.0
