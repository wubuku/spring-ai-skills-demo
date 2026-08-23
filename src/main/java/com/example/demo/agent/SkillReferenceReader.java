package com.example.demo.agent;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Reads only files below a registered Skill's {@code references/} directory.
 *
 * The model receives a bounded response, while the input stream is also bounded so
 * a large resource cannot be loaded fully before truncation.
 */
@Component
public class SkillReferenceReader {

    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_CHARS = 4000;
    private static final Pattern VALID_SKILL_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ENCODED_PATH_ESCAPE =
        Pattern.compile("%(?:2e|2f|5c|00)", Pattern.CASE_INSENSITIVE);

    private final SkillRegistry registry;
    private final ResourceLoader resourceLoader;

    public SkillReferenceReader(SkillRegistry registry, ResourceLoader resourceLoader) {
        this.registry = registry;
        this.resourceLoader = resourceLoader;
    }

    public String read(String skillName, String relativePath) {
        String validationError = validate(skillName, relativePath);
        if (validationError != null) {
            return validationError;
        }

        String resourceLocation = "classpath:skills/" + skillName + "/references/" + relativePath;
        Resource resource = resourceLoader.getResource(resourceLocation);
        try {
            if (!resource.exists()) {
                return "✗ 读取参考文件失败：文件不存在。";
            }
            if (!resource.isReadable()) {
                return "✗ 读取参考文件失败：文件不可读。";
            }

            byte[] bytes;
            try (var input = resource.getInputStream()) {
                bytes = input.readNBytes(MAX_READ_BYTES + 1);
            }
            if (bytes.length > MAX_READ_BYTES) {
                return "✗ 读取参考文件失败：文件超过读取上限。";
            }

            String content = new String(bytes, StandardCharsets.UTF_8);
            return content.length() > MAX_RESPONSE_CHARS
                ? content.substring(0, MAX_RESPONSE_CHARS) + "\n...[文件过长已截断]"
                : content;
        } catch (IOException e) {
            return "✗ 读取参考文件失败：" + safeMessage(e);
        }
    }

    private String validate(String skillName, String relativePath) {
        if (skillName == null || !VALID_SKILL_NAME.matcher(skillName).matches()) {
            return "✗ 读取参考文件失败：技能名称非法。";
        }
        if (registry.get(skillName).isEmpty()) {
            return "✗ 读取参考文件失败：技能不存在。";
        }
        if (relativePath == null || relativePath.isBlank()) {
            return "✗ 读取参考文件失败：路径非法。";
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
            || relativePath.contains("\\") || relativePath.contains("\u0000")
            || ENCODED_PATH_ESCAPE.matcher(relativePath).find()) {
            return "✗ 读取参考文件失败：路径非法。";
        }

        String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return "✗ 读取参考文件失败：路径非法。";
            }
        }
        return null;
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "资源读取异常" : message;
    }
}
