# Prompt Externalization Plan

## Document Info
- **Version**: 1.0
- **Created**: 2026-04-11
- **Status**: Draft
- **Author**: Claude Code

---

## 1. Background and Goals

### 1.1 Problem Statement

Currently, all AI model prompts in the project are hardcoded as Java string literals in source files:

- `SkillsAdvisor.java` - System prompt template and mode-specific rules
- `MultimodalAgentService.java` - Vision prompt for image description
- `ExplainResultService.java` - API result explanation prompt
- `AgUiConfig.java` - Enterprise agent system prompt
- `SkillTools.java` - Tool descriptions (though these use `@Tool` annotations)

### 1.2 Goals

1. **Extract prompts to resource files** - Move all hardcoded prompts to `src/main/resources/prompts/` directory
2. **Override capability** - Resource-based prompts should take precedence over hardcoded defaults
3. **Use `{{PLACEHOLDER}}` syntax** - Prompts should use `{{VARIABLE_NAME}}` placeholders instead of Java `String.format()` syntax
4. **String constants** - Where prompts must remain in Java code, they should be declared as `private static final String` constants

### 1.3 Non-Goals

- This plan does NOT address runtime prompt editing (admin UI, etc.)
- This plan does NOT change the existing prompt content or logic
- This plan does NOT modify the `skills/*.md` files (those are already externalized)

---

## 2. Current Prompt Inventory

### 2.1 Prompts Summary Table

| ID | Source File | Prompt Name | Lines | Type | Has Dynamic Content |
|----|-------------|-------------|-------|------|---------------------|
| P1 | `SkillsAdvisor.java` | SYSTEM_PROMPT_TEMPLATE | 66-83 | System | Yes ({{SKILL_LIST}}, {{API_BASE_URL}}, etc.) |
| P2 | `SkillsAdvisor.java` | buildModeSpecificRules() | 117-162 | System | No (static) |
| P3 | `MultimodalAgentService.java` | Vision prompt (with hint) | 131-133 | User | Yes (query variable) |
| P4 | `MultimodalAgentService.java` | Vision prompt (without hint) | 179 | User | No |
| P5 | `MultimodalAgentService.java` | Input enrichment labels | 59,65,67,72,77 | Input prefix | Yes (content varies) |
| P6 | `ExplainResultService.java` | API explanation prompt | 98-142 | User | Yes (API details, status, etc.) |
| P7 | `AgUiConfig.java` | Enterprise system prompt | 65-86 | System | No (static) |

### 2.2 Detailed Prompt Content

#### P1: SkillsAdvisor SYSTEM_PROMPT_TEMPLATE

**Location**: `src/main/java/com/example/demo/agent/SkillsAdvisor.java:66-83`

```java
private static final String SYSTEM_PROMPT_TEMPLATE = """
    你是一个智能助手。可用技能如下：

    <available_skills>
    {{SKILL_LIST}}
    </available_skills>

    **重要规则：**
    1. 使用某个技能前，必须先调用 `loadSkill` 工具加载它的完整指令
    2. 不要凭记忆猜测 API 参数，必须先加载技能查看文档
    3. 加载技能后，注意其 links 字段提示的关联技能
    4. API 基础 URL 是 {{API_BASE_URL}}（技能文档中的路径都是相对路径，调用 {{HTTP_TOOL_NAME}} 时只需传相对路径）
    5. 部分技能具有分层结构（如 OpenAPI 生成的技能），其 SKILL.md 中会列出 references 目录下的参考文件路径，
       需要调用 `readSkillReference` 工具读取具体的资源/操作文档，再据此调用 {{HTTP_TOOL_NAME}} 工具
    {{LOADED_CONTEXT}}

    {{MODE_RULES}}
    """;
```

**Placeholders**:
- `{{SKILL_LIST}}` - Dynamically generated from registry
- `{{API_BASE_URL}}` - Injected via `@Value`
- `{{HTTP_TOOL_NAME}}` - Returns "buildHttpRequest"
- `{{LOADED_CONTEXT}}` - Dynamic based on loaded skills
- `{{MODE_RULES}}` - Result of `buildModeSpecificRules()`

