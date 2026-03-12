package com.example.demo.service;

import com.example.demo.agent.SkillRegistry;
import com.example.demo.agent.SkillRegistry.ApiIndexEntry;
import com.example.demo.model.ExplainRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * API 结果解释服务
 */
@Service
public class ExplainResultService {

    private static final Logger log = LoggerFactory.getLogger(ExplainResultService.class);

    private final ChatClient chatClient;
    private final SkillRegistry skillRegistry;

    public ExplainResultService(ChatClient.Builder builder, SkillRegistry skillRegistry) {
        // 创建带有 Skills 工具的 ChatClient,让 AI 可以自己探索 Skills
        this.chatClient = builder.build();
        this.skillRegistry = skillRegistry;
    }

    /**
     * 解释 API 结果
     * 策略:
     * 1. 优先: 直接从 Skills 匹配查找 API 描述(快速、准确)
     * 2. 兜底: 如果找不到,让 AI 自己探索 Skills
     */
    public String explainResult(ExplainRequest request) {
        // 1. 尝试直接匹配 Skills
        String apiDescription = tryFindApiDescription(request);

        // 2. 构建 Prompt
        String prompt = buildPrompt(request, apiDescription);

        // 3. 调用 LLM (带 Skills 工具)
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

            // 尝试模式匹配(支持路径参数)
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
            log.warn("直接匹配失败,将让 AI 自己探索: {}", e.getMessage());
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
            // 没找到,让 AI 自己探索
            prompt.append("\n**提示**: 请使用 `loadSkill` 工具查找相关的 Skill 文档。\n");
            prompt.append("可用的技能包括: product-store, swagger-petstore-openapi-3-0 等。\n");
        }

        prompt.append("\n---\n\n**输出要求:**\n");
        prompt.append("1. 使用 Markdown 格式\n");
        prompt.append("2. 用 ✅ 或 ❌ 开头表示成功或失败\n");
        prompt.append("3. 简洁说明执行了什么操作\n");
        prompt.append("4. 提取并展示关键数据\n");
        prompt.append("5. 控制在 2-3 句话以内(除非有列表数据)\n");

        return prompt.toString();
    }
}
