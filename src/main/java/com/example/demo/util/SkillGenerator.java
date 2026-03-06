package com.example.demo.util;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import java.nio.file.*;

//@Component  // 取消注释以在启动时自动生成
public class SkillGenerator implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // TODO: 解析 OpenAPI JSON，为每个 path 生成 SKILL.md
        Path skillsDir = Paths.get("src/main/resources/skills");
        Files.createDirectories(skillsDir);
        System.out.println("Skills 已生成到：" + skillsDir.toAbsolutePath());
    }
}