**Replacement Logic** (in `buildSystemPrompt()`):
```java
String systemPrompt = SYSTEM_PROMPT_TEMPLATE
    .replace("{{SKILL_LIST}}", skillList)
    .replace("{{API_BASE_URL}}", apiBaseUrl)
    .replace("{{HTTP_TOOL_NAME}}", httpToolName)
    .replace("{{LOADED_CONTEXT}}", loadedContext)
    .replace("{{MODE_RULES}}", modeRules);
```

---

#### P2: SkillsAdvisor buildModeSpecificRules()

**Location**: `src/main/java/com/example/demo/agent/SkillsAdvisor.java:117-162`

```java
private String buildModeSpecificRules() {
    return """
        6. 【强制规则 - 必须严格遵守】
           - **httpRequest 工具**：仅用于调用**完全公开的、无需认证的外部 API**（如公开的天气 API，商品搜索 API 等）
           - **buildHttpRequest 工具**：用于构建所有需要认证的 API 调用的请求元数据（如方法、路径、查询参数、请求头、请求体）
           ...

        7. 【用户确认模式 - 核心流程】
        ...

        8. 【用户认证状态 - 重要说明】
        ...

        9. 【错误示例 - 绝对禁止这样做】
        ...
        """;
}
```

**Type**: Static, no placeholders. Returns a fixed multi-line string.

---

#### P3: Vision Prompt (with user query hint)

**Location**: `src/main/java/com/example/demo/service/MultimodalAgentService.java:131-133`

```java
String visionPrompt = (query != null && !query.isBlank())
        ? "用户问题是：" + query + "\n请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。"
        : "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";
```

**Note**: This is constructed dynamically using string concatenation, not a template.

**Occurrences**:
- Line 131-133 (in `streamImageOnly()`)
- Line 227-229 (in `describeImage()`)

---

#### P4: Vision Prompt (without hint)

**Location**: `src/main/java/com/example/demo/service/MultimodalAgentService.java:179`

```java
String visionPrompt = "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";
```

**Note**: Used in `streamImageAndAudio()` when processing image+audio combination.

---

#### P5: Input Enrichment Labels

**Location**: `src/main/java/com/example/demo/service/MultimodalAgentService.java`

| Line | Label | Dynamic Content |
|------|-------|-----------------|
| 59 | `【图片内容】` + imageDescription | Yes - imageDescription from vision model |
| 65 | `【语音转写】` + transcription | Yes - transcription from ASR |
| 67 | `【语音转写】语音转写功能未配置，无法处理音频。` | No - static error message |
| 72 | `【用户输入】` + query | Yes - query from user |
| 77 | `用户未提供有效输入。` | No - static fallback |

**Usage context**: These are prefix labels that enrich the user input before passing to the LLM.

---

#### P6: API Explanation Prompt

**Location**: `src/main/java/com/example/demo/service/ExplainResultService.java:98-142`

```java
private String buildPrompt(ExplainRequest request, String apiDescription) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("用户刚刚执行了一个 API 操作，请用简洁友好的中文解释发生了什么。\n\n");
    prompt.append("## 操作信息\n");
    prompt.append("- **端点**: ").append(request.getMethod()).append(" ").append(request.getUrl()).append("\n");
    // ... query params ...
    prompt.append("\n## 响应状态\n");
    prompt.append("HTTP ").append(request.getStatusCode());
    // ... response body, API description, output requirements ...
    return prompt.toString();
}
```

**Variables**:
- HTTP method (GET/POST/PUT/DELETE)
- URL endpoint
- Query parameters (key-value pairs)
- Status code
- Response body (JSON)
- API description (from skill registry or null)
- Output requirements (static list)

---

#### P7: Enterprise Agent System Prompt

**Location**: `src/main/java/com/example/demo/config/AgUiConfig.java:65-86`

