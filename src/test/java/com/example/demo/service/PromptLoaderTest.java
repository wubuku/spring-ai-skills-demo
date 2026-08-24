package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptLoaderTest {

    private static final String SYSTEM_PROMPT =
        "prompts/skills-advisor/system-prompt.template";
    private static final String FRONTEND_RULES =
        "prompts/skills-advisor/mode-rules.template";
    private static final String BACKEND_RULES =
        "prompts/skills-advisor/backend-mode-rules.template";

    @Test
    void classpathResourceAndFallbackStayIdenticalForSkillsAdvisorTemplates() {
        PromptLoader classpathLoader = new PromptLoader(new DefaultResourceLoader());
        PromptLoader fallbackLoader = new PromptLoader(unavailableResourceLoader());

        assertThat(fallbackLoader.getPrompt(SYSTEM_PROMPT))
            .isEqualTo(classpathLoader.getPrompt(SYSTEM_PROMPT));
        assertThat(fallbackLoader.getPrompt(FRONTEND_RULES))
            .isEqualTo(classpathLoader.getPrompt(FRONTEND_RULES));
        assertThat(fallbackLoader.getPrompt(BACKEND_RULES))
            .isEqualTo(classpathLoader.getPrompt(BACKEND_RULES));
    }

    @Test
    void replacesPlaceholdersAfterLoadingTemplate() {
        PromptLoader loader = new PromptLoader(new DefaultResourceLoader());

        String prompt = loader.getPrompt(SYSTEM_PROMPT, Map.of(
            "{{SKILL_LIST}}", "- `search-products`：搜索商品",
            "{{API_BASE_URL}}", "http://localhost:8080",
            "{{HTTP_TOOL_NAME}}", "httpRequest",
            "{{LOADED_CONTEXT}}", "本轮没有已加载技能",
            "{{MODE_RULES}}", "调用工具前先加载 Skill"
        ));

        assertThat(prompt)
            .contains("- `search-products`：搜索商品")
            .contains("http://localhost:8080")
            .contains("调用 httpRequest")
            .contains("本轮没有已加载技能")
            .contains("调用工具前先加载 Skill")
            .doesNotContain("{{SKILL_LIST}}")
            .doesNotContain("{{API_BASE_URL}}")
            .doesNotContain("{{HTTP_TOOL_NAME}}")
            .doesNotContain("{{LOADED_CONTEXT}}")
            .doesNotContain("{{MODE_RULES}}");
    }

    @Test
    void cachesTheSelectedTemplateUntilCacheIsCleared() throws IOException {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.getResource("classpath:" + SYSTEM_PROMPT)).thenReturn(resource);
        when(resource.exists()).thenReturn(true);
        when(resource.isReadable()).thenReturn(true);
        when(resource.getInputStream())
            .thenReturn(stream("first"))
            .thenReturn(stream("second"));

        PromptLoader loader = new PromptLoader(resourceLoader);

        assertThat(loader.getPrompt(SYSTEM_PROMPT)).isEqualTo("first");
        assertThat(loader.getPrompt(SYSTEM_PROMPT)).isEqualTo("first");

        loader.clearCache();

        assertThat(loader.getPrompt(SYSTEM_PROMPT)).isEqualTo("second");
    }

    private ResourceLoader unavailableResourceLoader() {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.getResource(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(resource);
        when(resource.exists()).thenReturn(false);
        when(resource.isReadable()).thenReturn(false);
        return resourceLoader;
    }

    private java.io.InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
