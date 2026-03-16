package com.example.demo.agent;

import com.example.demo.model.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    /**
     * API 端点索引
     * Key: "METHOD /path"（如 "POST /api/products/cart"）
     * Value: ApiIndexEntry（包含 Skill 名称和参考文件路径）
     */
    private final Map<String, ApiIndexEntry> apiIndex = new ConcurrentHashMap<>();

    /**
     * API 索引条目
     */
    @Data
    @AllArgsConstructor
    public static class ApiIndexEntry {
        /** 技能名称 */
        private String skillName;

        /** API 路径 */
        private String path;

        /** HTTP 方法 */
        private String method;

        /** API 描述（简短) */
        private String description;

        /** 参考文件路径（仅分层 Skill 有，如 "operations/addPet.md"） */
        private String referencePath;

        /** 是否为分层 Skill */
        private boolean hierarchical;
    }

    @PostConstruct
    public void init() throws IOException {
        var yaml = new ObjectMapper(new YAMLFactory());
        var skillsPath = Path.of("src/main/resources/skills");

        if (!Files.exists(skillsPath)) {
            Files.createDirectories(skillsPath);
            return;
        }

        // 1. 加载所有 Skills
        log.info("开始扫描 skills 目录: {}", skillsPath.toAbsolutePath());
        Files.list(skillsPath)
            .filter(Files::isDirectory)
            .forEach(dir -> {
                log.debug("扫描目录: {}", dir.getFileName());
                var mdFile = dir.resolve("SKILL.md");
                if (!Files.exists(mdFile)) {
                    log.debug("跳过目录 {} - 未找到 SKILL.md", dir.getFileName());
                    return;
                }
                try {
                    String content = Files.readString(mdFile);
                    String[] parts = content.split("---", 3);
                    if (parts.length < 3) {
                        log.warn("跳过技能 {} - YAML frontmatter 格式错误 (parts.length={})", dir.getFileName(), parts.length);
                        return;
                    }

                    var meta = yaml.readValue(parts[1], Skill.SkillMeta.class);
                    String body = parts[2].strip();
                    skills.put(meta.getName(), new Skill(meta, body));
                    log.info("成功加载技能: {} (name={})", dir.getFileName(), meta.getName());
                } catch (IOException e) {
                    log.error("解析 Skill 失败: {} - {}", dir.getFileName(), e.getMessage());
                    throw new RuntimeException("解析 Skill 失败: " + dir, e);
                }
            });

        log.info("技能加载完成，共加载 {} 个技能: {}", skills.size(), skills.keySet());

        // 2. 为所有 Skills 构建 API 索引
        for (Skill skill : skills.values()) {
                indexSkillApis(skill);
            }

        log.info("API 索引构建完成，共 {} 个端点", apiIndex.size());
    }

    /**
     * 为单个 Skill 构建 API 索引
     */
    private void indexSkillApis(Skill skill) {
        String skillName = skill.getMeta().getName();
        String body = skill.getBody();

        if (body == null || body.isEmpty()) {
            return;
        }

        // 检查是否为分层 Skill（有 references/operations 目录)
        Path operationsDir = Path.of("src/main/resources/skills/" + skillName + "/references/operations");
        boolean isHierarchical = Files.exists(operationsDir);

        if (isHierarchical) {
            // 分层 Skill：解析 operations/*.md 文件
            indexHierarchicalSkill(skillName, operationsDir);
        } else {
            // 平面 Skill:解析 SKILL.md 中的 API 端点
            indexFlatSkill(skillName, body, skill.getMeta().getDescription());
        }
    }

    /**
     * 索引平面 Skill 的 API
     */
    private void indexFlatSkill(String skillName, String body, String skillDescription) {
        // 匹配格式: "## API 端点\n```\nMETHOD /path\n```"
        // 或: "## API 端点\nMETHOD /path"
        Pattern endpointPattern = Pattern.compile(
            "##\\s*API\\s*端点\\s*\\n+```\\s*\\n?(\\w+)\\s+(\\S+)\\s*\\n?```|" +
            "##\\s*API\\s*端点\\s*\\n+(\\w+)\\s+(\\S+)",
            Pattern.MULTILINE
        );
        Matcher matcher = endpointPattern.matcher(body);

        while (matcher.find()) {
            String method = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String path = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);

            String indexKey = method.toUpperCase() + " " + path;
            apiIndex.put(indexKey, new ApiIndexEntry(
                skillName,
                path,
                method.toUpperCase(),
                skillDescription,
                null,  // 平面 Skill 无参考文件
                false
            ));
        }
    }

    /**
     * 索引分层 Skill 的 API（读取 operations/*.md)
     */
    private void indexHierarchicalSkill(String skillName, Path operationsDir) {
        try {
            Files.list(operationsDir)
                .filter(p -> p.toString().endsWith(".md"))
                .forEach(opFile -> indexOperationFile(skillName, opFile));
        } catch (IOException e) {
            log.warn("索引分层 Skill 失败: {}", skillName, e);
        }
    }

    /**
     * 索引单个操作文件
     */
    private void indexOperationFile(String skillName, Path operationFile) {
        try {
            String content = Files.readString(operationFile);

            // 解析操作文件的第一行，如 "# GET /pet/findByStatus"
            Pattern titlePattern = Pattern.compile("^#\\s+(\\w+)\\s+(\\S+)", Pattern.MULTILINE);
            Matcher matcher = titlePattern.matcher(content);

            if (matcher.find()) {
                String method = matcher.group(1);
                String path = matcher.group(2);

                // 提取简短描述（标题后的下一行，通常是 **Description:** 格式）
                String[] lines = content.split("\n");
                String description = "";
                for (int i = 1; i < Math.min(5, lines.length); i++) {
                    String line = lines[i].trim();
                    if (line.startsWith("**") && line.contains(":**")) {
                        description = line.replaceAll("\\*\\*", "").trim();
                        break;
                    }
                }

                String indexKey = method.toUpperCase() + " " + path;
                String relativePath = "operations/" + operationFile.getFileName().toString();

                apiIndex.put(indexKey, new ApiIndexEntry(
                    skillName,
                    path,
                    method.toUpperCase(),
                    description,
                    relativePath,
                    true
                ));
            }
        } catch (IOException e) {
            log.warn("索引操作文件失败: {}", operationFile, e);
        }
    }

    /**
     * 根据 API 路径和方法查找所有可能匹配的 API 描述
     * 支持路径参数匹配（如 /api/v3/pet/123 匹配 /api/v3/pet/{petId}）
     * 如果精确匹配成功，返回单个结果；否则返回所有可能的候选
     */
    public List<ApiIndexEntry> findAllApiEntries(String path, String method) {
        List<ApiIndexEntry> results = new ArrayList<>();

        // 1. 先尝试精确匹配
        String indexKey = method.toUpperCase() + " " + path;
        ApiIndexEntry exactMatch = apiIndex.get(indexKey);
        if (exactMatch != null) {
            results.add(exactMatch);
            return results;  // 精确匹配时直接返回
        }

        // 2. 如果精确匹配失败，收集所有模式匹配的候选
        String methodPrefix = method.toUpperCase() + " ";
        for (Map.Entry<String, ApiIndexEntry> indexEntry : apiIndex.entrySet()) {
            String patternKey = indexEntry.getKey();

            // 只检查相同 HTTP 方法的条目
            if (!patternKey.startsWith(methodPrefix)) {
                continue;
            }

            String pattern = patternKey.substring(methodPrefix.length());
            if (isPathMatch(path, pattern)) {
                results.add(indexEntry.getValue());
            }
        }

        return results;
    }

    /**
     * 根据 API 路径和方法查找单个最佳匹配的 API 描述
     */
    public ApiIndexEntry findApiEntry(String path, String method) {
        // 1. 先尝试精确匹配
        String indexKey = method.toUpperCase() + " " + path;
        ApiIndexEntry entry = apiIndex.get(indexKey);
        if (entry != null) {
            return entry;
        }

        // 2. 如果精确匹配失败，尝试模式匹配（处理路径参数）
        return findApiEntryByPattern(path, method);
    }

    /**
     * 通过模式匹配查找 API 条目（支持路径参数）
     */
    private ApiIndexEntry findApiEntryByPattern(String path, String method) {
        String methodPrefix = method.toUpperCase() + " ";

        // 遍历所有 API 条目，寻找最佳匹配
        ApiIndexEntry bestMatch = null;
        int bestMatchScore = -1;

        for (Map.Entry<String, ApiIndexEntry> indexEntry : apiIndex.entrySet()) {
            String patternKey = indexEntry.getKey();

            // 只检查相同 HTTP 方法的条目
            if (!patternKey.startsWith(methodPrefix)) {
                continue;
            }

            String pattern = patternKey.substring(methodPrefix.length());
            int score = calculateMatchScore(path, pattern);

            if (score > bestMatchScore) {
                bestMatchScore = score;
                bestMatch = indexEntry.getValue();
            }
        }

        return bestMatch;
    }

    /**
     * 检查路径是否匹配（支持路径参数）
     */
    private boolean isPathMatch(String concretePath, String patternPath) {
        return calculateMatchScore(concretePath, patternPath) > 0;
    }

    /**
     * 计算路径匹配分数
     * 返回匹配的段数，如果不匹配则返回 -1
     */
    private int calculateMatchScore(String concretePath, String patternPath) {
        String[] concreteParts = concretePath.split("/");
        String[] patternParts = patternPath.split("/");

        // 段数必须相同
        if (concreteParts.length != patternParts.length) {
            return -1;
        }

        int score = 0;
        for (int i = 0; i < concreteParts.length; i++) {
            String concretePart = concreteParts[i];
            String patternPart = patternParts[i];

            // 空段跳过
            if (concretePart.isEmpty() && patternPart.isEmpty()) {
                continue;
            }

            // 精确匹配
            if (concretePart.equals(patternPart)) {
                score += 2;  // 精确匹配得分更高
                continue;
            }

            // 模式参数匹配（{param} 形式）
            if (patternPart.startsWith("{") && patternPart.endsWith("}")) {
                score += 1;  // 参数匹配得分较低
                continue;
            }

            // 不匹配
            return -1;
        }

        return score;
    }

    /**
     * 获取完整的 API 描述文档(供 LLM 阅读)
     */
    public String getFullApiDescription(ApiIndexEntry entry) {
        if (entry == null) {
            return null;
        }

        Skill skill = skills.get(entry.getSkillName());
        if (skill == null) {
            return null;
        }

        if (entry.isHierarchical() && entry.getReferencePath() != null) {
            // 分层 Skill：读取操作文件
            try {
                Path opFile = Path.of("src/main/resources/skills/" + entry.getSkillName() + "/references/" + entry.getReferencePath());
                return Files.readString(opFile);
            } catch (IOException e) {
                log.warn("读取操作文件失败: {}", entry.getReferencePath(), e);
                return null;
            }
        } else {
            // 平面 Skill:提取 SKILL.md 中的相关部分
            return extractApiSection(skill.getBody(), entry.getMethod(), entry.getPath());
        }
    }

    /**
     * 从平面 Skill 的 body 中提取 API 相关部分
     */
    private String extractApiSection(String body, String method, String path) {
        if (body == null || body.isEmpty()) {
            return "";
        }

        // 提取从 "## API 端点" 开始到下一个 "##" 之前的所有内容
        // 以及 "## 返回结构" 部分
        StringBuilder result = new StringBuilder();

        // 提取 API 端点部分
        Pattern apiPattern = Pattern.compile(
            "(##\\s*API\\s*端点[\\s\\S]*?)(?=\\n##|$)"
        );
        Matcher apiMatcher = apiPattern.matcher(body);
        if (apiMatcher.find()) {
            result.append(apiMatcher.group(1)).append("\n\n");
        }

        // 提取返回结构部分
        Pattern returnPattern = Pattern.compile(
            "(##\\s*返回结构[\\s\\S]*?)(?=\\n##|$)"
        );
        Matcher returnMatcher = returnPattern.matcher(body);
        if (returnMatcher.find()) {
            result.append(returnMatcher.group(1));
        }

        return result.toString();
    }

    public Map<String, Skill> all() { return skills; }
    public Optional<Skill> get(String name) { return Optional.ofNullable(skills.get(name)); }
}
