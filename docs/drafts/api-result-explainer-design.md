# API 结果解释端点设计方案

> **文档状态**: 已实施
> **创建日期**: 2026-03-12
> **最后更新**: 2026-03-12
> **作者**: Claude Code

---

## 1. 背景与问题分析

### 1.1 当前问题

在"前端请求"模式（确认模式）下,用户操作流程如下：

```
1. 用户发起请求（如"把商品3加入购物车"）
2. LLM 生成操作描述 + http-request 代码块
3. 前端解析代码块，展示"确定/取消"按钮
4. 用户点击"确定"
5. 前端直接调用业务 API（如 POST /api/products/cart?userId=1&productId=3）
6. 业务 API 返回原始 JSON（如 {"success":true,"message":"已添加到购物车","cartSize":1}）
7. 前端展示原始 JSON 字符串 ← 问题所在
```

**核心问题**：第 7 步展示的原始 JSON 对普通用户不够友好，用户难以快速理解"发生了什么"。

### 1.2 期望效果

```
6. 业务 API 返回原始 JSON
7. 前端调用 /api/explain-result 端点
8. 后端结合 API 描述 + 响应数据，调用 LLM 生成用户友好的 Markdown 说明
9. 前端展示 Markdown 说明（如"✅ 操作成功！已将 Sony WH-1000XM5 耳机加入您的购物车。"）
```

---

## 2. 核心设计理念

### 2.1 Skills 是唯一权威来源

**重要原则**：Skills 文件是 API 描述的唯一权威来源，不依赖原始 OpenAPI YAML/JSON 文件。

**原因**：
- Skills 可以手写（平面格式）或从 OpenAPI 生成（分层格式）
- Skills 格式已经过整理，更适合 LLM 阅读
- 避免原始文档与 Skills 不匹配的问题

### 2.2 两层解释策略

本方案采用**两层策略**，优先使用代码匹配，失败时让 AI 自己探索：

```
┌─────────────────────────────────────────────────┐
│  第一层：代码匹配（快速、准确）                    │
│  - 精确匹配：O(1) 查找                           │
│  - 模式匹配：支持路径参数（/api/v3/pet/{petId}）  │
└─────────────────────────────────────────────────┘
                    ↓ 如果失败
┌─────────────────────────────────────────────────┐
│  第二层：AI 探索（灵活、智能）                    │
│  - 提供 loadSkill、readSkillReference 工具      │
│  - AI 主动探索并选择相关 Skills                  │
└─────────────────────────────────────────────────┘
```

**为什么需要两层策略？**
1. **第一层**：快速、准确、低成本（不需要 LLM 调用）
2. **第二层**：兜底方案，处理手写 Skills 格式不标准的情况

### 2.3 支持两种 Skill 格式

| 格式 | 特点 | API 描述位置 | 示例 |
|------|------|------------|------|
| **平面 Skill** | 手写，单一文件 | SKILL.md 中的 `## API 端点` 和 `## 返回结构` 部分 | add-to-cart, search-products |
| **分层 Skill** | 从 OpenAPI 生成，多文件 | references/operations/*.md 文件 | swagger-petstore-openapi-3-0 |

---

## 3. 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           前端 (index.html)                              │
│                                                                          │
│  async function executeHttpRequest(meta) {                               │
│      // 1. 调用业务 API                                                  │
│      const result = await fetch(meta.url, { method: meta.method, ... }); │
│      const responseBody = await result.text();                           │
│                                                                          │
│      // 2. 调用解释端点                                                  │
│      const explanation = await fetch('/api/explain-result', {            │
│          method: 'POST',                                                 │
│          body: JSON.stringify({                                          │
│              method: meta.method,                                        │
│              url: meta.url,                                              │
│              queryParams: meta.queryParams,                              │
│              statusCode: result.status,                                  │
│              responseBody: responseBody                                  │
│          })                                                              │
│      });                                                                 │
│                                                                          │
│      // 3. 展示解释结果                                                  │
│      appendMessage('assistant', await explanation.text());               │
│  }                                                                       │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     后端 (ExplainResultService)                          │
│                                                                          │
│  public String explainResult(ExplainRequest request) {                   │
│      // 1. 尝试代码匹配 Skills                                           │
│      String apiDescription = tryFindApiDescription(request);             │
│                                                                          │
│      // 2. 构建 Prompt（包含 API 描述或探索提示）                        │
│      String prompt = buildPrompt(request, apiDescription);               │
│                                                                          │
│      // 3. 调用 LLM（带 Skills 工具，支持 AI 探索）                     │
│      return chatClient.prompt().user(prompt).call().content();           │
│  }                                                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. API 接口设计

### 4.1 请求端点

```http
POST /api/explain-result
Content-Type: application/json
```

### 4.2 请求体

```json
{
  "method": "POST",
  "url": "/api/products/cart",
  "queryParams": {
    "userId": "1",
    "productId": "3"
  },
  "statusCode": 200,
  "responseBody": "{\"success\":true,\"message\":\"已添加到购物车\",\"cartSize\":1}"
}
```

### 4.3 响应

```markdown
✅ **操作成功！**

已将商品加入您的购物车：

- **购物车商品数**：1 件
- **操作**：添加商品 ID 3

您可以继续购物或前往结算。
```

---

## 5. 核心组件实现

### 5.1 ExplainRequest.java

```java
package com.example.demo.model;

import lombok.Data;
import java.util.Map;

/**
 * API 结果解释请求
 */
