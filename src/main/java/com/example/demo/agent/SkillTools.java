package com.example.demo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.demo.model.PendingHttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SkillTools {

    public static final String SKILL_SESSION_CONTEXT_KEY = "skills.load-session";
    public static final String AUTH_TOKEN_CONTEXT_KEY = "auth.token";
    public static final String AUTH_USERNAME_CONTEXT_KEY = "auth.username";

    private static final Set<String> ALLOWED_HEADERS = Set.of(
        "accept", "content-type", "x-request-id"
    );
    private static final Set<String> MUTATION_METHODS = Set.of(
        "POST", "PUT", "PATCH", "DELETE"
    );
    private static final int MAX_MAP_ENTRIES = 32;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_LENGTH = 2048;
    private static final int MAX_BODY_LENGTH = 32 * 1024;
    private static final int MAX_RESPONSE_LENGTH = 16 * 1024;

    private final SkillRegistry registry;
    private final RestTemplate restTemplate;
    private final String apiBaseUrl;
    private final SkillReferenceReader referenceReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SkillTools(SkillRegistry registry, RestTemplate restTemplate,
                      @Value("${app.api.base-url}") String apiBaseUrl,
                      SkillReferenceReader referenceReader) {
        this.registry = registry;
        this.restTemplate = restTemplate;
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.referenceReader = referenceReader;
    }

    @Tool(description = "加载指定技能的完整操作指令。在使用任何技能前必须先调用此工具。")
    public String loadSkill(
        @ToolParam(description = "技能名称，必须来自 available_skills 列表") String skillName,
        ToolContext toolContext
    ) {
        if (skillName == null || skillName.isBlank()) {
            return "✗ 错误：skillName 参数不能为空。";
        }
        SkillLoadSession session = skillSession(toolContext);
        if (session == null) {
            return "✗ 错误：缺少当前请求的 Skill 会话上下文。";
        }
        return registry.get(skillName)
            .map(skill -> {
                boolean firstLoad = session.markLoaded(skillName);
                String linksHint = skill.getMeta().getLinks() == null ||
                    skill.getMeta().getLinks().isEmpty() ? "" :
                    "\n\n**相关技能（按需加载）：**\n" +
                    skill.getMeta().getLinks().stream()
                        .map(l -> "- `" + l.getName() + "`：" + l.getDescription())
                        .collect(Collectors.joining("\n"));
                String loadStatus = !firstLoad
                    ? "⚠️ 技能 `" + skillName + "` 已在本轮加载。请不要再次调用 `loadSkill`，直接使用下方指令。"
                    : "✓ 技能 `" + skillName + "` 已加载";
                return loadStatus + linksHint +
                       "\n\n---\n" + skill.getBody();
            })
            .orElse("✗ 错误：技能 `" + skillName + "` 不存在");
    }

    @Tool(description = "调用已登记的只读 REST API。此工具只允许 GET；写操作必须使用 buildHttpRequest 生成前端确认元数据。")
    public String httpRequest(
        @ToolParam(description = "HTTP 方法，只允许 GET") String method,
        @ToolParam(description = "API 路径（相对路径会自动拼接 base URL）") String url,
        @ToolParam(description = "路径参数，用于替换 URL 中的占位符") Map<String, String> pathParams,
        @ToolParam(description = "查询参数") Map<String, String> queryParams,
        @ToolParam(description = "请求头") Map<String, String> headers,
        @ToolParam(description = "请求体；GET 时必须为空对象") Map<String, Object> body,
        ToolContext toolContext
    ) {
        String normalizedMethod = normalizeMethod(method);
        if (!"GET".equals(normalizedMethod)) {
            return "HTTP 请求被拒绝：后端工具只允许 GET；写操作请调用 buildHttpRequest。";
        }
        if (body != null && !body.isEmpty()) {
            return "HTTP 请求被拒绝：GET 请求体必须为空。";
        }
        String validationError = validateRequestParts(
            normalizedMethod, url, pathParams, queryParams, headers, null
        );
        if (validationError != null) {
            return "HTTP 请求被拒绝：" + validationError;
        }

        String resolvedUrl = resolvePathParams(url, pathParams);
        validationError = registry.validateResolvedApiRequest(normalizedMethod, resolvedUrl);
        if (validationError != null) {
            return "HTTP 请求被拒绝：" + validationError;
        }
        validationError = requireLoadedApiSkill(
            normalizedMethod, resolvedUrl, toolContext, "HTTP 请求被拒绝："
        );
        if (validationError != null) {
            return validationError;
        }

        UriComponentsBuilder uriBuilder = UriComponentsBuilder
            .fromUriString(apiBaseUrl + resolvedUrl);
        safeMap(queryParams).forEach(uriBuilder::queryParam);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        safeMap(headers).forEach(requestHeaders::set);
        String token = contextString(toolContext, AUTH_TOKEN_CONTEXT_KEY);
        if (token != null) {
            requestHeaders.setBearerAuth(token);
        }

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                uriBuilder.build().encode().toUri(),
                HttpMethod.GET,
                new HttpEntity<>(requestHeaders),
                String.class
            );
            log.debug("Skill HTTP GET completed: path={}, status={}, responseChars={}",
                resolvedUrl,
                response.getStatusCode().value(),
                response.getBody() == null ? 0 : response.getBody().length());
            return bounded(response.getBody());
        } catch (HttpStatusCodeException e) {
            return "HTTP " + e.getStatusCode().value() + "：" +
                bounded(e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            return "HTTP 请求失败：目标服务不可用或请求超时。";
        } catch (Exception e) {
            log.warn("Skill HTTP GET failed for {}: {}", resolvedUrl,
                e.getClass().getSimpleName());
            return "HTTP 请求失败：请求执行异常。";
        }
    }

    @Tool(
        description = "为 POST/PUT/PATCH/DELETE 写操作生成待确认请求；结果直接返回给应用，"
            + "由传统页面展示并由用户确认后执行。",
        returnDirect = true
    )
    public String buildHttpRequest(
        @ToolParam(description = "HTTP 写方法：POST/PUT/PATCH/DELETE") String method,
        @ToolParam(description = "API 路径（相对路径）") String url,
        @ToolParam(description = "路径参数") Map<String, String> pathParams,
        @ToolParam(description = "查询参数") Map<String, String> queryParams,
        @ToolParam(description = "请求体（JSON 对象）") Map<String, Object> body,
        ToolContext toolContext
    ) {
        String normalizedMethod = normalizeMethod(method);
        if (!MUTATION_METHODS.contains(normalizedMethod)) {
            return "构建请求被拒绝：buildHttpRequest 只接受写操作。";
        }
        String validationError = validateRequestParts(
            normalizedMethod, url, pathParams, queryParams, null, body
        );
        if (validationError != null) {
            return "构建请求被拒绝：" + validationError;
        }

        String resolvedUrl = resolvePathParams(url, pathParams);
        validationError = registry.validateResolvedApiRequest(normalizedMethod, resolvedUrl);
        if (validationError != null) {
            return "构建请求被拒绝：" + validationError;
        }
        validationError = requireLoadedApiSkill(
            normalizedMethod, resolvedUrl, toolContext, "构建请求被拒绝："
        );
        if (validationError != null) {
            return validationError;
        }

        PendingHttpRequest pendingRequest = new PendingHttpRequest(
            normalizedMethod,
            resolvedUrl,
            queryParams,
            body
        );

        String confirmUrl = apiBaseUrl + resolvedUrl;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("method", normalizedMethod);
        meta.put("url", confirmUrl);
        if (pathParams != null && !pathParams.isEmpty()) meta.put("pathParams", pathParams);
        if (queryParams != null && !queryParams.isEmpty()) meta.put("queryParams", queryParams);
        if (body != null && !body.isEmpty()) meta.put("body", body);

        try {
            String serialized = objectMapper.writeValueAsString(meta);
            MutationConfirmationSession confirmationSession =
                confirmationSession(toolContext);
            if (confirmationSession == null) {
                return "构建请求被拒绝：缺少当前请求的写操作确认上下文。";
            }
            if (!confirmationSession.register(pendingRequest)) {
                return "构建请求被拒绝：本轮已经存在待确认的写操作，请等待用户处理。";
            }
            return serialized;
        } catch (Exception e) {
            return "构建请求失败：无法序列化请求元数据。";
        }
    }

    @Tool(description = "读取技能的参考文件（适用于具有分层结构的技能，如 OpenAPI 生成的技能）")
    public String readSkillReference(
        @ToolParam(description = "技能名称，例如 swagger-petstore-openapi-3-0") String skillName,
        @ToolParam(description = "相对于该技能 references 目录的路径，例如 resources/pet.md 或 operations/addPet.md") String relativePath,
        ToolContext toolContext
    ) {
        if (skillName != null && registry.get(skillName).isPresent()) {
            String validationError = requireLoadedSkill(
                skillName, toolContext, "读取参考文件失败："
            );
            if (validationError != null) {
                return validationError;
            }
        }
        return referenceReader.read(skillName, relativePath);
    }

    private String validateRequestParts(
        String method,
        String url,
        Map<String, String> pathParams,
        Map<String, String> queryParams,
        Map<String, String> headers,
        Map<String, Object> body
    ) {
        String validationError = registry.validateApiRequest(method, url);
        if (validationError != null) {
            return validationError;
        }
        if (url.contains("?")) {
            return "URL 中不能内嵌查询字符串，请使用 queryParams。";
        }
        validationError = validateStringMap("路径参数", pathParams, false);
        if (validationError != null) {
            return validationError;
        }
        validationError = validatePathParamValues(pathParams);
        if (validationError != null) {
            return validationError;
        }
        validationError = validateStringMap("查询参数", queryParams, true);
        if (validationError != null) {
            return validationError;
        }
        validationError = validateHeaders(headers);
        if (validationError != null) {
            return validationError;
        }
        if (body != null) {
            try {
                if (objectMapper.writeValueAsBytes(body).length > MAX_BODY_LENGTH) {
                    return "请求体超过长度上限。";
                }
            } catch (Exception e) {
                return "请求体无法序列化。";
            }
        }
        return null;
    }

    private String validateStringMap(
        String label,
        Map<String, String> values,
        boolean allowBlankValue
    ) {
        if (values == null) {
            return null;
        }
        if (values.size() > MAX_MAP_ENTRIES) {
            return label + "数量超过上限 " + MAX_MAP_ENTRIES + "。";
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH
                || value == null || value.length() > MAX_VALUE_LENGTH
                || (!allowBlankValue && value.isBlank())
                || containsControlCharacter(key) || containsControlCharacter(value)) {
                return label + "包含非法键或值。";
            }
        }
        return null;
    }

    private String validateHeaders(Map<String, String> headers) {
        String validationError = validateStringMap("请求头", headers, false);
        if (validationError != null) {
            return validationError;
        }
        for (String name : safeMap(headers).keySet()) {
            if (!ALLOWED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return "请求头 `" + name + "` 不在允许列表中。";
            }
        }
        return null;
    }

    private String validatePathParamValues(Map<String, String> pathParams) {
        if (pathParams == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : pathParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()
                || value.contains("/") || value.contains("\\")
                || value.contains("?") || value.contains("#")
                || value.contains("{") || value.contains("}")
                || ".".equals(value) || "..".equals(value)
                || value.chars().anyMatch(c ->
                    Character.isWhitespace(c) || Character.isISOControl(c))) {
                return "路径参数包含非法值。";
            }
        }
        return null;
    }

    private String resolvePathParams(String url, Map<String, String> pathParams) {
        String resolvedUrl = url;
        if (pathParams != null && !pathParams.isEmpty()) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                resolvedUrl = resolvedUrl.replace(
                    "{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return resolvedUrl;
    }

    private SkillLoadSession skillSession(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object session = toolContext.getContext().get(SKILL_SESSION_CONTEXT_KEY);
        return session instanceof SkillLoadSession skillLoadSession
            ? skillLoadSession
            : null;
    }

    private String requireLoadedApiSkill(
        String method,
        String resolvedUrl,
        ToolContext toolContext,
        String prefix
    ) {
        SkillRegistry.ApiIndexEntry entry = registry.findApiEntry(resolvedUrl, method);
        if (entry == null) {
            return prefix + "API index 中没有找到对应的 Skill。";
        }
        return requireLoadedSkill(entry.getSkillName(), toolContext, prefix);
    }

    private String requireLoadedSkill(
        String skillName,
        ToolContext toolContext,
        String prefix
    ) {
        SkillLoadSession session = skillSession(toolContext);
        if (session == null) {
            return prefix + "缺少当前请求的 Skill 会话上下文。";
        }
        if (!session.loadedSkills().contains(skillName)) {
            return prefix + "当前请求尚未加载技能 `" + skillName + "`。"
                + "请先调用 loadSkill(\"" + skillName + "\") 获取完整操作指令。";
        }
        return null;
    }

    private MutationConfirmationSession confirmationSession(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Object session = toolContext.getContext().get(MutationConfirmationSession.CONTEXT_KEY);
        return session instanceof MutationConfirmationSession confirmationSession
            ? confirmationSession
            : null;
    }

    private String contextString(ToolContext toolContext, String key) {
        if (toolContext == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() > MAX_RESPONSE_LENGTH
            ? value.substring(0, MAX_RESPONSE_LENGTH) + "\n...[响应过长已截断]"
            : value;
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private String normalizeMethod(String method) {
        return method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    }

    private Map<String, String> safeMap(Map<String, String> values) {
        return values == null ? Map.of() : values;
    }
}
