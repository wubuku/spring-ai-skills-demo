package com.example.demo.agent;

import com.example.demo.auth.UserContextHolder;
import com.example.demo.model.Skill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
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

@Slf4j
@Component
public class SkillTools {

    private final SkillRegistry registry;
    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> loadedSkills = new CopyOnWriteArrayList<>();

    /**
     * confirm-before-mutate 配置已移除
     * 原因：AG-UI + SSE + Spring AI 场景不支持用户态 Token 透传
     * 后端不再试图"代表用户调用API"，任何需要用户 access token 的操作都推到前端
     */
    public SkillTools(SkillRegistry registry, RestTemplate restTemplate,
                      @Value("${app.api.base-url}") String apiBaseUrl) {
        this.registry = registry;
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl;
    }

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
     * 工具1（模式1）：直接发送 HTTP 请求并返回结果
     * 用于 confirmBeforeMutate = false 时
     *
     * 支持认证透传机制：通过 SecurityContextHolder 获取 JWT
     */
    @Tool(description = "发送 HTTP 请求调用 REST API，并直接返回执行结果。支持 GET/POST/PUT/DELETE 所有方法。")
    public String httpRequest(
        @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
        @ToolParam(description = "API 路径（相对路径会自动拼接 base URL）") String url,
        @ToolParam(description = "路径参数，用于替换 URL 中的占位符") Map<String, String> pathParams,
        @ToolParam(description = "查询参数") Map<String, String> queryParams,
        @ToolParam(description = "请求头") Map<String, String> headers,
        @ToolParam(description = "请求体（仅用于 POST/PUT）") Map<String, Object> body
    ) {
        return executeHttpRequest(method, url, pathParams, queryParams, headers, body);
    }

    /**
     * 工具2（模式2）：HTTP 请求工具（确认模式）
     * 用于 confirmBeforeMutate = true 时
     *
     * 此工具根据请求类型决定：
     * - GET 请求：直接执行并返回结果
     * - POST/PUT/DELETE：返回请求元数据，供前端显示确认按钮
     */
    @Tool(description = "发送 HTTP 请求。GET 查询会直接执行并返回结果；POST/PUT/DELETE 等修改操作会返回元数据供用户确认。")
    public String buildHttpRequest(
        @ToolParam(description = "HTTP 方法：GET/POST/PUT/DELETE") String method,
        @ToolParam(description = "API 路径（相对路径）") String url,
        @ToolParam(description = "路径参数") Map<String, String> pathParams,
        @ToolParam(description = "查询参数") Map<String, String> queryParams,
        @ToolParam(description = "请求体（JSON 对象，仅用于 POST/PUT）") Map<String, Object> body
    ) {
        log.info("[buildHttpRequest] 被调用! method={}, url={}", method, url);

        // 替换路径参数
        String resolvedUrl = url;
        if (pathParams != null && !pathParams.isEmpty()) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                resolvedUrl = resolvedUrl.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        // buildHttpRequest 的语义：只构建请求，不直接执行
        // 返回元数据供前端确认，前端拿到用户 token 后在自己的浏览器中执行
        log.info("[buildHttpRequest] {} 请求，返回确认元数据", method);
        String confirmUrl = resolvedUrl.startsWith("http") ? resolvedUrl : apiBaseUrl + resolvedUrl;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("method", method.toUpperCase());
        meta.put("url", confirmUrl);
        if (pathParams != null && !pathParams.isEmpty()) meta.put("pathParams", pathParams);
        if (queryParams != null && !queryParams.isEmpty()) meta.put("queryParams", queryParams);
        if (body != null && !body.isEmpty()) meta.put("body", body);

        try {
            String json = objectMapper.writeValueAsString(meta);
            // 不要输出 [CONFIRM_REQUIRED] 前缀，让模型直接输出 http-request 代码块
            // 前端会根据代码块自动显示确认对话框，不需要模型多此一举
            return json;
        } catch (Exception e) {
            return "构建请求失败：" + e.getMessage();
        }
    }

    /**
     * 实际执行 HTTP 请求的内部方法
     * 从 SecurityContextHolder 获取 JWT（通过 AuthFilter 设置）
     */
    private String executeHttpRequest(String method, String url, Map<String, String> pathParams,
                                     Map<String, String> queryParams, Map<String, String> headers,
                                     Map<String, Object> body) {
        try {
            // 从 SecurityContextHolder 获取 JWT（AuthFilter 已设置）
            String jwt = extractJwt();

            // Step 1: 替换路径参数
            String resolvedUrl = url;
            if (pathParams != null && !pathParams.isEmpty()) {
                for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                    resolvedUrl = resolvedUrl.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }

            // Step 2: 构建完整 URL
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

            // 认证透传：自动添加用户认证头
            if (jwt != null && !httpHeaders.containsKey(HttpHeaders.AUTHORIZATION)) {
                httpHeaders.setBearerAuth(jwt);
                log.debug("自动注入用户认证头到请求");
            }

            // [FINAL] 最终确认：HTTP 请求头中是否有 token
            log.info("[FINAL] executeHttpRequest thread={}, authHeader={}",
                    Thread.currentThread().getName(),
                    httpHeaders.getFirst(HttpHeaders.AUTHORIZATION) != null ? "present" : "null");

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

    /**
     * 提取 JWT Token
     * 优先从 SecurityContextHolder 获取，如果为空则从 UserContextHolder 获取
     * （boundedElastic 线程中 SecurityContext 已被清理，需要从 UserContextHolder 获取）
     */
    private String extractJwt() {
        // [STEP4] 记录当前线程信息
        log.info("[STEP4] extractJwt thread={}", Thread.currentThread().getName());

        // 首先尝试从 SecurityContextHolder 获取（主线程中使用）
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            log.info("[STEP4] SecurityContext authClass={}, authenticated={}",
                auth != null ? auth.getClass().getSimpleName() : "null",
                auth != null && auth.isAuthenticated());
            if (auth != null && auth.isAuthenticated()) {
                Object credentials = auth.getCredentials();
                log.info("[STEP4] credentials 类型: {}", credentials != null ? credentials.getClass().getSimpleName() : "null");
                if (credentials instanceof String) {
                    log.info("[STEP4] 从 SecurityContext 提取到 token");
                    return (String) credentials;
                }
            }
        } catch (Exception e) {
            log.info("[STEP4] 从 SecurityContext 提取 JWT 失败: {}", e.getMessage());
        }

        // 从 UserContextHolder 获取（boundedElastic 线程中使用）
        // 由于不清理 UserContextHolder，boundedElastic 线程复用时仍能获取到 token
        String token = UserContextHolder.getToken();
        log.info("[STEP4] UserContextHolder token: {}", token != null ? "present" : "null");
        if (token != null) {
            log.info("[STEP4] 从 UserContextHolder 获取到 JWT token");
            return token;
        }

        log.warn("[STEP4] 所有方式都无法获取 JWT token！");
        return null;
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