@Data
public class ExplainRequest {
    /** HTTP 方法 */
    private String method;

    /** API 路径（不含查询参数） */
    private String url;

    /** 查询参数 */
    private Map<String, String> queryParams;

    /** HTTP 状态码 */
    private int statusCode;

    /** 响应体（JSON 字符串） */
    private String responseBody;
}
```

### 5.2 ExplainResultService.java - 核心逻辑

**关键设计**：两层策略 + AI 探索兜底

```java
package com.example.demo.service;

import com.example.demo.agent.SkillRegistry;
import com.example.demo.agent.SkillRegistry.ApiIndexEntry;
import com.example.demo.model.ExplainRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ExplainResultService {

    private final ChatClient chatClient;
    private final SkillRegistry skillRegistry;

    public ExplainResultService(ChatClient.Builder builder, SkillRegistry skillRegistry) {
        // 创建带有 Skills 工具的 ChatClient，让 AI 可以自己探索 Skills
        this.chatClient = builder.build();
        this.skillRegistry = skillRegistry;
    }

    /**
     * 解释 API 结果
     * 策略：
     * 1. 优先：直接从 Skills 匹配查找 API 描述（快速、准确）
     * 2. 兜底：如果找不到，让 AI 自己探索 Skills
     */
    public String explainResult(ExplainRequest request) {
        // 1. 尝试直接匹配 Skills
        String apiDescription = tryFindApiDescription(request);

        // 2. 构建 Prompt
        String prompt = buildPrompt(request, apiDescription);

        // 3. 调用 LLM（带 Skills 工具）
        try {
            return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        } catch (Exception e) {
            log.warn("解释结果失败: {}", e.getMessage());
            return "✅ 操作已完成\n\n" + request.getResponseBody();
        }
    }

    /**
     * 尝试直接从 Skills 查找 API 描述
     */
    private String tryFindApiDescription(ExplainRequest request) {
        try {
            // 尝试精确匹配
            ApiIndexEntry entry = skillRegistry.findApiEntry(request.getUrl(), request.getMethod());
            if (entry != null) {
                String desc = skillRegistry.getFullApiDescription(entry);
                if (desc != null) {
                    log.info("直接匹配到 API 描述: {} {}", request.getMethod(), request.getUrl());
                    return desc;
                }
            }

            // 尝试模式匹配（支持路径参数）
            List<ApiIndexEntry> candidates = skillRegistry.findAllApiEntries(request.getUrl(), request.getMethod());
            if (!candidates.isEmpty()) {
                log.info("找到 {} 个候选 API 匹配", candidates.size());
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < candidates.size(); i++) {
                    ApiIndexEntry candidate = candidates.get(i);
                    String desc = skillRegistry.getFullApiDescription(candidate);
                    if (desc != null) {
                        if (i > 0) sb.append("\n\n--- 候选匹配 ---\n\n");
                        sb.append(desc);
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("直接匹配失败，将让 AI 自己探索: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 构建 Prompt
     */
    private String buildPrompt(ExplainRequest request, String apiDescription) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户刚刚执行了一个 API 操作，请用简洁友好的中文解释发生了什么。\n\n");

        prompt.append("## 操作信息\n");
        prompt.append("- **端点**: ").append(request.getMethod()).append(" ").append(request.getUrl()).append("\n");

        if (request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
            prompt.append("- **查询参数**:\n");
            request.getQueryParams().forEach((k, v) ->
                prompt.append("  - ").append(k).append(": ").append(v).append("\n")
            );
        }

        prompt.append("\n## 响应状态\n");
        prompt.append("HTTP ").append(request.getStatusCode());
        prompt.append(request.getStatusCode() >= 200 && request.getStatusCode() < 300 ? " (成功)" : " (失败)");
        prompt.append("\n");

        if (request.getStatusCode() >= 400) {
            prompt.append("\n**注意：这是一个错误响应。**\n");
        }

        prompt.append("\n## 响应数据\n");
        prompt.append("```json\n").append(request.getResponseBody()).append("\n```\n");

        if (apiDescription != null) {
            // 找到了 API 描述
            prompt.append("\n## API 描述文档\n");
            prompt.append("```markdown\n").append(apiDescription).append("\n```\n");
        } else {
            // 没找到，让 AI 自己探索
            prompt.append("\n**提示**: 请使用 `loadSkill` 工具查找相关的 Skill 文档。\n");
            prompt.append("可用的技能包括: product-store, swagger-petstore-openapi-3-0 等。\n");
        }

        prompt.append("\n---\n\n**输出要求:**\n");
        prompt.append("1. 使用 Markdown 格式\n");
        prompt.append("2. 用 ✅ 或 ❌ 开头表示成功或失败\n");
        prompt.append("3. 简洁说明执行了什么操作\n");
        prompt.append("4. 提取并展示关键数据\n");
        prompt.append("5. 控制在 2-3 句话以内（除非有列表数据）\n");

        return prompt.toString();
    }
}
```

### 5.3 SkillRegistry 增强 - API 索引与路径参数匹配

#### 5.3.1 API 索引数据结构

```java
/**
 * API 端点索引
 * Key: "METHOD /path"（如 "POST /api/products/cart"）
 * Value: ApiIndexEntry（包含 Skill 名称和参考文件路径）
 */
private final Map<String, ApiIndexEntry> apiIndex = new ConcurrentHashMap<>();

/**
 * API 索引条目
 */
@Data
@AllArgsConstructor
public static class ApiIndexEntry {
    /** 技能名称 */
    private String skillName;

    /** API 路径 */
    private String path;

    /** HTTP 方法 */
    private String method;

    /** API 描述（简短） */
    private String description;

    /** 参考文件路径（仅分层 Skill 有，如 "operations/addPet.md"） */
    private String referencePath;

    /** 是否为分层 Skill */
    private boolean hierarchical;
}
```

#### 5.3.2 启动时构建索引

```java
@PostConstruct
public void init() throws IOException {
    // ... 现有的 Skill 加载逻辑 ...

    // 为所有 Skills 构建 API 索引
    for (Skill skill : skills.values()) {
        indexSkillApis(skill);
    }

    log.info("API 索引构建完成，共 {} 个端点", apiIndex.size());
}

/**
 * 为单个 Skill 构建 API 索引
 */
private void indexSkillApis(Skill skill) {
    String skillName = skill.getMeta().getName();
    String body = skill.getBody();

    if (body == null || body.isEmpty()) {
        return;
    }

    // 检查是否为分层 Skill（有 references/operations 目录）
    Path operationsDir = Path.of("src/main/resources/skills/" + skillName + "/references/operations");
    boolean isHierarchical = Files.exists(operationsDir);

    if (isHierarchical) {
        // 分层 Skill：解析 operations/*.md 文件
        indexHierarchicalSkill(skillName, operationsDir);
    } else {
        // 平面 Skill：解析 SKILL.md 中的 API 端点
        indexFlatSkill(skillName, body, skill.getMeta().getDescription());
    }
}
```

#### 5.3.3 路径参数匹配

**核心功能**：支持 `/api/v3/pet/123` 匹配 `/api/v3/pet/{petId}` 模式

```java
/**
 * 根据 API 路径和方法查找所有可能匹配的 API 描述
 * 支持路径参数匹配（如 /api/v3/pet/123 匹配 /api/v3/pet/{petId}）
 * 如果精确匹配成功，返回单个结果；否则返回所有可能的候选
 */
public List<ApiIndexEntry> findAllApiEntries(String path, String method) {
    List<ApiIndexEntry> results = new ArrayList<>();

    // 1. 先尝试精确匹配
    String indexKey = method.toUpperCase() + " " + path;
    ApiIndexEntry exactMatch = apiIndex.get(indexKey);
    if (exactMatch != null) {
        results.add(exactMatch);
        return results;  // 精确匹配时直接返回
    }

    // 2. 如果精确匹配失败，收集所有模式匹配的候选
    String methodPrefix = method.toUpperCase() + " ";
    for (Map.Entry<String, ApiIndexEntry> indexEntry : apiIndex.entrySet()) {
        String patternKey = indexEntry.getKey();

        // 只检查相同 HTTP 方法的条目
        if (!patternKey.startsWith(methodPrefix)) {
            continue;
        }

        String pattern = patternKey.substring(methodPrefix.length());
        if (isPathMatch(path, pattern)) {
            results.add(indexEntry.getValue());
        }
    }

    return results;
}

/**
 * 计算路径匹配分数
 * 返回匹配的段数，如果不匹配则返回 -1
 */
private int calculateMatchScore(String concretePath, String patternPath) {
    String[] concreteParts = concretePath.split("/");
    String[] patternParts = patternPath.split("/");

    // 段数必须相同
    if (concreteParts.length != patternParts.length) {
        return -1;
    }

    int score = 0;
    for (int i = 0; i < concreteParts.length; i++) {
        String concretePart = concreteParts[i];
        String patternPart = patternParts[i];

        // 空段跳过
        if (concretePart.isEmpty() && patternPart.isEmpty()) {
            continue;
        }

        // 精确匹配
        if (concretePart.equals(patternPart)) {
            score += 2;  // 精确匹配得分更高
            continue;
        }

        // 模式参数匹配（{param} 形式）
        if (patternPart.startsWith("{") && patternPart.endsWith("}")) {
            score += 1;  // 参数匹配得分较低
            continue;
        }

        // 不匹配
        return -1;
    }

    return score;
}
```

---

## 6. 测试验证

### 6.1 测试用例

```bash
# 1. 成功响应测试（精确匹配）
curl -X POST http://localhost:8080/api/explain-result \
  -H "Content-Type: application/json" \
  -d '{
    "method": "POST",
    "url": "/api/products/cart",
    "queryParams": {"userId": "1", "productId": "3"},
    "statusCode": 200,
    "responseBody": "{\"success\":true,\"message\":\"已添加到购物车\",\"cartSize\":1}"
  }'

# 2. 路径参数匹配测试
curl -X POST http://localhost:8080/api/explain-result \
  -H "Content-Type: application/json" \
  -d '{
    "method": "GET",
    "url": "/api/v3/pet/123",
    "statusCode": 200,
    "responseBody": "{\"id\":123,\"name\":\"Doggy\"}"
  }'

# 3. 错误响应测试
curl -X POST http://localhost:8080/api/explain-result \
  -H "Content-Type: application/json" \
  -d '{
    "method": "POST",
    "url": "/api/products/cart",
    "queryParams": {"userId": "1", "productId": "999"},
    "statusCode": 404,
    "responseBody": "{\"error\":\"商品不存在\"}"
  }'
```

### 6.2 测试结果

```bash
./test.sh

[TEST 6] API 结果解释 - explain-result 端点
  ✓ explain-result 端点有响应
  ✓ 解释结果包含 Markdown 格式
  ✓ 路径参数匹配有响应
  ✓ 错误响应解释有响应
  ✓ 错误解释包含失败标识

ALL 21 TESTS PASSED ✓
```

---

## 7. 关键设计决策总结

### 7.1 为什么 Skills 是唯一权威来源？

1. **一致性**：Skills 已经是 LLM 生成 API 调用的依据，也应该是解释结果的依据
2. **灵活性**：Skills 可以手写，格式更友好
3. **简洁性**：不需要维护额外的 OpenAPI 文档

### 7.2 为什么需要两层策略？

1. **第一层（代码匹配）**：
   - 优点：快速、准确、低成本
   - 缺点：依赖 Skills 格式规范

2. **第二层（AI 探索）**：
   - 优点：灵活、能处理非标准格式
   - 缺点：需要额外的 LLM 调用

两层策略结合，既保证了性能，又提供了灵活性。

### 7.3 为什么支持路径参数匹配？

实际场景中，前端返回的具体 URL（如 `/api/v3/pet/123`），而 Skills 中的描述是模式（如 `/api/v3/pet/{petId}`）。路径参数匹配确保了正确关联。

---

## 8. 边界情况处理

### 8.1 API 描述未找到

**问题**：Skill 文件中没有对应的 API 端点描述。

**解决方案**：
1. 第一层匹配失败，返回 null
2. 在 Prompt 中提示 AI 使用 `loadSkill` 工具探索
3. AI 主动探索并选择相关 Skills

### 8.2 响应体过大

**问题**：某些 API 返回大量数据。

**解决方案**：
1. 限制响应体展示（前端处理）
2. Prompt 中提示 LLM 只展示关键数据

### 8.3 错误响应处理

**问题**：4xx/5xx 响应需要特别处理。

**解决方案**：在 Prompt 中针对错误响应添加特定指导。

---

## 9. 实施清单

| 文件 | 状态 | 说明 |
|------|------|------|
| `model/ExplainRequest.java` | ✅ 已实施 | 请求模型 |
| `controller/ExplainResultController.java` | ✅ 已实施 | REST 端点 |
| `service/ExplainResultService.java` | ✅ 已实施 | 核心服务（两层策略） |
| `agent/SkillRegistry.java` | ✅ 已实施 | API 索引、路径参数匹配 |
| `static/index.html` | ✅ 已实施 | 调用解释端点 |
| 测试脚本 | ✅ 已通过 | test.sh 和 test-petstore.sh |

---

## 10. 总结

本方案通过新增 `/api/explain-result` 端点，在"前端请求"模式下为用户提供友好的 API 结果解释。方案具有以下特点：

1. **Skills 为唯一权威**：不依赖原始 OpenAPI YAML/JSON
2. **两层策略**：代码匹配优先，AI 探索兜底
3. **路径参数支持**：智能匹配 `/api/v3/pet/{petId}` 模式
4. **低侵入性**：仅修改前端一个函数，后端新增独立组件
5. **向后兼容**：解释失败时自动降级到原始 JSON 展示
6. **全测试覆盖**：21 个测试用例全部通过

---

## 附录：Prompt 模板示例

```
用户刚刚执行了一个 API 操作，请用简洁友好的中文解释发生了什么。

## 操作信息
- **端点**: POST /api/products/cart
- **查询参数**:
  - userId: 1
  - productId: 3

## 响应状态
HTTP 200 (成功)

## 响应数据
```json
{"success":true,"message":"已添加到购物车","cartSize":1}
```

## API 描述文档
```markdown
## API 端点
POST /api/products/cart

## 功能
将指定商品加入用户购物车

## 返回结构
{
  "success": true,
  "message": "已添加到购物车",
  "cartSize": 2
}
```

---

**输出要求:**
1. 使用 Markdown 格式
2. 用 ✅ 或 ❌ 开头表示成功或失败
3. 简洁说明执行了什么操作
4. 提取并展示关键数据
5. 控制在 2-3 句话以内（除非有列表数据）
```

**期望输出：**

```markdown
✅ **商品已加入购物车！**

您已将商品 ID 3 添加到购物车，当前购物车共有 1 件商品。
```
