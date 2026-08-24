package com.example.demo.agent;

import com.example.demo.model.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SkillRegistry {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern FRONTMATTER = Pattern.compile(
        "\\A(?:\\uFEFF)?---[ \\t]*\\r?\\n(.*?)(?:\\r?\\n)---[ \\t]*(?:\\r?\\n|\\z)(.*)\\z",
        Pattern.DOTALL
    );
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern API_METHOD = Pattern.compile("^[A-Z]+$");
    private static final Pattern API_PARAMETER_SEGMENT = Pattern.compile("\\{[^/{}]+}");
    private static final Pattern SKILL_SOURCE_PATH = Pattern.compile(
        "(?:^|[/\\\\\\[:])skills[/\\\\]([a-z0-9]+(?:-[a-z0-9]+)*)"
            + "[/\\\\]SKILL\\.md(?:\\]|$)"
    );
    private static final Set<String> SUPPORTED_METHODS =
        Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Map<String, String> skillSources = new ConcurrentHashMap<>();

    /**
     * API 端点索引。
     * Key: "METHOD /path"（如 "POST /api/products/cart"）
     * Value: ApiIndexEntry（包含 Skill 名称和参考文件路径）
     */
    private final Map<String, ApiIndexEntry> apiIndex = new ConcurrentHashMap<>();

    @Autowired
    private ResourceLoader resourceLoader;

    @Data
    @AllArgsConstructor
    public static class ApiIndexEntry {
        private String skillName;
        private String path;
        private String method;
        private String description;
        private String referencePath;
        private boolean hierarchical;
    }

    @PostConstruct
    public void init() throws IOException {
        skills.clear();
        skillSources.clear();
        apiIndex.clear();

        ResourcePatternResolver resolver =
            ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        Resource[] skillResources;
        try {
            skillResources = resolver.getResources("classpath:skills/*/SKILL.md");
        } catch (IOException e) {
            log.warn("类路径 Skill 扫描失败，回退文件系统: {}", e.getMessage());
            skillResources = new Resource[0];
        }

        if (skillResources.length > 0) {
            log.info("从类路径找到 {} 个技能", skillResources.length);
            for (Resource resource : skillResources) {
                String source = resource.getDescription();
                String skillDir = extractSkillDirName(resource);
                try {
                    registerSkill(source, parseSkillDocument(readResourceAsString(resource), source));
                    log.info("成功加载技能: {} (name={})", skillDir, skillDir);
                } catch (IOException e) {
                    throw new SkillDefinitionException("读取 Skill 失败: " + source, e);
                }
            }
        } else {
            log.warn("未从类路径找到技能，尝试从文件系统加载");
            loadFromFileSystem();
        }

        validateSkillLinks();
        log.info("技能加载完成，共加载 {} 个技能: {}", skills.size(), sortedSkillNames());
        for (Skill skill : skills.values()) {
            indexSkillApis(skill, resolver);
        }
        log.info("API 索引构建完成，共 {} 个端点", apiIndex.size());
    }

    private void loadFromFileSystem() throws IOException {
        Path skillsPath = Path.of("src/main/resources/skills");
        if (!Files.isDirectory(skillsPath)) {
            throw new SkillDefinitionException("文件系统 skills 目录不存在: " + skillsPath);
        }

        try (var directories = Files.list(skillsPath)) {
            directories.filter(Files::isDirectory)
                .sorted()
                .forEach(dir -> {
                    Path skillFile = dir.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillFile)) {
                        return;
                    }
                    try {
                        String source = skillFile.toString();
                        registerSkill(source, parseSkillDocument(Files.readString(skillFile), source));
                    } catch (IOException e) {
                        throw new SkillDefinitionException("读取 Skill 失败: " + skillFile, e);
                    }
                });
        }
    }

    static Skill parseSkillDocument(String content, String source) {
        if (content == null) {
            throw new SkillDefinitionException("Skill 文档为空: " + source);
        }
        Matcher matcher = FRONTMATTER.matcher(content);
        if (!matcher.matches()) {
            throw new SkillDefinitionException(
                "Skill frontmatter 必须从文档开头开始，且使用独占行 ---: " + source);
        }

        try {
            Skill.SkillMeta meta = YAML.readValue(matcher.group(1), Skill.SkillMeta.class);
            validateSkillMeta(meta, source);
            return new Skill(meta, matcher.group(2).strip());
        } catch (SkillDefinitionException e) {
            throw e;
        } catch (Exception e) {
            throw new SkillDefinitionException("解析 Skill frontmatter 失败: " + source, e);
        }
    }

    private static void validateSkillMeta(Skill.SkillMeta meta, String source) {
        if (meta == null) {
            throw new SkillDefinitionException("Skill frontmatter 为空: " + source);
        }
        String name = meta.getName() == null ? "" : meta.getName().trim();
        if (!SKILL_NAME.matcher(name).matches() || name.length() > 64) {
            throw new SkillDefinitionException(
                "Skill name 非法（仅允许小写字母、数字和连字符，长度不超过 64）: "
                    + source);
        }
        if (meta.getDescription() == null
            || meta.getDescription().isBlank()
            || meta.getDescription().length() > 1024) {
            throw new SkillDefinitionException(
                "Skill description 必填且长度不超过 1024: " + source);
        }
        if (meta.getLinks() != null) {
            for (Skill.SkillLink link : meta.getLinks()) {
                String linkName = link == null || link.getName() == null
                    ? ""
                    : link.getName().trim();
                if (!SKILL_NAME.matcher(linkName).matches()) {
                    throw new SkillDefinitionException(
                        "Skill links.name 非法: " + source);
                }
                link.setName(linkName);
            }
        }
        meta.setName(name);
    }

    synchronized void registerSkill(String source, Skill skill) {
        String name = skill.getMeta().getName();
        validateSkillSourceName(source, name);
        String previousSource = skillSources.putIfAbsent(name, source);
        if (previousSource != null) {
            throw new SkillDefinitionException(
                "发现重复 Skill name `" + name + "`，来源分别为 "
                    + previousSource + " 和 " + source);
        }
        skills.put(name, skill);
    }

    private static void validateSkillSourceName(String source, String skillName) {
        if (source == null) {
            return;
        }
        Matcher matcher = SKILL_SOURCE_PATH.matcher(source);
        if (matcher.find() && !skillName.equals(matcher.group(1))) {
            throw new SkillDefinitionException(
                "Skill `" + skillName + "` 必须与资源目录名 `"
                    + matcher.group(1) + "` 一致，来源: " + source);
        }
    }

    /**
     * Validate the Skill metadata graph after every resource has been registered.
     *
     * Keeping this check after discovery allows forward links between resources while
     * still failing startup before an invalid link can reach a model prompt.
     */
    void validateSkillLinks() {
        for (Map.Entry<String, Skill> entry : new TreeMap<>(skills).entrySet()) {
            String skillName = entry.getKey();
            Skill.SkillMeta meta = entry.getValue().getMeta();
            if (meta.getLinks() == null || meta.getLinks().isEmpty()) {
                continue;
            }

            Set<String> linkNames = new java.util.HashSet<>();
            String source = skillSources.getOrDefault(skillName, skillName);
            for (Skill.SkillLink link : meta.getLinks()) {
                String target = link.getName().trim();
                if (skillName.equals(target)) {
                    throw new SkillDefinitionException(
                        "Skill `" + skillName + "` 不能链接自身，来源: " + source);
                }
                if (!skills.containsKey(target)) {
                    throw new SkillDefinitionException(
                        "Skill `" + skillName + "` link 指向不存在的 Skill `"
                            + target + "`，来源: " + source);
                }
                if (!linkNames.add(target)) {
                    throw new SkillDefinitionException(
                        "Skill `" + skillName + "` 存在重复 link `"
                            + target + "`，来源: " + source);
                }
            }
        }
    }

    static void validateApiDefinition(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        if (!SUPPORTED_METHODS.contains(normalizedMethod)
            || !API_METHOD.matcher(normalizedMethod).matches()) {
            throw new SkillDefinitionException("不支持的 API method: " + method);
        }
        if (path == null || path.isBlank() || !path.startsWith("/")
            || path.contains("?") || path.contains("#")
            || path.chars().anyMatch(Character::isWhitespace)
            || path.contains("//")) {
            throw new SkillDefinitionException("API path 非法: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.contains("{") || segment.contains("}")) {
                if (!API_PARAMETER_SEGMENT.matcher(segment).matches()) {
                    throw new SkillDefinitionException("API path 参数段非法: " + path);
                }
            }
        }
    }

    private void indexSkillApis(Skill skill, ResourcePatternResolver resolver) {
        String skillName = skill.getMeta().getName();
        String body = skill.getBody();
        if (body == null || body.isEmpty()) {
            return;
        }

        try {
            Resource operationsRoot = resolver.getResource(
                "classpath:skills/" + skillName + "/references/operations");
            if (!operationsRoot.exists()) {
                indexFlatSkill(skillName, body, skill.getMeta().getDescription());
                return;
            }
            Resource[] opResources = resolver.getResources(
                "classpath:skills/" + skillName + "/references/operations/*.md");
            if (opResources.length > 0) {
                indexHierarchicalSkill(skillName, body, opResources);
            } else {
                indexFlatSkill(skillName, body, skill.getMeta().getDescription());
            }
        } catch (IOException e) {
            log.debug("扫描 Skill {} 的分层操作文件失败，回退平面索引: {}",
                skillName, e.getMessage());
            indexFlatSkill(skillName, body, skill.getMeta().getDescription());
        }
    }

    private void indexFlatSkill(String skillName, String body, String description) {
        Pattern endpointPattern = Pattern.compile(
            "##\\s*API\\s*端点\\s*\\n+```\\s*\\n?(\\w+)\\s+(\\S+)\\s*\\n?```|"
                + "##\\s*API\\s*端点\\s*\\n+(\\w+)\\s+(\\S+)",
            Pattern.MULTILINE
        );
        Matcher matcher = endpointPattern.matcher(body);
        while (matcher.find()) {
            String method = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String path = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            addApiEntry(new ApiIndexEntry(
                skillName, path, normalizeMethod(method), description, null, false
            ), skillName + "/SKILL.md");
        }
    }

    private void indexHierarchicalSkill(
        String skillName, String body, Resource[] opResources
    ) {
        String basePath = extractBasePath(body);
        Pattern titlePattern = Pattern.compile("^#\\s+(\\w+)\\s+(\\S+)", Pattern.MULTILINE);
        for (Resource resource : opResources) {
            try {
                String content = readResourceAsString(resource);
                Matcher matcher = titlePattern.matcher(content);
                if (!matcher.find()) {
                    throw new SkillDefinitionException(
                        "操作文件缺少 '# METHOD /path' 标题: " + resource.getDescription());
                }

                String method = normalizeMethod(matcher.group(1));
                String path = combinePaths(basePath, matcher.group(2));
                String description = extractOperationDescription(content);
                String filename = resource.getFilename();
                addApiEntry(new ApiIndexEntry(
                    skillName,
                    path,
                    method,
                    description,
                    "operations/" + filename,
                    true
                ), resource.getDescription());
            } catch (IOException e) {
                throw new SkillDefinitionException(
                    "索引操作文件失败: " + resource.getDescription(), e);
            }
        }
    }

    private void addApiEntry(ApiIndexEntry entry, String source) {
        validateApiDefinition(entry.getMethod(), entry.getPath());
        String key = entry.getMethod() + " " + entry.getPath();
        ApiIndexEntry previous = apiIndex.putIfAbsent(key, entry);
        if (previous != null) {
            throw new SkillDefinitionException(
                "发现重复 API index `" + key + "`，来源至少包括 "
                    + previous.getSkillName() + " 和 " + source);
        }
    }

    private String extractBasePath(String body) {
        Matcher section = Pattern.compile(
            "(?ms)^##\\s+Base URL\\s*\\n(.*?)(?=^##\\s|\\z)"
        ).matcher(body);
        if (!section.find()) {
            return "";
        }
        Matcher path = Pattern.compile("(?m)^\\s*-\\s+`?(/[^`\\s]*)`?\\s*$")
            .matcher(section.group(1));
        return path.find() ? path.group(1) : "";
    }

    private String extractOperationDescription(String content) {
        String[] lines = content.split("\\R");
        for (int i = 1; i < Math.min(6, lines.length); i++) {
            String line = lines[i].trim();
            if (line.startsWith("**") && line.endsWith("**")) {
                return line.replaceAll("\\*\\*", "").trim();
            }
        }
        return "";
    }

    private String combinePaths(String basePath, String operationPath) {
        String base = basePath == null ? "" : basePath;
        String operation = operationPath == null ? "" : operationPath;
        if (base.isEmpty()) {
            return operation;
        }
        return base.replaceAll("/+$", "") + "/" + operation.replaceFirst("^/+", "");
    }

    public List<ApiIndexEntry> findAllApiEntries(String path, String method) {
        String requestPath = pathOnly(path);
        String normalizedMethod = normalizeMethodOrNull(method);
        if (requestPath == null || normalizedMethod == null) {
            return List.of();
        }

        List<ApiIndexEntry> results = new ArrayList<>();
        ApiIndexEntry exactMatch = apiIndex.get(normalizedMethod + " " + requestPath);
        if (exactMatch != null) {
            return List.of(exactMatch);
        }

        for (Map.Entry<String, ApiIndexEntry> indexEntry : apiIndex.entrySet()) {
            String key = indexEntry.getKey();
            if (!key.startsWith(normalizedMethod + " ")) {
                continue;
            }
            String pattern = key.substring(normalizedMethod.length() + 1);
            if (calculateMatchScore(requestPath, pattern) > 0) {
                results.add(indexEntry.getValue());
            }
        }
        results.sort(Comparator.comparing(ApiIndexEntry::getPath));
        return results;
    }

    public ApiIndexEntry findApiEntry(String path, String method) {
        List<ApiIndexEntry> entries = findAllApiEntries(path, method);
        return entries.isEmpty() ? null : entries.get(0);
    }

    public String validateApiRequest(String method, String url) {
        String normalizedMethod = normalizeMethodOrNull(method);
        if (normalizedMethod == null) {
            return "不支持的 HTTP method：" + method;
        }
        if (url == null || url.isBlank()) {
            return "URL 不能为空。";
        }
        if (url.contains("#")) {
            return "不允许 URL fragment。";
        }
        if (url.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") || url.startsWith("//")) {
            return "不允许绝对 URL。";
        }
        String path = pathOnly(url);
        if (path == null || !path.startsWith("/")) {
            return "URL 路径必须是相对的绝对路径。";
        }
        if (path.contains("\\")
            || path.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
            || path.matches("(?i).*%(?:00|2e|2f|5c).*")) {
            return "URL 路径包含非法字符。";
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return "URL 路径不允许目录跳转。";
            }
        }
        if (findApiEntry(path, normalizedMethod) == null) {
            String hint = getApiIndex().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(normalizedMethod + " "))
                .map(entry -> "  " + entry.getKey()
                    + " -> loadSkill(\"" + entry.getValue().getSkillName() + "\")")
                .collect(Collectors.joining("\n"));
            return "URL \"" + url + "\" 不是已注册的 API 端点。"
                + "请先调用 loadSkill 获取正确的 API 路径。\n"
                + "可用的 " + normalizedMethod + " 端点：\n" + hint;
        }
        return null;
    }

    public String validateResolvedApiRequest(String method, String url) {
        String validationError = validateApiRequest(method, url);
        if (validationError != null) {
            return validationError;
        }
        String path = pathOnly(url);
        if (path.contains("{") || path.contains("}")) {
            return "URL 仍包含未解析的路径参数。";
        }
        return null;
    }

    private int calculateMatchScore(String concretePath, String patternPath) {
        String[] concreteParts = concretePath.split("/", -1);
        String[] patternParts = patternPath.split("/", -1);
        if (concreteParts.length != patternParts.length) {
            return -1;
        }

        int score = 0;
        for (int i = 0; i < concreteParts.length; i++) {
            String concretePart = concreteParts[i];
            String patternPart = patternParts[i];
            if (concretePart.equals(patternPart)) {
                score += 2;
            } else if (API_PARAMETER_SEGMENT.matcher(patternPart).matches()
                && !concretePart.isEmpty()) {
                score += 1;
            } else {
                return -1;
            }
        }
        return score;
    }

    public String getFullApiDescription(ApiIndexEntry entry) {
        if (entry == null) {
            return null;
        }
        Skill skill = skills.get(entry.getSkillName());
        if (skill == null) {
            return null;
        }
        if (entry.isHierarchical() && entry.getReferencePath() != null) {
            try {
                ResourcePatternResolver resolver =
                    ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
                Resource resource = resolver.getResource(
                    "classpath:skills/" + entry.getSkillName()
                        + "/references/" + entry.getReferencePath());
                return readResourceAsString(resource);
            } catch (IOException e) {
                log.warn("读取操作文件失败: {}", entry.getReferencePath(), e);
                return null;
            }
        }
        return extractApiSection(skill.getBody());
    }

    private String extractApiSection(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        Matcher apiMatcher = Pattern.compile(
            "(##\\s*API\\s*端点[\\s\\S]*?)(?=\\n##|$)"
        ).matcher(body);
        if (apiMatcher.find()) {
            result.append(apiMatcher.group(1)).append("\n\n");
        }
        Matcher returnMatcher = Pattern.compile(
            "(##\\s*返回结构[\\s\\S]*?)(?=\\n##|$)"
        ).matcher(body);
        if (returnMatcher.find()) {
            result.append(returnMatcher.group(1));
        }
        return result.toString();
    }

    private String pathOnly(String url) {
        if (url == null) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        int fragmentIndex = url.indexOf('#');
        int end = queryIndex >= 0 ? queryIndex : url.length();
        if (fragmentIndex >= 0 && fragmentIndex < end) {
            end = fragmentIndex;
        }
        return url.substring(0, end);
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeMethodOrNull(String method) {
        String normalized = normalizeMethod(method);
        return SUPPORTED_METHODS.contains(normalized) ? normalized : null;
    }

    private String extractSkillDirName(Resource resource) {
        String description = resource.getDescription();
        int skillsIndex = description.lastIndexOf("/skills/");
        if (skillsIndex < 0) {
            return "unknown";
        }
        String afterSkills = description.substring(skillsIndex + "/skills/".length());
        int nextSlash = afterSkills.indexOf('/');
        return nextSlash > 0 ? afterSkills.substring(0, nextSlash) : "unknown";
    }

    private String readResourceAsString(Resource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private List<String> sortedSkillNames() {
        return skills.keySet().stream().sorted().toList();
    }

    public Map<String, Skill> all() {
        return Collections.unmodifiableMap(new TreeMap<>(skills));
    }

    public Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public Map<String, ApiIndexEntry> getApiIndex() {
        return apiIndex.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }
}
