package com.example.demo.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final SkillReferenceReader referenceReader;
    private final CopyOnWriteArrayList<String> loadedSkills = new CopyOnWriteArrayList<>();

    @Autowired
    public SkillCoreTools(
        SkillRegistry registry,
        SkillReferenceReader referenceReader
    ) {
        this.registry = registry;
        this.referenceReader = referenceReader;
    }

    public void reset() {
        loadedSkills.clear();
    }

    public List<String> getLoadedSkills() {
        return List.copyOf(loadedSkills);
    }

    @Tool(description = "加载指定技能的完整操作指令。在使用任何技能前必须先调用此工具。")
    public String loadSkill(
        @ToolParam(description = "技能名称，必须来自 available_skills 列表") String skillName
    ) {
        if (skillName == null || skillName.isBlank()) {
            return "✗ 错误：skillName 参数不能为空。请提供技能名称，例如 loadSkill('add-to-cart')。可用技能：search-products, get-product-detail, add-to-cart, checkout, view-cart";
        }
        return registry.get(skillName)
            .map(skill -> {
                boolean alreadyLoaded = loadedSkills.contains(skillName);
                loadedSkills.addIfAbsent(skillName);
                String linksHint = skill.getMeta().getLinks() == null ||
                    skill.getMeta().getLinks().isEmpty() ? "" :
                    "\n\n**相关技能（按需加载）：**\n" +
                    skill.getMeta().getLinks().stream()
                        .map(l -> "- `" + l.getName() + "`：" + l.getDescription())
                        .collect(Collectors.joining("\n"));
                String mutationReminder = "";
                // For skills that involve write operations, add a mandatory reminder
                String bodyLower = skill.getBody().toLowerCase();
                if (bodyLower.contains("post") || bodyLower.contains("put") || bodyLower.contains("delete")) {
                    mutationReminder = "\n\n⚠️ **关键提醒**：此技能涉及写操作。你**必须**调用 `httpRequest` 工具实际执行 API 调用，" +
                        "然后等待用户确认。**禁止**只用文字回复而不实际调用 API！";
                }
                String loadStatus = alreadyLoaded
                    ? "⚠️ 技能 `" + skillName + "` 已在本轮加载。请不要再次调用 `loadSkill`，直接使用下方指令。"
                    : "✓ 技能 `" + skillName + "` 已加载";
                return loadStatus + linksHint +
                       "\n\n---\n" + skill.getBody() + mutationReminder;
            })
            .orElse("✗ 错误：技能 `" + skillName + "` 不存在。可用技能：" +
                    registry.all().keySet().stream().sorted().collect(Collectors.joining(", ")) +
                    "\n请使用上述技能名称之一重新调用 loadSkill。");
    }

    @Tool(description = "读取技能的参考文件（适用于具有分层结构的技能，如 OpenAPI 生成的技能）")
    public String readSkillReference(
        @ToolParam(description = "技能名称，例如 swagger-petstore-openapi-3-0") String skillName,
        @ToolParam(description = "相对于该技能 references 目录的路径，例如 resources/pet.md 或 operations/addPet.md") String relativePath
    ) {
        return referenceReader.read(skillName, relativePath);
    }
}