```java
.systemMessage("""
    你是企业智能助手，帮助员工解答业务问题、查询数据、执行操作。

    你可以使用以下能力：
    1. 技能加载：根据用户需求加载相应的技能模块
    2. API 调用：通过 httpRequest 工具调用 REST API
    3. 数据查询：查询商品、订单、员工等信息
    4. 操作执行：执行各种业务操作（可能需要用户确认）

    回答要求：
    - 使用中文，简洁专业
    - 对于敏感操作，先向用户说明并征得同意
    - 如果需要查询数据，优先使用提供的工具函数
    - 按需加载技能，不要一次性加载所有技能

    **格式化要求：**
    - 使用 Markdown 格式组织所有回复内容
    - 使用 **加粗** 强调重要信息
    - 使用列表（- 或 1.）组织多个要点
    - 使用代码块（```）展示代码或技术内容
    - 确保表格列对齐，增强可读性
    """)
```

**Type**: Static, no placeholders.

---

## 3. Proposed Solution

### 3.1 Resource File Structure

Create a new directory `src/main/resources/prompts/` with the following structure:

```
src/main/resources/prompts/
├── skills-advisor/
│   ├── system-prompt.template          # P1: Main system prompt template
│   └── mode-rules.template             # P2: Mode-specific rules (static)
├── multimodal/
│   ├── vision-prompt-with-hint.template # P3: Vision prompt with user query
│   ├── vision-prompt.template          # P4: Vision prompt without hint
│   └── input-labels.properties          # P5: Input enrichment labels
├── explain-result/
│   └── api-explanation-prompt.template # P6: API explanation prompt
└── enterprise-agent/
    └── system-prompt.template          # P7: Enterprise agent system prompt
```

### 3.2 PromptLoader Service

Create a new `PromptLoader` service that handles:

1. **Loading templates from classpath resources** using `ResourceLoader` (same pattern as `KnowledgeBaseInitializer`)
2. **Caching loaded templates** in `ConcurrentHashMap`
3. **Fallback to hardcoded defaults if resource not found** - ensures zero risk deployment
4. **Replacing `{{PLACEHOLDER}}` tokens with values** using simple `String.replace()`

```java
@Service
public class PromptLoader {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private final Map<String, String> DEFAULT_PROMPTS = new HashMap<>();

    public PromptLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        // Initialize default prompts map with hardcoded values
        DEFAULT_PROMPTS.put("prompts/skills-advisor/system-prompt.template", SYSTEM_PROMPT_TEMPLATE);
        DEFAULT_PROMPTS.put("prompts/skills-advisor/mode-rules.template", MODE_RULES_DEFAULT);
        // ... other defaults
    }

    /**
     * Load prompt template from resources, fallback to default if not found
     */
    public String getPrompt(String resourcePath, Map<String, String> placeholders) {
        String cached = templateCache.get(resourcePath);
        if (cached != null) {
            return replacePlaceholders(cached, placeholders);
        }

        String template = loadTemplate(resourcePath);
        if (template == null) {
            // Fallback to default
            template = DEFAULT_PROMPTS.getOrDefault(resourcePath, "");
        }

        templateCache.put(resourcePath, template);
        return replacePlaceholders(template, placeholders);
    }

    /**
     * Load static prompt (no placeholders)
     */
    public String getPrompt(String resourcePath) {
        return getPrompt(resourcePath, Collections.emptyMap());
    }

    private String loadTemplate(String resourcePath) {
        try {
            Resource resource = resourceLoader.getResource(resourcePath);
            if (resource.exists() && resource.isReadable()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            // Log warning and fallback to default
        }
        return null;
    }

    private String replacePlaceholders(String template, Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
```

**Key Design Points**:
- Uses `ResourceLoader` (Spring's standard resource loading mechanism)
- Supports both classpath (`classpath:prompts/xxx`) and file (`file:/path/to/xxx`) resources
- Defaults are stored in code as fallback (zero-risk deployment)
- Simple `String.replace()` is sufficient since `{{PLACEHOLDER}}` doesn't use regex

### 3.3 Override Mechanism

The override mechanism works as follows:

1. **Resource files take precedence** - If `src/main/resources/prompts/xxx.template` exists, it will be loaded
2. **Hardcoded defaults as fallback** - If resource file is missing, the hardcoded string in Java is used
3. **Environment variable override** - Optional: `PROMPTS_RESOURCE_PATH` can point to external directory

```
Priority (highest to lowest):
1. External file system (if PROMPTS_RESOURCE_PATH configured)
2. Classpath resource (src/main/resources/prompts/)
3. Hardcoded default in Java code
```

### 3.4 Placeholder Replacement

All templates use `{{PLACEHOLDER_NAME}}` syntax:

```java
// Example usage
String template = promptLoader.getPrompt(
    "prompts/skills-advisor/system-prompt.template",
    DEFAULT_SYSTEM_PROMPT,
    Map.of(
        "{{SKILL_LIST}}", skillList,
        "{{API_BASE_URL}}", apiBaseUrl,
        "{{HTTP_TOOL_NAME}}", httpToolName,
        "{{LOADED_CONTEXT}}", loadedContext,
        "{{MODE_RULES}}", modeRules
    )
);
```

### 3.5 Input Labels Treatment

Input enrichment labels (P5) are treated specially because:

- They are short strings, not full prompts
- They need to be combined with dynamic content at runtime

**Proposed solution**: Use a `.properties` file:

```properties
# src/main/resources/prompts/multimodal/input-labels.properties
label.image.content=【图片内容】
label.audio.transcribed=【语音转写】
label.audio.not.configured=【语音转写】语音转写功能未配置，无法处理音频。
label.user.input=【用户输入】
label.no.input=用户未提供有效输入。
```

**Usage in MultimodalAgentService**:

The dynamic combination happens in Java code using `PromptLoader`:

```java
// Load label from properties (with fallback)
String imageLabel = promptLoader.getLabel("label.image.content", "【图片内容】");
String imageDescription = describeImage(image, imageContentType, query);

// Combine: label + dynamic content
enrichedInput.append(imageLabel).append(imageDescription).append("\n\n");
```

The `PromptLoader.getLabel()` method loads from properties file with hardcoded fallback:

```java
public String getLabel(String key, String defaultValue) {
    // Try to load from properties (cached)
    // Fallback to defaultValue if not found
}
```

---

## 4. Implementation Plan

### 4.1 Phase 1: Create PromptLoader Service

**Files to create**:
- `src/main/java/com/example/demo/service/PromptLoader.java`

**Tasks**:
1. Create `PromptLoader` class with resource loading logic
2. Implement template caching
3. Implement `{{PLACEHOLDER}}` replacement
4. Add fallback to hardcoded defaults

### 4.2 Phase 2: Create Resource Files

**Files to create**:
- `src/main/resources/prompts/skills-advisor/system-prompt.template`
- `src/main/resources/prompts/skills-advisor/mode-rules.template`
- `src/main/resources/prompts/multimodal/vision-prompt-with-hint.template`
- `src/main/resources/prompts/multimodal/vision-prompt.template`
- `src/main/resources/prompts/multimodal/input-labels.properties`
- `src/main/resources/prompts/explain-result/api-explanation-prompt.template`
- `src/main/resources/prompts/enterprise-agent/system-prompt.template`

### 4.3 Phase 3: Update SkillsAdvisor

**File to modify**: `src/main/java/com/example/demo/agent/SkillsAdvisor.java`

**Changes**:
1. Inject `PromptLoader`
2. Replace `SYSTEM_PROMPT_TEMPLATE` constant with resource-based loading
3. Keep `buildModeSpecificRules()` but load from resource
4. Update `buildSystemPrompt()` to use `PromptLoader`

### 4.4 Phase 4: Update MultimodalAgentService

**File to modify**: `src/main/java/com/example/demo/service/MultimodalAgentService.java`

**Changes**:
1. Inject `PromptLoader`
2. Load vision prompts from resources
3. Load input labels from properties

### 4.5 Phase 5: Update ExplainResultService

**File to modify**: `src/main/java/com/example/demo/service/ExplainResultService.java`

**Changes**:
1. Inject `PromptLoader`
2. Load API explanation prompt from resource
3. Update `buildPrompt()` to use template with placeholders

### 4.6 Phase 6: Update AgUiConfig

**File to modify**: `src/main/java/com/example/demo/config/AgUiConfig.java`

**Changes**:
1. Load enterprise system prompt from resource
2. Provide via `@Value` or `PromptLoader`

### 4.7 Phase 7: Testing

**Tasks**:
1. Verify all existing functionality works unchanged
2. Test that resource files properly override defaults
3. Test placeholder replacement works correctly
4. Test that missing resource files fall back to defaults

---

## 5. Risk Analysis

### 5.1 Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Resource files not found at runtime | Low | High | Robust fallback to hardcoded defaults |
| Placeholder replacement bugs | Medium | Medium | Thorough unit testing |
| Encoding issues (UTF-8) | Low | Medium | Ensure all template files are UTF-8 |
| Template syntax errors | Medium | High | Validate template syntax on startup |

### 5.2 Mitigation Strategies

1. **Startup validation**: Log warning if resource templates are missing but defaults exist
2. **Template validation**: Validate `{{PLACEHOLDER}}` syntax on load
3. **Comprehensive testing**: Test both with and without resource files present

---

## 6. File Change Summary

### 6.1 New Files

| File | Purpose |
|------|---------|
| `src/main/java/com/example/demo/service/PromptLoader.java` | Centralized prompt loading service |
| `src/main/resources/prompts/skills-advisor/system-prompt.template` | SkillsAdvisor main prompt |
| `src/main/resources/prompts/skills-advisor/mode-rules.template` | SkillsAdvisor mode rules |
| `src/main/resources/prompts/multimodal/vision-prompt-with-hint.template` | Vision prompt template |
| `src/main/resources/prompts/multimodal/vision-prompt.template` | Vision prompt without hint |
| `src/main/resources/prompts/multimodal/input-labels.properties` | Input enrichment labels |
| `src/main/resources/prompts/explain-result/api-explanation-prompt.template` | API explanation prompt |
| `src/main/resources/prompts/enterprise-agent/system-prompt.template` | Enterprise agent prompt |

### 6.2 Modified Files

| File | Changes |
|------|---------|
| `SkillsAdvisor.java` | Inject PromptLoader, use resources |
| `MultimodalAgentService.java` | Inject PromptLoader, use resources |
| `ExplainResultService.java` | Inject PromptLoader, use resources |
| `AgUiConfig.java` | Load prompt from resources |

### 6.3 No Changes Required

| File | Reason |
|------|--------|
| `SkillTools.java` | Tool descriptions use `@Tool` annotations, which is appropriate |
| `AgentService.java` | No direct prompts, delegates to other services |
| Controller files | No prompts, only request handling |

---

## 7. Testing Strategy

### 7.1 Unit Tests

1. **PromptLoaderTest**: Test loading, caching, fallback, and placeholder replacement
2. Each modified service should have basic tests verifying prompt loading

### 7.2 Integration Tests

1. Start application with resource files present
2. Start application with resource files absent (fallback)
3. Verify all chat flows work correctly in both scenarios

### 7.3 Manual Verification

1. Use existing test scripts (`test-multimodal.sh`)
2. Verify SkillsAdvisor prompts appear in logs
3. Verify vision prompts work for image inputs

---

## 8. Rollback Plan

If issues arise after deployment:

1. **Remove resource files** - Application will automatically fall back to hardcoded defaults
2. **Disable PromptLoader** - Revert modified files to original state
3. **No database migrations needed** - No persistent state changes

---

## 9. Open Questions

1. Should we add environment variable to completely disable resource-based prompts?
2. Do we need version info in template files for debugging?
3. Should we add template validation on startup (fail-fast vs warn-only)?

---

## 10. Appendix: Template Format Specification

### 10.1 Placeholder Syntax

- Format: `{{PLACEHOLDER_NAME}}`
- Names: Uppercase with underscores (e.g., `{{SKILL_LIST}}`, `{{API_BASE_URL}}`)
- Whitespace: Preserved around placeholders

### 10.2 Template File Encoding

- All files must be UTF-8 encoded
- No BOM (Byte Order Mark)
- Line endings:LF (Unix-style)

### 10.3 Properties File Format

- Standard Java `.properties` format
- UTF-8 encoding (Spring handles this automatically)
- Keys should be lowercase with dots (e.g., `label.image.content`)

---

*Document Version: 1.0*
*Last Updated: 2026-04-11*
