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
            6. 【HTTP 请求工具使用规则】
               - 唯一可用的 HTTP 工具：`httpRequest`（前端执行，自动携带用户 token）
               - GET 请求自动执行；POST/PUT/DELETE/PATCH 弹确认对话框
               - 参数都是字符串：`params`、`body` 都是 JSON 字符串
               - 禁止输出 `http-request` 代码块或说"需要登录"

            7. 【技能探索流程】
               - 查询、浏览、搜索、查看、添加、删除或修改数据时，必须使用工具获取真实结果
               - 先调用 `loadSkill`，再从返回的 SKILL.md 获取 method、url 和参数
               - 分层 Skill 还必须调用 `readSkillReference` 读取具体操作文档
               - URL 必须来自 Skill 文档，禁止猜测
               - 每轮最多调用一次 `httpRequest`；拿到结果后直接回答

            8. 【httpRequest 参数格式】
               - `method` 是 HTTP 方法字符串
               - `url` 必须是 Skill API index 中的相对路径
               - `params` 和 `body` 必须是合法 JSON 字符串，不能传对象

            9. 【错误和输出规则】
               - URL 校验失败时重新调用 `loadSkill` 获取正确路径
               - 调用工具前不要输出文本；拿到结果后用中文组织回答
               - 严禁输出推理文本、`[TOOL_CALL]` 或 XML 工具调用草稿

            10. `loadSkill` 的 `skillName` 必须从 `<available_skills>` 列表逐字复制
            """;

    private static final String DEFAULT_SKILLS_ADVISOR_BACKEND_MODE_RULES = """
            6. 【后端 HTTP 工具规则】
               - 只读 GET：调用 `httpRequest`
               - 写操作 POST/PUT/PATCH/DELETE：必须调用 `buildHttpRequest`
               - 写操作必须调用 `buildHttpRequest`，禁止通过 `httpRequest` 直接执行
               - `pathParams`、`queryParams`、`headers`、`body` 都传 JSON 对象

            7. 【技能探索流程】
               - 先调用 `loadSkill`，再从返回文档获取 method、url 和参数
               - 分层 Skill 继续调用 `readSkillReference`
               - URL 必须来自 Skill 文档，禁止猜测

            8. 【只读请求格式】
               - `httpRequest(method, url, pathParams, queryParams, headers, body)`
               - `method` 必须是 `GET`
               - 没有参数时传空对象

            9. 【写操作确认】
               - `buildHttpRequest(method, url, pathParams, queryParams, body)` 会校验并记录待确认请求
               - 工具返回后立即停止，不要再次调用业务工具
               - 不要自行输出 `http-request` 代码块；确认协议由应用代码生成
               - 未收到实际 API 结果前，禁止声称操作成功

            10. 调用工具前不输出说明；拿到结果后用中文回答。每次回复最多执行一次业务 API。
            """;

    /** P3: Vision Prompt with hint */
    private static final String DEFAULT_VISION_PROMPT_WITH_HINT = "用户问题是：{{USER_QUERY}}\n请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

    /** P4: Vision Prompt without hint */
    private static final String DEFAULT_VISION_PROMPT = "请详细描述这张图片的内容，包括文字、数据、图表、场景等所有重要信息。";

    /** P5: Vision Prompt Generator for Contextual Enhancement */
    private static final String DEFAULT_VISION_PROMPT_GENERATOR = """
            你是一个图像理解助手。你的任务是根据当前对话上下文，为识别用户上传的图片生成一个**情境化的视图提示词**。

            ## 当前对话上下文（最近的消息）
            {{CONVERSATION_HISTORY}}

            ## 用户当前附带的文本
            {{USER_COMMENT}}

            ## 默认视图提示词（供参考）
            {{DEFAULT_VISION_PROMPT}}

            ## 任务
            请根据以上上下文，生成一个适合当前情境的视图提示词。这个提示词应该：
            1. 承接之前的对话主题（如果有）
            2. 呼应用户当前的问题或需求
            3. 引导视觉模型关注与当前任务相关的图片细节
            4. 保持简洁，通常 1-3 句话即可

            ## 输出要求
            - 直接输出生成的提示词，不要添加解释
            - 如果用户没有附带文本或没有历史，可以返回默认提示词的微调版本
            """;

    /** P6: Enterprise Agent System Prompt */
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
        defaultPrompts.put("prompts/skills-advisor/backend-mode-rules.template", DEFAULT_SKILLS_ADVISOR_BACKEND_MODE_RULES);
        defaultPrompts.put("prompts/multimodal/vision-prompt-with-hint.template", DEFAULT_VISION_PROMPT_WITH_HINT);
        defaultPrompts.put("prompts/multimodal/vision-prompt.template", DEFAULT_VISION_PROMPT);
        defaultPrompts.put("prompts/multimodal/vision-prompt-generator.template", DEFAULT_VISION_PROMPT_GENERATOR);
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
