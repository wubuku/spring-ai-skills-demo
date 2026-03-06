package com.example.demo.agent;

import com.example.demo.model.Skill;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class SkillTools {

    private final SkillRegistry registry;
    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final boolean confirmBeforeMutate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> loadedSkills = new CopyOnWriteArrayList<>();

    public SkillTools(SkillRegistry registry, RestTemplate restTemplate,
                      @Value("${app.api.base-url}") String apiBaseUrl,
                      @Value("${app.confirm-before-mutate:false}") boolean confirmBeforeMutate) {
        this.registry = registry;
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl;
        this.confirmBeforeMutate = confirmBeforeMutate;
    }

    public boolean isConfirmBeforeMutate() { return confirmBeforeMutate; }

    public void reset() { loadedSkills.clear(); }
    public List<String> getLoadedSkills() { return loadedSkills; }

    @Tool(description = "加载指定技能的完整操作指令。在使用任何技能前必须先调用此工具。")
    public String loadSkill(
        @ToolParam(description = "技能名称，必须来自 available_skills 列表") String skillName
    ) {
        return registry.get(skillName)
            .map(skill -> {
                loadedSkills.add(skillName);
                String linksHint = skill.getMeta().getLinks() == null || 
                    skill.getMeta().getLinks().isEmpty() ? "" :
                    "\n\n**相关技能（按需加载）：**\n" + 
                    skill.getMeta().getLinks().stream()
                        .map(l -> "- `" + l.getName() + "`：" + l.getDescription())
                        .collect(Collectors.joining("\n"));
                return "✓ 技能 `" + skillName + "` 已加载" + linksHint + 
                       "\n\n---\n" + skill.getBody();
            })
            .orElse("✗ 错误：技能 `" + skillName + "` 不存在");
    }

    @Tool(description = "发送 HTTP 请求调用 REST API")
    public String httpRequest(
        @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
        @ToolParam(description = "API 路径，例如 /api/products（相对路径会自动拼接 base URL）") String url,
        @ToolParam(description = "Query 参数（JSON 对象）") Map<String, String> params,
        @ToolParam(description = "请求体（JSON 对象）") Map<String, Object> body
    ) {
        try {
            // 确认模式：非 GET 请求不在后端执行，返回元数据让前端确认
            if (confirmBeforeMutate && !"GET".equalsIgnoreCase(method)) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("method", method.toUpperCase());
                meta.put("url", url);
                if (params != null && !params.isEmpty()) meta.put("params", params);
                if (body != null && !body.isEmpty()) meta.put("body", body);
                String json = objectMapper.writeValueAsString(meta);
                return "[CONFIRM_REQUIRED]\n此操作需要用户确认后才能执行。" +
                       "请用自然语言向用户说明将要执行什么操作及其影响，" +
                       "然后在消息末尾原样附上以下代码块：\n\n" +
                       "```http-request\n" + json + "\n```";
            }

            String fullUrl = url.startsWith("http") ? url : apiBaseUrl + url;
            var uriBuilder = UriComponentsBuilder.fromHttpUrl(fullUrl);
            if (params != null) params.forEach(uriBuilder::queryParam);

            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var entity = new HttpEntity<>(body, headers);
            
            var response = restTemplate.exchange(
                uriBuilder.toUriString(),
                HttpMethod.valueOf(method.toUpperCase()),
                entity,
                String.class
            );
            
            String responseBody = response.getBody();
            return responseBody != null && responseBody.length() > 3000
                ? responseBody.substring(0, 3000) + "\n...[响应过长已截断]"
                : responseBody;
        } catch (Exception e) {
            return "HTTP 请求失败：" + e.getMessage();
        }
    }
}
