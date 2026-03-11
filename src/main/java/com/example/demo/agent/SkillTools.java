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
import org.springframework.core.io.ClassPathResource;
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

    /**
     * 发送 HTTP 请求调用 REST API
     *
     * 支持的参数位置（对应 OpenAPI 的 "in" 字段）：
     * - path: 路径参数，用于替换 URL 中的占位符，如 /pet/{petId}
     * - query: 查询参数，拼接到 URL 的 ? 之后
     * - header: 请求头，用于认证等
     * - body: 请求体，用于 POST/PUT 等方法
     */
    @Tool(description = "发送 HTTP 请求调用 REST API。支持路径参数、查询参数、请求头和请求体。")
    public String httpRequest(
        @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
        @ToolParam(description = "API 路径，可包含占位符如 /pet/{petId}（相对路径会自动拼接 base URL）") String url,
        @ToolParam(description = "路径参数，用于替换 URL 中的占位符，如 {\"petId\": \"123\"}") Map<String, String> pathParams,
        @ToolParam(description = "查询参数（URL 中 ? 之后的部分）") Map<String, String> queryParams,
        @ToolParam(description = "请求头（如认证信息）") Map<String, String> headers,
        @ToolParam(description = "请求体（JSON 对象，用于 POST/PUT）") Map<String, Object> body
    ) {
        try {
            // Step 1: 替换路径参数（如 /pet/{petId} -> /pet/123）
            String resolvedUrl = url;
            if (pathParams != null && !pathParams.isEmpty()) {
                for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                    resolvedUrl = resolvedUrl.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }

            // 确认模式：非 GET 请求不在后端执行，返回元数据让前端确认
            if (confirmBeforeMutate && !"GET".equalsIgnoreCase(method)) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("method", method.toUpperCase());
                meta.put("url", resolvedUrl);
                if (pathParams != null && !pathParams.isEmpty()) meta.put("pathParams", pathParams);
                if (queryParams != null && !queryParams.isEmpty()) meta.put("queryParams", queryParams);
                if (headers != null && !headers.isEmpty()) meta.put("headers", headers);
                if (body != null && !body.isEmpty()) meta.put("body", body);
                String json = objectMapper.writeValueAsString(meta);
                return "[CONFIRM_REQUIRED]\n此操作需要用户确认后才能执行。" +
                       "请用自然语言向用户说明将要执行什么操作及其影响，" +
                       "然后在消息末尾原样附上以下代码块：\n\n" +
                       "```http-request\n" + json + "\n```";
            }

            // Step 2: 构建完整 URL（拼接 base URL + 查询参数）
            String fullUrl = resolvedUrl.startsWith("http") ? resolvedUrl : apiBaseUrl + resolvedUrl;
            var uriBuilder = UriComponentsBuilder.fromHttpUrl(fullUrl);
            if (queryParams != null && !queryParams.isEmpty()) {
                queryParams.forEach(uriBuilder::queryParam);
            }

            // Step 3: 构建请求头
            var httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            if (headers != null && !headers.isEmpty()) {
                headers.forEach(httpHeaders::set);
            }
            var entity = new HttpEntity<>(body, httpHeaders);

            // Step 4: 发送请求
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

    @Tool(description = "读取技能的参考文件（适用于具有分层结构的技能，如 OpenAPI 生成的技能）")
    public String readSkillReference(
        @ToolParam(description = "技能名称，例如 swagger-petstore-openapi-3-0") String skillName,
        @ToolParam(description = "相对于该技能 references 目录的路径，例如 resources/pet.md 或 operations/addPet.md") String relativePath
    ) {
        try {
            var resource = new ClassPathResource("skills/" + skillName + "/references/" + relativePath);
            String content = new String(resource.getInputStream().readAllBytes());
            return content.length() > 4000
                ? content.substring(0, 4000) + "\n...[文件过长已截断]"
                : content;
        } catch (Exception e) {
            return "✗ 读取参考文件失败：skills/" + skillName + "/references/" + relativePath + " — " + e.getMessage();
        }
    }
}
