package com.example.demo.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SkillReferenceReaderTest {

    private SkillRegistry registry;
    private SkillReferenceReader reader;

    @BeforeEach
    void setUp() throws Exception {
        registry = new SkillRegistry();
        Field resourceLoader = SkillRegistry.class.getDeclaredField("resourceLoader");
        resourceLoader.setAccessible(true);
        resourceLoader.set(registry, new DefaultResourceLoader());
        registry.init();
        reader = new SkillReferenceReader(registry, new DefaultResourceLoader());
    }

    @Test
    void readsAReferenceFileWithinTheSkillReferenceRoot() {
        String content = reader.read("swagger-petstore-openapi-3-0", "operations/addPet.md");

        assertThat(content).startsWith("# POST /pet");
        assertThat(content).contains("Operation ID");
    }

    @Test
    void rejectsTraversalAbsoluteAndMalformedReferencePaths() {
        assertThat(reader.read("swagger-petstore-openapi-3-0", "../SKILL.md"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "../../application.yml"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "/etc/passwd"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "..\\SKILL.md"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "%2e%2e/SKILL.md"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "operations/%2faddPet.md"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "operations/%5caddPet.md"))
            .contains("路径非法");
        assertThat(reader.read("swagger-petstore-openapi-3-0", "operations//addPet.md"))
            .contains("路径非法");
        assertThat(reader.read("unknown-skill", "operations/addPet.md"))
            .contains("技能不存在");
    }

    @Test
    void ordinaryAndAgUiToolsUseTheSameReferenceReaderContract() {
        SkillTools ordinaryTools = new SkillTools(
            registry,
            new RestTemplate(),
            "http://localhost:8080",
            reader
        );
        SkillCoreTools agUiTools = new SkillCoreTools(registry, ordinaryTools, reader);

        String ordinary = ordinaryTools.readSkillReference(
            "swagger-petstore-openapi-3-0", "operations/addPet.md");
        String agUi = agUiTools.readSkillReference(
            "swagger-petstore-openapi-3-0", "operations/addPet.md");

        assertThat(ordinary).isEqualTo(agUi);
        assertThat(ordinary).contains("# POST /pet");
    }

    @Test
    void recordsEachLoadedSkillOnlyOnceAcrossBothToolAdapters() {
        SkillTools ordinaryTools = new SkillTools(
            registry,
            new RestTemplate(),
            "http://localhost:8080",
            reader
        );
        SkillCoreTools agUiTools = new SkillCoreTools(registry, ordinaryTools, reader);

        ordinaryTools.loadSkill("search-products");
        String ordinaryDuplicate = ordinaryTools.loadSkill("search-products");
        assertThat(ordinaryTools.getLoadedSkills()).containsExactly("search-products");
        assertThat(ordinaryDuplicate).contains("已在本轮加载")
            .contains("不要再次调用 `loadSkill`");

        agUiTools.reset();
        agUiTools.loadSkill("search-products");
        String agUiDuplicate = agUiTools.loadSkill("search-products");
        assertThat(agUiTools.getLoadedSkills()).containsExactly("search-products");
        assertThat(agUiDuplicate).contains("已在本轮加载")
            .contains("不要再次调用 `loadSkill`");
    }

    @Test
    void rejectsAReferenceThatExceedsTheReadLimitBeforeReturningContent() {
        ResourceLoader oversizedResourceLoader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(
                    "x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
        SkillReferenceReader boundedReader =
            new SkillReferenceReader(registry, oversizedResourceLoader);

        assertThat(boundedReader.read(
            "swagger-petstore-openapi-3-0", "operations/addPet.md"))
            .contains("超过读取上限");
    }
}
