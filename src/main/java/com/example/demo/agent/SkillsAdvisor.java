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
    private final boolean confirmBeforeMutate;

    public SkillsAdvisor(SkillRegistry registry, SkillTools skillTools,
                         @Value("${app.api.base-url}") String apiBaseUrl,
                         @Value("${app.confirm-before-mutate:false}") boolean confirmBeforeMutate) {
        this.registry = registry;
        this.skillTools = skillTools;
        this.apiBaseUrl = apiBaseUrl;
        this.confirmBeforeMutate = confirmBeforeMutate;
    }

    @Override
    public String getName() { return "SkillsAdvisor"; }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String systemPrompt = buildSystemPrompt();
        log.info("[SkillsAdvisor] 注入系统提示，模式={}, HTTP工具={}, 技能数量={}",
                confirmBeforeMutate ? "确认模式" : "直接执行模式",
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
     * 根据模式返回不同的规则指令
     */
    private String buildModeSpecificRules() {
        String httpToolName = getHttpToolName();

        if (confirmBeforeMutate) {
            // 确认模式规则
            return """
                6. 【用户确认模式】已启用！但是你**不应该**直接生成 `http-request` 代码块！你应该先调用 buildHttpRequest 工具，当返回结果中包含 `[CONFIRM_REQUIRED]` 时，
                   才可以最终确认*该操作需要用户手动确认后执行*。此时，你必须：
                   a) 先用自然语言清晰描述将要执行的操作（做什么、影响哪些数据、预期结果）
                   b) 在消息末尾原样保留工具返回的 JSON 代码块（不要修改其中的内容）
                   c) 绝不要省略代码块，也不要尝试自行执行该操作
                   d) 不要在代码块外重复展示请求参数的技术细节

                **【关键格式要求】**：
                - http-request（JSON）代码块的格式必须是：
                  ```http-request
                  {"method":"POST","url":"/api/xxx",...}
                  ```
                - 语言标识符 `http-request` 后面必须有一个**换行符**，JSON 必须在新的一行
                - 禁止将 JSON 紧跟在语言标识符后面（如 ```http-request{...} ``` 是错误的）
                - 禁止在语言标识符后添加任何字符或空格后直接跟 JSON

                **【重要】GET 请求说明**：
                - GET 查询操作是安全的，会直接执行并返回结果
                - buildHttpRequest 工具对于 GET 请求会直接执行，不需要用户确认
                """;
        } else {
            // 直接执行模式规则（强制工具调用）
            return """
                6. 【直接执行模式】已启用！你必须通过调用 `httpRequest` 工具来执行 API 请求！
                   - 禁止生成 `http-request` 或 `http` 代码块！
                   - 所有 API 调用必须通过工具调用完成，工具会自动处理认证！
                """;
        }
    }

    /**
     * 根据模式返回不同的 HTTP 工具名称
     */
    private String getHttpToolName() {
        return confirmBeforeMutate ? "buildHttpRequest" : "httpRequest";
    }
}
