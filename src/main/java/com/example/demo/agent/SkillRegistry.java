package com.example.demo.agent;

import com.example.demo.model.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        var yaml = new ObjectMapper(new YAMLFactory());
        var skillsPath = Path.of("src/main/resources/skills");

        if (!Files.exists(skillsPath)) {
            Files.createDirectories(skillsPath);
            return;
        }

        Files.list(skillsPath)
            .filter(Files::isDirectory)
            .forEach(dir -> {
                var mdFile = dir.resolve("SKILL.md");
                if (!Files.exists(mdFile)) return;
                try {
                    String content = Files.readString(mdFile);
                    String[] parts = content.split("---", 3);
                    if (parts.length < 3) return;
                    
                    var meta = yaml.readValue(parts[1], Skill.SkillMeta.class);
                    String body = parts[2].strip();
                    skills.put(meta.getName(), new Skill(meta, body));
                } catch (IOException e) {
                    throw new RuntimeException("解析 Skill 失败: " + dir, e);
                }
            });
    }

    public Map<String, Skill> all() { return skills; }
    public Optional<Skill> get(String name) { return Optional.ofNullable(skills.get(name)); }
}
