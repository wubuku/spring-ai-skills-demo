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

    private final SkillRegistry registry;
    private final SkillTools skillTools;
    private final PromptLoader promptLoader;
    private final String apiBaseUrl;

    /**
     * confirm-before-mutate 配置已移除
     * 原因：AG-UI + SSE + Spring AI 场景不支持用户态 Token 透传
     * 后端不再试图"代表用户调用API"，任何需要用户 access token 的操作都推到前端
     */
    public SkillsAdvisor(SkillRegistry registry, SkillTools skillTools, PromptLoader promptLoader,
                         @Value("${app.api.base-url}") String apiBaseUrl) {
        this.registry = registry;
        this.skillTools = skillTools;
        this.promptLoader = promptLoader;
        this.apiBaseUrl = apiBaseUrl;
    }

    @Override
    public String getName() { return "SkillsAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String systemPrompt = buildSystemPrompt();
        log.info("[SkillsAdvisor] 注入系统提示，HTTP工具={}, 技能数量={}",
                getHttpToolName(),
                registry.all().size());

        // 打印完整系统提示词（用于调试）
        log.info("========== [完整系统提示词] ==========");
        log.info(systemPrompt);
        log.info("==========================================");

        return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(systemPrompt))
            .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    private String buildSystemPrompt() {
        String skillList = registry.all().values().stream()
            .map(s -> "- `" + s.getMeta().getName() + "`：" + s.getMeta().getDescription())
            .collect(Collectors.joining("\n"));

        log.info("[SkillsAdvisor] 所有技能列表: {}", registry.all().keySet());

        String loadedContext = skillTools.getLoadedSkills().stream()
            .map(name -> registry.get(name)
                .map(s -> "\n\n## 已激活技能：" + name + "\n" + s.getBody())
                .orElse(""))
            .collect(Collectors.joining());

        String httpToolName = getHttpToolName();
        String modeRules = promptLoader.getPrompt("prompts/skills-advisor/mode-rules.template");

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{{SKILL_LIST}}", skillList);
        placeholders.put("{{API_BASE_URL}}", apiBaseUrl);
        placeholders.put("{{HTTP_TOOL_NAME}}", httpToolName);
        placeholders.put("{{LOADED_CONTEXT}}", loadedContext);
        placeholders.put("{{MODE_RULES}}", modeRules);

        return promptLoader.getPrompt("prompts/skills-advisor/system-prompt.template", placeholders);
    }

    /**
     * 返回 HTTP 工具名称（默认使用 buildHttpRequest，因为不确定时使用这个更安全）
     */
    private String getHttpToolName() {
        return "buildHttpRequest";
    }
}
