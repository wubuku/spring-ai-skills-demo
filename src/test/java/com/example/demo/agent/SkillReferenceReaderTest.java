package com.example.demo.agent;

import com.example.demo.config.SkillResourceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillReferenceReaderTest {

    private SkillRegistry registry;
    private SkillResourceCatalog catalog;
    private SkillReferenceReader reader;

    @BeforeEach
    void setUp() throws Exception {
        catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of("classpath*:skills"))
        );
        registry = new SkillRegistry(catalog);
        registry.init();
        reader = new SkillReferenceReader(registry, catalog);
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
        assertThat(reader.read("swagger-petstore-openapi-3-0", "operations/" + "x".repeat(512)))
            .contains("路径过长");
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
        SkillCoreTools agUiTools = new SkillCoreTools(registry, reader);
        SkillLoadSession session = new SkillLoadSession();
        ToolContext context = new ToolContext(Map.of(
            SkillTools.SKILL_SESSION_CONTEXT_KEY, session
        ));
        ordinaryTools.loadSkill("swagger-petstore-openapi-3-0", context);

        String ordinary = ordinaryTools.readSkillReference(
            "swagger-petstore-openapi-3-0", "operations/addPet.md", context);
        String agUi = agUiTools.readSkillReference(
            "swagger-petstore-openapi-3-0", "operations/addPet.md");

        assertThat(ordinary).isEqualTo(agUi);
        assertThat(ordinary).contains("# POST /pet");
    }

    @Test
    void ordinaryReferenceReadRequiresTheSkillToBeLoadedInTheCurrentContext() {
        SkillTools ordinaryTools = new SkillTools(
            registry,
            new RestTemplate(),
            "http://localhost:8080",
            reader
        );
        ToolContext context = new ToolContext(Map.of(
            SkillTools.SKILL_SESSION_CONTEXT_KEY, new SkillLoadSession()
        ));

        assertThat(ordinaryTools.readSkillReference(
            "swagger-petstore-openapi-3-0",
            "operations/addPet.md",
            context
        ))
            .contains("尚未加载技能 `swagger-petstore-openapi-3-0`")
            .contains("loadSkill(\"swagger-petstore-openapi-3-0\")");
    }

    @Test
    void ordinaryReferenceReadKeepsInvalidSkillNameErrorsStable() {
        SkillTools ordinaryTools = new SkillTools(
            registry,
            new RestTemplate(),
            "http://localhost:8080",
            reader
        );

        assertThat(ordinaryTools.readSkillReference(
            null,
            "operations/addPet.md",
            new ToolContext(Map.of(
                SkillTools.SKILL_SESSION_CONTEXT_KEY, new SkillLoadSession()
            ))
        )).contains("技能名称非法");
    }

    @Test
    void keepsOrdinaryAndAgUiLoadedSkillStateIndependent() {
        SkillTools ordinaryTools = new SkillTools(
            registry,
            new RestTemplate(),
            "http://localhost:8080",
            reader
        );
        SkillCoreTools agUiTools = new SkillCoreTools(registry, reader);

        SkillLoadSession session = new SkillLoadSession();
        ToolContext context = new ToolContext(Map.of(
            SkillTools.SKILL_SESSION_CONTEXT_KEY, session
        ));
        ordinaryTools.loadSkill("search-products", context);
        String ordinaryDuplicate = ordinaryTools.loadSkill("search-products", context);
        assertThat(session.loadedSkills()).containsExactly("search-products");
        assertThat(ordinaryDuplicate).contains("已在本轮加载")
            .contains("不要再次调用 `loadSkill`");
        assertThat(agUiTools.getLoadedSkills()).isEmpty();

        agUiTools.loadSkill("search-products");
        String agUiDuplicate = agUiTools.loadSkill("search-products");
        assertThat(agUiTools.getLoadedSkills()).containsExactly("search-products");
        assertThat(agUiDuplicate).contains("已在本轮加载")
            .contains("不要再次调用 `loadSkill`");
        assertThat(session.loadedSkills()).containsExactly("search-products");
    }

    @Test
    void rejectsAReferenceThatExceedsTheReadLimitBeforeReturningContent() {
        SkillResourceCatalog oversizedCatalog = mock(SkillResourceCatalog.class);
        when(oversizedCatalog.resolve(
            "swagger-petstore-openapi-3-0",
            "references/operations/addPet.md"
        )).thenReturn(new ByteArrayResource(
            "x".repeat(64 * 1024 + 1).getBytes(StandardCharsets.UTF_8)
        ));
        SkillReferenceReader boundedReader =
            new SkillReferenceReader(registry, oversizedCatalog);

        assertThat(boundedReader.read(
            "swagger-petstore-openapi-3-0", "operations/addPet.md"))
            .contains("超过读取上限");
    }
}
