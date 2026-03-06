package com.example.demo.agent;

import com.example.demo.model.Skill;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
public class SkillsAdvisor implements CallAroundAdvisor {

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
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedRequest augmented = AdvisedRequest.from(request)
            .systemText(buildSystemPrompt())
            .build();
        return chain.nextAroundCall(augmented);
    }

    private String buildSystemPrompt() {
        String skillList = registry.all().values().stream()
            .map(s -> "- `" + s.getMeta().getName() + "`：" + s.getMeta().getDescription())
            .collect(Collectors.joining("\n"));

        String loadedContext = skillTools.getLoadedSkills().stream()
            .map(name -> registry.get(name)
                .map(s -> "\n\n## 已激活技能：" + name + "\n" + s.getBody())
                .orElse(""))
            .collect(Collectors.joining());

        return """
            你是一个智能购物助手。可用技能如下：

            <available_skills>
            %s
            </available_skills>

            **重要规则：**
            1. 使用某个技能前，必须先调用 `loadSkill` 工具加载它的完整指令
            2. 不要凭记忆猜测 API 参数，必须先加载技能查看文档
            3. 加载技能后，注意其 links 字段提示的关联技能
            4. API 基础 URL 是 %s（技能文档中的路径都是相对路径，调用 httpRequest 时只需传相对路径）
            5. 默认用户 ID 是 1
            %s
            %s
            """.formatted(skillList, apiBaseUrl, loadedContext, buildConfirmRule());
    }
    private String buildConfirmRule() {
        if (!confirmBeforeMutate) return "";
        return """
            6. 【用户确认模式】当 httpRequest 工具返回包含 [CONFIRM_REQUIRED] 的结果时，
               表示该操作需要用户手动确认后才能执行。你必须：
               a) 先用自然语言清晰描述将要执行的操作（做什么、影响哪些数据、预期结果）
               b) 在消息末尾原样保留工具返回的 ```http-request 代码块（不要修改其中的 JSON 内容）
               c) 绝不要省略代码块，也不要尝试自行执行该操作
               d) 不要在代码块外重复展示请求参数的技术细节
            """;
    }
}
