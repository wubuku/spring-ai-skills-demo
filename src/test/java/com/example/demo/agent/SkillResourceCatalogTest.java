package com.example.demo.agent;

import com.example.demo.config.SkillResourceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillResourceCatalogTest {

    private static final String FLAT_SKILL = """
        ---
        name: %s
        description: fixture skill
        ---
        # Fixture

        ## Base URL
        - `/api/fixture`

        ## API 端点
        ```
        GET /api/fixture
        ```
        """;

    @Test
    void bindsDefaultAndCommaSeparatedSkillLocationsFromApplicationConfiguration() throws Exception {
        ConfigurableEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = environment.getPropertySources();
        for (var propertySource : loader.load(
            "application",
            new ClassPathResource("application.yml")
        )) {
            sources.addLast(propertySource);
        }

        SkillResourceProperties defaults = Binder.get(environment)
            .bind("app.skills", Bindable.of(SkillResourceProperties.class))
            .orElseThrow(() -> new IllegalStateException("app.skills 未绑定"));
        assertThat(defaults.getLocations()).containsExactly("classpath*:skills");

        environment.getPropertySources().addFirst(
            new org.springframework.core.env.MapPropertySource(
                "test",
                java.util.Map.of(
                    "SKILL_LOCATIONS",
                    "classpath*:skills, file:/tmp/company-skills"
                )
            )
        );

        SkillResourceProperties configured = Binder.get(environment)
            .bind("app.skills", Bindable.of(SkillResourceProperties.class))
            .orElseThrow(() -> new IllegalStateException("app.skills 未绑定"));
        assertThat(configured.getLocations())
            .containsExactly("classpath*:skills", "file:/tmp/company-skills");
    }

    @Test
    void discoversAndResolvesFilesystemSkillResources(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("skills");
        Path skillDir = root.resolve("file-skill");
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            FLAT_SKILL.formatted("file-skill"),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            skillDir.resolve("references/guide.md"),
            "filesystem reference",
            StandardCharsets.UTF_8
        );

        SkillResourceCatalog catalog = catalog(root.toUri().toString());

        assertThat(catalog.discover())
            .singleElement()
            .satisfies(skill -> {
                assertThat(skill.skillName()).isEqualTo("file-skill");
                assertThat(read(skill.skillDocument())).contains("GET /api/fixture");
                assertThat(skill.source()).contains("file-skill");
            });
        assertThat(read(catalog.resolve("file-skill", "references/guide.md")))
            .isEqualTo("filesystem reference");
        assertThat(catalog.findResources("file-skill", "references/*.md"))
            .extracting(Resource::getFilename)
            .containsExactly("guide.md");

        assertThatThrownBy(() -> catalog.resolve("file-skill", "../SKILL.md"))
            .isInstanceOf(SkillDefinitionException.class);
        assertThatThrownBy(() -> catalog.resolve("file-skill", "/etc/passwd"))
            .isInstanceOf(SkillDefinitionException.class);
        assertThatThrownBy(() -> catalog.findResources("file-skill", "references/**/*.md"))
            .isInstanceOf(SkillDefinitionException.class);
        assertThatThrownBy(() -> catalog.findResources("file-skill", "references/./*.md"))
            .isInstanceOf(SkillDefinitionException.class);
    }

    @Test
    void discoversNoDirectoryEntryJarBothOnClasspathAndAsExplicitJarLocation(
        @TempDir Path tempDir
    ) throws Exception {
        Path jarPath = createJar(
            tempDir.resolve("skills.jar"),
            "jar-skill",
            "jar reference"
        );

        URLClassLoader classLoader = new URLClassLoader(
            new URL[] {jarPath.toUri().toURL()},
            getClass().getClassLoader()
        );
        Thread currentThread = Thread.currentThread();
        ClassLoader previous = currentThread.getContextClassLoader();
        try {
            currentThread.setContextClassLoader(classLoader);

            SkillResourceCatalog classpathCatalog = new SkillResourceCatalog(
                new DefaultResourceLoader(classLoader),
                new SkillResourceProperties(List.of("classpath*:skills"))
            );
            assertThat(classpathCatalog.discover())
                .extracting(SkillResourceCatalog.SkillResource::skillName)
                .contains("jar-skill");
            assertThat(read(classpathCatalog.resolve(
                "jar-skill",
                "references/operations/list.md"
            ))).contains("jar reference");

            SkillResourceCatalog explicitJarCatalog = new SkillResourceCatalog(
                new DefaultResourceLoader(),
                new SkillResourceProperties(List.of(
                    "jar:" + jarPath.toUri() + "!/skills"
                ))
            );
            assertThat(explicitJarCatalog.discover())
                .singleElement()
                .extracting(SkillResourceCatalog.SkillResource::skillName)
                .isEqualTo("jar-skill");
            assertThat(read(explicitJarCatalog.resolve(
                "jar-skill",
                "references/operations/list.md"
            ))).contains("jar reference");

            SkillRegistry registry = new SkillRegistry(explicitJarCatalog);
            registry.init();
            SkillReferenceReader reader =
                new SkillReferenceReader(registry, explicitJarCatalog);
            assertThat(reader.read("jar-skill", "operations/list.md"))
                .contains("jar reference");
            SkillRegistry.ApiIndexEntry entry =
                registry.findApiEntry("/api/fixture/list", "GET");
            assertThat(entry).isNotNull();
            assertThat(entry.getSkillName()).isEqualTo("jar-skill");
            assertThat(registry.getFullApiDescription(entry))
                .contains("jar reference");
        } finally {
            currentThread.setContextClassLoader(previous);
            classLoader.close();
        }
    }

    @Test
    void discoversStableOrderAndLeavesDuplicateDetectionToRegistry(@TempDir Path tempDir)
        throws Exception {
        Path firstRoot = tempDir.resolve("first");
        Path secondRoot = tempDir.resolve("second");
        writeSkill(firstRoot, "z-skill", "z");
        writeSkill(secondRoot, "a-skill", "a");

        SkillResourceCatalog catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of(
                firstRoot.toUri().toString(),
                secondRoot.toUri().toString()
            ))
        );

        assertThat(catalog.discover())
            .extracting(SkillResourceCatalog.SkillResource::skillName)
            .containsExactly("a-skill", "z-skill");
    }

    @Test
    void registryRejectsDuplicateSkillNamesAcrossConfiguredLocations(@TempDir Path tempDir)
        throws Exception {
        Path firstRoot = tempDir.resolve("first");
        Path secondRoot = tempDir.resolve("second");
        writeSkill(firstRoot, "duplicate-skill", "first");
        writeSkill(secondRoot, "duplicate-skill", "second");
        SkillResourceCatalog catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of(
                firstRoot.toUri().toString(),
                secondRoot.toUri().toString()
            ))
        );

        assertThatThrownBy(() -> new SkillRegistry(catalog).init())
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("重复 Skill name")
            .hasMessageContaining("duplicate-skill");
    }

    @Test
    void registryRejectsFrontmatterNameThatDiffersFromResourceDirectory(
        @TempDir Path tempDir
    ) throws Exception {
        Path root = tempDir.resolve("skills");
        Path skillDir = root.resolve("directory-name");
        Files.createDirectories(skillDir);
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            FLAT_SKILL.formatted("frontmatter-name"),
            StandardCharsets.UTF_8
        );
        SkillResourceCatalog catalog = catalog(root.toUri().toString());

        assertThatThrownBy(() -> new SkillRegistry(catalog).init())
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("frontmatter-name")
            .hasMessageContaining("directory-name");
    }

    @Test
    void failsWhenConfiguredLocationHasNoSkills(@TempDir Path tempDir) {
        SkillResourceCatalog catalog = new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of(tempDir.toUri().toString()))
        );

        assertThatThrownBy(catalog::discover)
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining(tempDir.toUri().toString());
    }

    @Test
    void rejectsFilesystemSkillsReachedThroughSymlinkOutsideConfiguredRoot(
        @TempDir Path tempDir
    ) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("skills"));
        Path outside = tempDir.resolve("outside");
        writeSkill(outside, "linked-skill", "outside");
        Files.createSymbolicLink(
            root.resolve("linked-skill"),
            outside.resolve("linked-skill")
        );

        SkillResourceCatalog catalog = catalog(root.toUri().toString());

        assertThatThrownBy(catalog::discover)
            .isInstanceOf(SkillDefinitionException.class)
            .hasMessageContaining("无法解析 Skill resource");
    }

    private SkillResourceCatalog catalog(String root) {
        return new SkillResourceCatalog(
            new DefaultResourceLoader(),
            new SkillResourceProperties(List.of(root))
        );
    }

    private static void writeSkill(Path root, String name, String marker) throws IOException {
        Path skillDir = root.resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            FLAT_SKILL.formatted(name) + "\n" + marker,
            StandardCharsets.UTF_8
        );
    }

    private static Path createJar(Path jarPath, String skillName, String reference)
        throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (OutputStream output = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            putEntry(
                jar,
                "skills/" + skillName + "/SKILL.md",
                FLAT_SKILL.formatted(skillName).getBytes(StandardCharsets.UTF_8)
            );
            putEntry(
                jar,
                "skills/" + skillName + "/references/operations/list.md",
                ("# GET /list\n\n**JAR fixture operation**\n\n" + reference)
                    .getBytes(StandardCharsets.UTF_8)
            );
        }
        return jarPath;
    }

    private static void putEntry(JarOutputStream jar, String name, byte[] content)
        throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }

    private static String read(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
