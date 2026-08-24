package com.example.demo.agent;

import com.example.demo.config.SkillResourceProperties;
import com.example.demo.service.PromptLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillsAdvisorTest {

    private SkillRegistry registry;
    private SkillCoreTools skillCoreTools;
    private SkillsAdvisor advisor;

    @BeforeEach
    void setUp() throws Exception {
        SkillResourceCatalog catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of("classpath*:skills"))
        );
        registry = new SkillRegistry(catalog);
        registry.init();

        skillCoreTools = new SkillCoreTools(
            registry,
            new SkillReferenceReader(registry, catalog)
        );
        advisor = new SkillsAdvisor(
            registry,
            skillCoreTools,
            new PromptLoader(new DefaultResourceLoader()),
            "http://localhost:8080",
            false
        );
    }

    @AfterEach
    void resetSharedFrontendState() {
        skillCoreTools.reset();
    }

    @Test
    void backendModeInjectsBackendRulesAndStableLevelOneCatalog() {
        String systemPrompt = systemPrompt(Map.of(
            SkillsAdvisor.EXECUTION_MODE, SkillsAdvisor.MODE_BACKEND
        ));

        assertThat(systemPrompt)
            .contains("`search-products`")
            .contains("`swagger-petstore-openapi-3-0`")
            .contains("写操作 POST/PUT/PATCH/DELETE：必须调用 `buildHttpRequest`")
            .contains("pathParams")
            .contains("queryParams")
            .contains("body")
            .doesNotContain("参数都是**字符串**：`params`、`body` 都是 JSON 字符串");

        int previousIndex = -1;
        for (String skillName : registry.all().keySet()) {
            int currentIndex = systemPrompt.indexOf("`" + skillName + "`");
            assertThat(currentIndex)
                .as("Skill should appear in stable registry order: %s", skillName)
                .isGreaterThan(previousIndex);
            previousIndex = currentIndex;
        }
    }

    @Test
    void frontendModeInjectsBrowserRulesAndLoadedSkillBody() {
        skillCoreTools.loadSkill("search-products");

        String systemPrompt = systemPrompt(Map.of(
            SkillsAdvisor.EXECUTION_MODE, SkillsAdvisor.MODE_FRONTEND
        ));

        assertThat(systemPrompt)
            .contains("唯一可用的 HTTP 工具：`httpRequest`（前端执行，自动携带用户 token）")
            .contains("本轮已加载技能（禁止重复调用）")
            .contains("### 已激活技能：search-products")
            .contains("# 商品搜索技能")
            .doesNotContain("写操作 POST/PUT/PATCH/DELETE：必须调用 `buildHttpRequest`");
    }

    @Test
    void unknownOrMissingModeFallsBackToFrontendRules() {
        String defaultPrompt = systemPrompt(Map.of());
        String unknownModePrompt = systemPrompt(Map.of(
            SkillsAdvisor.EXECUTION_MODE, "unsupported-mode"
        ));

        assertThat(defaultPrompt)
            .contains("参数都是**字符串**：`params`、`body` 都是 JSON 字符串")
            .doesNotContain("写操作 POST/PUT/PATCH/DELETE：必须调用 `buildHttpRequest`");
        assertThat(unknownModePrompt)
            .contains("参数都是**字符串**：`params`、`body` 都是 JSON 字符串")
            .doesNotContain("写操作 POST/PUT/PATCH/DELETE：必须调用 `buildHttpRequest`");
    }

    @Test
    void advisorDoesNotCopyAuthenticationLikeContextValuesIntoSystemPrompt() {
        String secret = "test-only-auth-token-7e2b";
        String systemPrompt = systemPrompt(Map.of(
            "auth.token", secret,
            SkillsAdvisor.EXECUTION_MODE, SkillsAdvisor.MODE_BACKEND
        ));

        assertThat(systemPrompt).doesNotContain(secret);
    }

    private String systemPrompt(Map<String, Object> context) {
        ChatClientRequest request = ChatClientRequest.builder()
            .prompt(new Prompt("test user message"))
            .context(context)
            .build();
        return advisor.before(request, null).prompt().getSystemMessage().getText();
    }
}
