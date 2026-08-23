package com.example.demo.agent;

import com.example.demo.service.PromptLoader;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SkillsAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SkillsAdvisor.class);
    public static final String EXECUTION_MODE = "skills.execution-mode";
    public static final String MODE_BACKEND = "backend";
    public static final String MODE_FRONTEND = "frontend";

    private final SkillRegistry registry;
    private final SkillCoreTools skillCoreTools;
    private final PromptLoader promptLoader;
    private final String apiBaseUrl;
    private final boolean logSystemPrompt;

    /**
     * confirm-before-mutate 配置已移除
     * 原因：AG-UI + SSE + Spring AI 场景不支持用户态 Token 透传
     * 后端不再试图"代表用户调用API"，任何需要用户 access token 的操作都推到前端
     *
     * 注意：使用 SkillCoreTools 而非 SkillTools，保证已加载技能列表的可见性，
     * 同时不依赖 SkillTools 的 HTTP 工具（避免与前端 httpRequest 工具冲突）。
     */
    public SkillsAdvisor(SkillRegistry registry, SkillCoreTools skillCoreTools, PromptLoader promptLoader,
                         @Value("${app.api.base-url}") String apiBaseUrl,
                         @Value("${app.ai.log-system-prompt:false}") boolean logSystemPrompt) {
        this.registry = registry;
        this.skillCoreTools = skillCoreTools;
        this.promptLoader = promptLoader;
        this.apiBaseUrl = apiBaseUrl;
        this.logSystemPrompt = logSystemPrompt;
    }

    @Override
    public String getName() { return "SkillsAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String mode = executionMode(request);
        String systemPrompt = buildSystemPrompt(mode);
        log.debug("[SkillsAdvisor] 注入系统提示，mode={}, HTTP工具={}, 技能数量={}",
                mode,
                getHttpToolName(mode),
                registry.all().size());

        if (logSystemPrompt && log.isDebugEnabled()) {
            log.debug("[SkillsAdvisor] 完整系统提示词:\n{}", systemPrompt);
        }

        return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(systemPrompt))
            .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private String buildSystemPrompt(String mode) {
        String skillList = registry.all().values().stream()
            .map(s -> "- `" + s.getMeta().getName() + "`：" + s.getMeta().getDescription())
            .collect(Collectors.joining("\n"));

        log.debug("[SkillsAdvisor] 所有技能列表: {}", registry.all().keySet());

        String loadedContext = MODE_FRONTEND.equals(mode)
            ? buildFrontendLoadedContext()
            : "";

        String httpToolName = getHttpToolName(mode);
        String rulesPath = MODE_BACKEND.equals(mode)
            ? "prompts/skills-advisor/backend-mode-rules.template"
            : "prompts/skills-advisor/mode-rules.template";
        String modeRules = promptLoader.getPrompt(rulesPath);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{{SKILL_LIST}}", skillList);
        placeholders.put("{{API_BASE_URL}}", apiBaseUrl);
        placeholders.put("{{HTTP_TOOL_NAME}}", httpToolName);
        placeholders.put("{{LOADED_CONTEXT}}", loadedContext);
        placeholders.put("{{MODE_RULES}}", modeRules);

        return promptLoader.getPrompt("prompts/skills-advisor/system-prompt.template", placeholders);
    }

    private String buildFrontendLoadedContext() {
        var loadedSkills = skillCoreTools.getLoadedSkills();
        if (loadedSkills.isEmpty()) {
            return "";
        }
        return "\n\n## 本轮已加载技能（禁止重复调用）\n"
            + "以下技能已经在本轮加载。请直接使用对应文档，不要再次调用 `loadSkill`；"
            + "如果需要执行 API，再调用 `httpRequest`。\n"
            + loadedSkills.stream()
                .map(name -> registry.get(name)
                    .map(s -> "\n### 已激活技能：" + name + "\n" + s.getBody())
                    .orElse(""))
                .collect(Collectors.joining());
    }

    private String executionMode(ChatClientRequest request) {
        Object configuredMode = request.context().get(EXECUTION_MODE);
        return MODE_BACKEND.equals(configuredMode) ? MODE_BACKEND : MODE_FRONTEND;
    }

    private String getHttpToolName(String mode) {
        return "httpRequest";
    }
}
