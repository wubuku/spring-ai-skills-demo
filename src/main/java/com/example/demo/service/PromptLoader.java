package com.example.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Prompt Loader Service
 *
 * Loads AI prompt templates from classpath resources with fallback to hardcoded defaults.
 * Supports {{PLACEHOLDER}} syntax for template variables.
 *
 * Priority (highest to lowest):
 * 1. Classpath resource (src/main/resources/prompts/)
 * 2. Hardcoded default in Java code
 */
@Service
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    // =========================================================================
    // Default Prompt Templates (fallback when resource files are not found)
    // =========================================================================

    /** P1: SkillsAdvisor System Prompt Template */
    private static final String DEFAULT_SKILLS_ADVISOR_SYSTEM_PROMPT = """
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

    /** P2: SkillsAdvisor Mode Rules */
    private static final String DEFAULT_SKILLS_ADVISOR_MODE_RULES = """
            6. 【强制规则 - 必须严格遵守】
               - **httpRequest 工具**：仅用于调用**完全公开的、无需认证的外部 API**（如公开的天气 API，商品搜索 API 等）
               - **buildHttpRequest 工具**：用于构建所有需要认证的 API 调用的请求元数据（如方法、路径、查询参数、请求头、请求体）
               - **任何涉及当前用户数据的操作**（如查看购物车、查询订单、添加购物车、结算）：**必须使用 buildHttpRequest**
               - **绝对禁止**：对需要认证的 API 使用 httpRequest 工具（会失败并返回 403 错误）

            7. 【用户确认模式 - 核心流程】
               步骤1：调用 buildHttpRequest 工具，传入 method、url、body 等参数；
               步骤2：工具会返回 JSON 格式的请求元数据；
               步骤3：在你的回复中先用自然语言清晰描述将要执行的操作（做什么、影响哪些数据、预期结果），然后**必须**输出 http-request 代码块，原样包含工具返回的 JSON。

               **【关键格式要求 - 必须严格遵守】**：
               - http-request（JSON）代码块的格式必须是：
                 ```http-request
                 {"method":"POST","url":"/api/xxx",...}
                 ```
               - 语言标识符 `http-request` 后面必须有一个**换行符**，JSON 必须在新的一行
               - 禁止将 JSON 紧跟在语言标识符后面（如 ```http-request{...} ``` 是错误的）

            8. 【用户认证状态 - 重要说明】
               - 用户已通过前端登录，前端会携带用户的 access token
               - 当你在回复中输出 http-request 代码块后，前端会使用用户的 token 执行请求
               - **禁止**说"需要认证"、"无法访问"、"需要用户登录"之类的话
               - **禁止**使用 httpRequest 工具直接执行（会返回 403 错误）
               - **必须**通过 buildHttpRequest 构建请求，让前端确认后执行

               **正确示例**（从下一行开始直到 `---` 为止，之间的内容都是需返回的内容的示例)：
               我现在帮你添加 iPhone15 到购物车：

               ```http-request
               {"method":"POST","url":"/api/products/cart","queryParams":{"productId":"1"}}
               ```
               ---

            9. 【错误示例 - 绝对禁止这样做】
               ❌ 错误做法：直接调用 httpRequest 工具添加购物车
                  结果：返回 403 错误，因为后端无法透传用户 token
               ❌ 错误做法：告诉用户"需要登录"、"无法访问"、"需要认证"
                  结果：违反规则，前端已携带用户 token
               ❌ 错误做法：不调用 buildHttpRequest 就直接输出 http-request 代码块
                  结果：必须先调用工具获取元数据

               ✅ 正确做法：先调用 buildHttpRequest 工具 → 获取 JSON → 输出 http-request 代码块
            """;

    /** P3: Vision Prompt with hint */
    private static final String DEFAULT_VISION_PROMPT_WITH_HINT = "用户问题是：{{USER_QUERY}}\n请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

    /** P4: Vision Prompt without hint */
    private static final String DEFAULT_VISION_PROMPT = "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

    /** P5: Enterprise Agent System Prompt */
    private static final String DEFAULT_ENTERPRISE_AGENT_SYSTEM_PROMPT = """
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
            """;

    /** P6: API Explanation Prompt */
    private static final String DEFAULT_API_EXPLANATION_PROMPT = """
            用户刚刚执行了一个 API 操作，请用简洁友好的中文解释发生了什么。

            ## 操作信息
            - **端点**: {{METHOD}} {{URL}}

            {{QUERY_PARAMS}}

            ## 响应状态
            HTTP {{STATUS_CODE}} ({{STATUS_TEXT}})

            {{ERROR_NOTE}}

            ## 响应数据
            ```json
            {{RESPONSE_BODY}}
            ```

            {{API_DESCRIPTION}}

            {{SKILL_HINT}}

            ---

            **输出要求:**
            1. 使用 Markdown 格式
            2. 用 ✅ 或 ❌ 开头表示成功或失败
            3. 简洁说明执行了什么操作
            4. 提取并展示关键数据
            5. 控制在 2-3 句话以内(除非有列表数据)
            """;

    // =========================================================================
    // Instance Fields
    // =========================================================================

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();
    private final Map<String, String> defaultPrompts = new HashMap<>();

    // =========================================================================
    // Constructor
    // =========================================================================

    public PromptLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        initializeDefaultPrompts();
    }

    // =========================================================================
    // Public Methods
    // =========================================================================

    /**
     * Load prompt template from resources, with fallback to default
     *
     * @param resourcePath Path to resource (e.g., "prompts/skills-advisor/system-prompt.template")
     * @param placeholders Map of placeholders to replace
     * @return The loaded template with placeholders replaced
     */
    public String getPrompt(String resourcePath, Map<String, String> placeholders) {
        String cached = templateCache.get(resourcePath);
        String template;

        if (cached != null) {
            template = cached;
        } else {
            template = loadTemplate(resourcePath);
            if (template == null) {
                // Fallback to default
                template = defaultPrompts.get(resourcePath);
                if (template == null) {
                    log.warn("[PromptLoader] No default found for: {}", resourcePath);
                    template = "";
                } else {
                    log.info("[PromptLoader] Using default for: {}", resourcePath);
                }
            } else {
                log.info("[PromptLoader] Loaded from resource: {}", resourcePath);
            }
            templateCache.put(resourcePath, template);
        }

        return replacePlaceholders(template, placeholders);
    }

    /**
     * Load static prompt (no placeholders)
     *
     * @param resourcePath Path to resource
     * @return The loaded template
     */
    public String getPrompt(String resourcePath) {
        return getPrompt(resourcePath, Collections.emptyMap());
    }

    /**
     * Load a label from properties (for input enrichment labels)
     *
     * @param key Properties key (e.g., "label.image.content")
     * @param defaultValue Default value if not found
     * @return The loaded label
     */
    public String getLabel(String key, String defaultValue) {
        String value = defaultPrompts.get(key);
        if (value != null) {
            return value;
        }
        // For labels, also check if there's a cached version
        String cached = templateCache.get(key);
        if (cached != null) {
            return cached;
        }
        log.debug("[PromptLoader] Label not found, using default: {}", key);
        return defaultValue;
    }

    /**
     * Clear the template cache (useful for testing)
     */
    public void clearCache() {
        templateCache.clear();
        log.info("[PromptLoader] Template cache cleared");
    }

    // =========================================================================
    // Private Methods
    // =========================================================================

    /**
     * Initialize hardcoded default prompts
     * These are used when resource files are not found
     */
    private void initializeDefaultPrompts() {
        defaultPrompts.put("prompts/skills-advisor/system-prompt.template", DEFAULT_SKILLS_ADVISOR_SYSTEM_PROMPT);
        defaultPrompts.put("prompts/skills-advisor/mode-rules.template", DEFAULT_SKILLS_ADVISOR_MODE_RULES);
        defaultPrompts.put("prompts/multimodal/vision-prompt-with-hint.template", DEFAULT_VISION_PROMPT_WITH_HINT);
        defaultPrompts.put("prompts/multimodal/vision-prompt.template", DEFAULT_VISION_PROMPT);
        defaultPrompts.put("prompts/enterprise-agent/system-prompt.template", DEFAULT_ENTERPRISE_AGENT_SYSTEM_PROMPT);
        defaultPrompts.put("prompts/explain-result/api-explanation-prompt.template", DEFAULT_API_EXPLANATION_PROMPT);

        log.info("[PromptLoader] Initialized {} default prompts", defaultPrompts.size());
    }

    /**
     * Load template from classpath resources
     */
    private String loadTemplate(String resourcePath) {
        try {
            Resource resource = resourceLoader.getResource("classpath:" + resourcePath);
            if (resource.exists() && resource.isReadable()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            log.debug("[PromptLoader] Failed to load resource {}: {}", resourcePath, e.getMessage());
        }
        return null;
    }

    /**
     * Replace all placeholders in the template
     */
    private String replacePlaceholders(String template, Map<String, String> placeholders) {
        if (template == null || placeholders == null || placeholders.isEmpty()) {
            return template;
        }

        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
