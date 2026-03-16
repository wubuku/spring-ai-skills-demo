package com.example.demo.agent;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

@Component
public class SkillsAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SkillsAdvisor.class);

    private final SkillRegistry registry;
    private final SkillTools skillTools;
    private final String apiBaseUrl;

    /**
     * confirm-before-mutate 配置已移除
     * 原因：AG-UI + SSE + Spring AI 场景不支持用户态 Token 透传
     * 后端不再试图"代表用户调用API"，任何需要用户 access token 的操作都推到前端
     */
    public SkillsAdvisor(SkillRegistry registry, SkillTools skillTools,
                         @Value("${app.api.base-url}") String apiBaseUrl) {
        this.registry = registry;
        this.skillTools = skillTools;
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
        log.debug("[SkillsAdvisor] 系统提示前300字：{}", systemPrompt.substring(0, Math.min(300, systemPrompt.length())));
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
        String modeRules = buildModeSpecificRules();

        return """
            你是一个智能助手。可用技能如下：

            <available_skills>
            %s
            </available_skills>

            **重要规则：**
            1. 使用某个技能前，必须先调用 `loadSkill` 工具加载它的完整指令
            2. 不要凭记忆猜测 API 参数，必须先加载技能查看文档
            3. 加载技能后，注意其 links 字段提示的关联技能
            4. API 基础 URL 是 %s（技能文档中的路径都是相对路径，调用 %s 时只需传相对路径）
            5. 部分技能具有分层结构（如 OpenAPI 生成的技能），其 SKILL.md 中会列出 references 目录下的参考文件路径，
               需要调用 `readSkillReference` 工具读取具体的资源/操作文档，再据此调用 %s 工具
            %s
            """.formatted(skillList, apiBaseUrl, httpToolName, httpToolName, loadedContext, modeRules);
    }

    /**
     * HTTP 工具调用规则
     * 后端不再试图"代表用户调用API"，任何可能需要用户 access token 的操作都推到前端
     */
    private String buildModeSpecificRules() {
        return """
            6. 【HTTP 工具使用规则】：
               - **禁止**生成 `http-request` 或 `http` 代码块！
               - 所有 API 调用必须通过工具调用完成。

            7. 【如何选择 HTTP 工具】：
               - 如果 API 可能需要用户认证（用户登录态、用户个人数据等），或者你不确定是否需要认证，请使用 `buildHttpRequest` 工具
               - 不确定时**总是使用 buildHttpRequest 工具**（更安全）
               - 只有确定是**完全公开的 API**（如公开的天气 API、公开的产品列表等）才使用 `httpRequest` 工具

            8. 【buildHttpRequest 工具返回确认请求】
               - 调用 buildHttpRequest 后，如果返回结果中包含 `[CONFIRM_REQUIRED]`，说明该操作需要用户确认
               - 此时，你必须：
                 a) 先用自然语言清晰描述将要执行的操作（做什么、影响哪些数据）
                 b) 在消息末尾原样保留工具返回的 JSON 代码块（不要修改其中的内容）
                 c) 绝不要省略代码块，也不要尝试自行执行该操作

            **【关键格式要求】**：
            - http-request（JSON）代码块的格式必须是：
              ```http-request
              {"method":"POST","url":"/api/xxx",...}
              ```
            - 语言标识符 `http-request` 后面必须有一个**换行符**，JSON 必须在新的一行
            - 禁止将 JSON 紧跟在语言标识符后面（如 ```http-request{...} ``` 是错误的）
            - 禁止在语言标识符后添加任何字符或空格后直接跟 JSON

            **【重要】GET 请求说明**：
            - GET 查询操作通常用于获取数据，但不意味着不需要认证
            - 如果是获取用户个人信息、用户订单等，需要用户认证的 GET 请求仍应使用 buildHttpRequest
            """;
    }

    /**
     * 返回 HTTP 工具名称（默认使用 buildHttpRequest，因为不确定时使用这个更安全）
     */
    private String getHttpToolName() {
        return "buildHttpRequest";
    }
}
