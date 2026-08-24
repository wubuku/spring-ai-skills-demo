package com.example.demo.agent;

import com.example.demo.model.Skill;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryTest {

    @Test
    void parsesFrontmatterOnlyAtDocumentStartAndPreservesOpenApiMetadata() {
        String document = """
            ---
            name: sample-skill
            description: A sample skill
            version: 1.2.3
            license: Apache 2.0
            metadata:
              api-version: "1.0"
            links:
              - name: next-skill
                description: Continue with the next step
            ---
            # Sample
            """;

        Skill skill = SkillRegistry.parseSkillDocument(document, "fixture/SKILL.md");

        assertThat(skill.getMeta().getName()).isEqualTo("sample-skill");
        assertThat(skill.getMeta().getDescription()).isEqualTo("A sample skill");
        assertThat(skill.getMeta().getLicense()).isEqualTo("Apache 2.0");
        assertThat(skill.getMeta().getMetadata())
            .containsEntry("api-version", "1.0");
        assertThat(skill.getMeta().getLinks())
            .singleElement()
            .extracting(Skill.SkillLink::getName)
            .isEqualTo("next-skill");
        assertThat(skill.getBody()).isEqualTo("# Sample");
    }

    @Test
    void rejectsMalformedOrUnsafeFrontmatterWithSourceContext() {
        assertThatThrownBy(() -> SkillRegistry.parseSkillDocument(
            "name: missing-delimiters\n---\nbody", "broken/SKILL.md"))
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("broken/SKILL.md");

        assertThatThrownBy(() -> SkillRegistry.parseSkillDocument(
            """
            ---
            name: invalid_name
            description: valid
            ---
            body
            """, "invalid-name/SKILL.md"))
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("name");

        assertThatThrownBy(() -> SkillRegistry.parseSkillDocument(
            """
            ---
            name: missing-description
            ---
            body
            """, "missing-description/SKILL.md"))
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("description");
    }

    @Test
    void rejectsDuplicateSkillNamesInsteadOfSilentlyOverwriting() {
        Skill first = SkillRegistry.parseSkillDocument("""
            ---
            name: duplicate-skill
            description: first
            ---
            first
            """, "one/SKILL.md");
        Skill second = SkillRegistry.parseSkillDocument("""
            ---
            name: duplicate-skill
            description: second
            ---
            second
            """, "two/SKILL.md");

        SkillRegistry registry = new SkillRegistry();
        registry.registerSkill("one/SKILL.md", first);

        assertThatThrownBy(() -> registry.registerSkill("two/SKILL.md", second))
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("duplicate-skill")
            .hasMessageContaining("one/SKILL.md")
            .hasMessageContaining("two/SKILL.md");
    }

    @Test
    void rejectsDanglingSkillLinksAfterAllSkillsAreRegistered() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerSkill("root/SKILL.md", SkillRegistry.parseSkillDocument("""
            ---
            name: root-skill
            description: root
            links:
              - name: missing-skill
                description: missing
            ---
            body
            """, "root/SKILL.md"));

        assertThatThrownBy(registry::validateSkillLinks)
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("root-skill")
            .hasMessageContaining("missing-skill")
            .hasMessageContaining("root/SKILL.md");
    }

    @Test
    void rejectsSelfReferentialAndDuplicateSkillLinks() {
        SkillRegistry selfReferential = new SkillRegistry();
        selfReferential.registerSkill("self/SKILL.md", SkillRegistry.parseSkillDocument("""
            ---
            name: self-skill
            description: self
            links:
              - name: self-skill
                description: loop
            ---
            body
            """, "self/SKILL.md"));

        assertThatThrownBy(selfReferential::validateSkillLinks)
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("不能链接自身")
            .hasMessageContaining("self-skill");

        SkillRegistry duplicateLinks = new SkillRegistry();
        duplicateLinks.registerSkill("duplicate-links/SKILL.md",
            SkillRegistry.parseSkillDocument("""
                ---
                name: duplicate-links
                description: duplicate links
                links:
                  - name: next-skill
                    description: first
                  - name: next-skill
                    description: second
                ---
                body
                """, "duplicate-links/SKILL.md"));
        duplicateLinks.registerSkill("next/SKILL.md",
            SkillRegistry.parseSkillDocument("""
                ---
                name: next-skill
                description: next
                ---
                body
                """, "next/SKILL.md"));

        assertThatThrownBy(duplicateLinks::validateSkillLinks)
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("重复 link")
            .hasMessageContaining("duplicate-links");
    }

    @Test
    void validatesApiDefinitionsAndMatchesIndexedPathParameters() throws Exception {
        assertThatCode(() -> SkillRegistry.validateApiDefinition("GET", "/api/items/{id}"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> SkillRegistry.validateApiDefinition("TRACE", "/api/items"))
            .isInstanceOf(SkillDefinitionException.class);
        assertThatThrownBy(() -> SkillRegistry.validateApiDefinition("GET", "api/items"))
            .isInstanceOf(SkillDefinitionException.class);

        SkillRegistry registry = initializedRegistry();

        assertThat(registry.findApiEntry("/api/products/3", "GET"))
            .extracting(SkillRegistry.ApiIndexEntry::getSkillName)
            .isEqualTo("get-product-detail");
        assertThat(registry.findApiEntry("/api/v3/pet/123", "GET"))
            .extracting(SkillRegistry.ApiIndexEntry::getReferencePath)
            .isEqualTo("operations/getPetById.md");
        assertThat(registry.findApiEntry("/api/products/3", "POST")).isNull();

        assertThat(registry.validateApiRequest("GET", "/api/products/3")).isNull();
        assertThat(registry.validateApiRequest("GET", "/api/products/3?keyword=headset")).isNull();
        assertThat(registry.validateApiRequest("POST", "/api/unknown")).contains("不是已注册");
        assertThat(registry.validateApiRequest("GET", "https://example.com/secret"))
            .contains("不允许绝对 URL");
        assertThat(registry.validateApiRequest("GET", "/api/products/3#fragment"))
            .contains("不允许 URL fragment");
        assertThat(registry.validateApiRequest("GET", "/api/products/.."))
            .contains("目录跳转");
        assertThat(registry.validateApiRequest("GET", "/api/products/%2e%2e"))
            .contains("非法字符");
        assertThat(registry.validateApiRequest("GET", "/api/products/%00"))
            .contains("非法字符");
        assertThat(registry.validateResolvedApiRequest("GET", "/api/products/{id}"))
            .contains("未解析");
        assertThat(registry.validateResolvedApiRequest("GET", "/api/products/3"))
            .isNull();
    }

    @Test
    void discoversAllCurrentRuntimeSkillsAndBuildsStableApiIndex() throws Exception {
        SkillRegistry registry = initializedRegistry();

        assertThat(registry.all()).containsKeys(
            "search-products",
            "get-product-detail",
            "add-to-cart",
            "view-cart",
            "checkout",
            "swagger-petstore-openapi-3-0"
        );
        assertThat(registry.getApiIndex()).containsKeys(
            "GET /api/products",
            "GET /api/products/{id}",
            "POST /api/products/cart",
            "GET /api/v3/pet/{petId}"
        );
        assertThat(registry.getApiIndex().values())
            .allSatisfy(entry -> assertThat(entry.getMethod()).isUpperCase());
    }

    private SkillRegistry initializedRegistry() throws Exception {
        SkillRegistry registry = new SkillRegistry();
        Field resourceLoader = SkillRegistry.class.getDeclaredField("resourceLoader");
        resourceLoader.setAccessible(true);
        resourceLoader.set(registry, new DefaultResourceLoader());
        registry.init();
        return registry;
    }
}
