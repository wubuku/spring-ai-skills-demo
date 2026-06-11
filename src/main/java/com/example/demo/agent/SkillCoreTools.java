package com.example.demo.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 技能核心工具（AG-UI 专用）
 *
 * 设计目的：在 AG-UI 模式下，HTTP 调用必须由前端持有用户 access token 来执行，
 * 因此后端不应暴露 `httpRequest` / `buildHttpRequest` 给模型，避免与前端 CopilotKit
 * 注册的 `httpRequest` 工具同名/近名冲突导致 LLM tool_choice 决策死循环。
 *
 * 本类只暴露：
 *   - loadSkill：加载技能描述
 *   - readSkillReference：读取技能参考文件
 *
 * 完整 HTTP 工具仍在 {@link SkillTools} 中保留（供 AgentService 链路使用）。
 */
@Component
public class SkillCoreTools {

    private final SkillRegistry registry;
    private final SkillTools skillTools;

    public SkillCoreTools(SkillRegistry registry, SkillTools skillTools) {
        this.registry = registry;
        this.skillTools = skillTools;
    }

    public void reset() {
        skillTools.reset();
    }

    public List<String> getLoadedSkills() {
        return skillTools.getLoadedSkills();
    }

    @Tool(description = "加载指定技能的完整操作指令。在使用任何技能前必须先调用此工具。")
    public String loadSkill(
        @ToolParam(description = "技能名称，必须来自 available_skills 列表") String skillName
    ) {
        return registry.get(skillName)
            .map(skill -> {
                skillTools.markSkillLoaded(skillName);
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
