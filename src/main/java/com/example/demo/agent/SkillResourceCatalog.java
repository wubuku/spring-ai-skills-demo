package com.example.demo.agent;

import com.example.demo.config.SkillResourceProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Discovers runtime Skills and keeps every Skill's related resources on one source.
 *
 * A source can be an exploded filesystem directory, a classpath resource, a regular
 * JAR, or a Spring Boot nested JAR resource. The catalog is intentionally read-only
 * and does not expose arbitrary file access to the model.
 */
@Component
public class SkillResourceCatalog {

    private static final Pattern SKILL_NAME =
        Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ENCODED_ESCAPE =
        Pattern.compile("%(?:2e|2f|5c|00)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFERENCE_GLOB =
        Pattern.compile("[^/\\\\:*?]+(?:/[^/\\\\:*?]+)*/\\*\\.md");

    private final ResourceLoader resourceLoader;
    private final ResourcePatternResolver resolver;
    private final SkillResourceProperties properties;
    private volatile List<SkillResource> lastDiscovery = List.of();

    public SkillResourceCatalog(
        ResourceLoader resourceLoader,
        SkillResourceProperties properties
    ) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
        this.resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader);
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public List<SkillResource> discover() {
        List<String> locations = properties.getLocations();
        if (locations.isEmpty()) {
            throw new SkillDefinitionException(
                "app.skills.locations 不能为空，至少配置一个 Skill resource location");
        }

        List<SkillResource> discovered = new ArrayList<>();
        for (String location : locations) {
            List<SkillResource> fromLocation = discoverLocation(location);
            if (fromLocation.isEmpty()) {
                throw new SkillDefinitionException(
                    "Skill resource location 未发现任何 SKILL.md: " + location);
            }
            discovered.addAll(fromLocation);
        }

        List<SkillResource> sorted = discovered.stream()
            .collect(Collectors.toMap(
                SkillResource::identity,
                resource -> resource,
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .sorted(Comparator
                .comparing(SkillResource::skillName)
                .thenComparing(SkillResource::source))
            .toList();
        lastDiscovery = sorted;
        return sorted;
    }

    public java.util.Optional<SkillResource> find(String skillName) {
        if (skillName == null || !SKILL_NAME.matcher(skillName).matches()) {
            return java.util.Optional.empty();
        }
        List<SkillResource> resources = lastDiscovery;
        if (resources.isEmpty()) {
            resources = discover();
        }
        List<SkillResource> matches = resources.stream()
            .filter(resource -> resource.skillName().equals(skillName))
            .toList();
        if (matches.size() > 1) {
            throw new SkillDefinitionException(
                "发现重复 Skill name `" + skillName + "`，来源分别为 "
                    + matches.stream().map(SkillResource::source).collect(Collectors.joining(" 和 "))
            );
        }
        return matches.stream().findFirst();
    }

    public Resource resolve(String skillName, String relativePath) {
        validateRelativePath(relativePath);
        SkillResource skill = find(skillName).orElseThrow(() ->
            new SkillDefinitionException("Skill 不存在或未注册: " + skillName));
        try {
            return skill.scope().resolve(relativePath);
        } catch (IOException e) {
            throw new SkillDefinitionException(
                "解析 Skill resource 失败: skill=" + skillName
                    + ", source=" + skill.source(),
                e
            );
        }
    }

    public Resource[] findResources(String skillName, String relativePattern) {
        validateReferenceGlob(relativePattern);
        SkillResource skill = find(skillName).orElseThrow(() ->
            new SkillDefinitionException("Skill 不存在或未注册: " + skillName));
        try {
            return skill.scope().find(relativePattern);
        } catch (IOException e) {
            throw new SkillDefinitionException(
                "扫描 Skill resource 失败: skill=" + skillName
                    + ", source=" + skill.source(),
                e
            );
        }
    }

    private List<SkillResource> discoverLocation(String location) {
        String pattern = appendPattern(location);
        List<SkillResource> resources = new ArrayList<>();
        Set<String> identities = new HashSet<>();

        try {
            for (Resource resource : resolver.getResources(pattern)) {
                SkillResource skill = toSkillResource(resource, location);
                if (identities.add(skill.identity())) {
                    resources.add(skill);
                }
            }
        } catch (IOException e) {
            throw new SkillDefinitionException(
                "扫描 Skill resource location 失败: " + location,
                e
            );
        }

        if (location.startsWith("classpath*:")) {
            resources.addAll(discoverClasspathJars(location, identities));
        } else if (location.startsWith("jar:")) {
            resources.addAll(discoverExplicitJar(location, identities));
        }
        return resources;
    }

    private List<SkillResource> discoverClasspathJars(
        String location,
        Set<String> identities
    ) {
        String prefix = normalizeEntryPrefix(location.substring("classpath*:".length()));
        ClassLoader classLoader = resourceLoader.getClassLoader();
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        if (classLoader == null) {
            return List.of();
        }

        List<SkillResource> resources = new ArrayList<>();
        try {
            Enumeration<URL> manifests =
                classLoader.getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                URL manifest = manifests.nextElement();
                if (!"jar".equals(manifest.getProtocol())) {
                    continue;
                }
                JarURLConnection connection = (JarURLConnection) manifest.openConnection();
                connection.setUseCaches(false);
                URL jarUrl = connection.getJarFileURL();
                try (JarFile jar = connection.getJarFile()) {
                    resources.addAll(scanJar(
                        jar,
                        jarUrl,
                        prefix,
                        identities
                    ));
                }
            }
        } catch (IOException e) {
            throw new SkillDefinitionException(
                "扫描 classpath JAR Skill 失败: " + location,
                e
            );
        }
        return resources;
    }

    private List<SkillResource> discoverExplicitJar(
        String location,
        Set<String> identities
    ) {
        try {
            URL rootUrl = resourceLoader.getResource(location).getURL();
            if (!"jar".equals(rootUrl.getProtocol())) {
                throw new IOException("不是 jar URL: " + rootUrl);
            }
            JarURLConnection connection = (JarURLConnection) rootUrl.openConnection();
            connection.setUseCaches(false);
            URL jarUrl = connection.getJarFileURL();
            String prefix = normalizeEntryPrefix(connection.getEntryName());
            try (JarFile jar = openFileJar(jarUrl)) {
                return scanJar(jar, jarUrl, prefix, identities);
            }
        } catch (IOException e) {
            throw new SkillDefinitionException(
                "扫描显式 JAR Skill location 失败: " + location,
                e
            );
        }
    }

    private List<SkillResource> scanJar(
        JarFile jar,
        URL jarUrl,
        String entryPrefix,
        Set<String> identities
    ) throws IOException {
        List<SkillResource> resources = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory()
                || !name.startsWith(entryPrefix)
                || !name.endsWith("/SKILL.md")) {
                continue;
            }

            String skillName = lastPathSegment(name.substring(0, name.length() - "/SKILL.md".length()));
            if (!SKILL_NAME.matcher(skillName).matches()) {
                continue;
            }
            String resourceUrl = "jar:" + jarUrl + "!/" + name;
            Resource document = new UrlResource(resourceUrl);
            SkillResource skill = new SkillResource(
                skillName,
                document,
                resourceUrl,
                createJarScope(
                    document,
                    jarUrl,
                    name.substring(0, name.lastIndexOf('/') + 1)
                )
            );
            if (identities.add(skill.identity())) {
                resources.add(skill);
            }
        }
        return resources;
    }

    private SkillResource toSkillResource(Resource resource, String location) {
        try {
            URL url = resource.getURL();
            String source = url.toExternalForm();
            String documentPath = url.getPath();
            if (!documentPath.endsWith("/SKILL.md")) {
                throw new IOException("资源不是 Skill 文档: " + source);
            }
            String skillName = lastPathSegment(
                documentPath.substring(0, documentPath.length() - "/SKILL.md".length())
            );
            if (!SKILL_NAME.matcher(skillName).matches()) {
                throw new SkillDefinitionException(
                    "Skill 目录名非法: " + skillName + "，来源: " + source);
            }
            return new SkillResource(
                skillName,
                resource,
                source,
                createScope(resource, url, location)
            );
        } catch (IOException | IllegalArgumentException e) {
            throw new SkillDefinitionException("无法解析 Skill resource: " + resource, e);
        }
    }

    private ResourceScope createScope(
        Resource skillDocument,
        URL documentUrl,
        String location
    ) throws IOException {
        if ("file".equals(documentUrl.getProtocol())) {
            Path document = Path.of(URI.create(documentUrl.toExternalForm()));
            Path allowedRoot = location.startsWith("file:")
                ? Path.of(URI.create(stripResourcePattern(location)))
                : document.getParent();
            return new FileScope(document.getParent(), allowedRoot);
        }
        if ("jar".equals(documentUrl.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) documentUrl.openConnection();
            connection.setUseCaches(false);
            return createJarScope(
                skillDocument,
                connection.getJarFileURL(),
                connection.getEntryName().substring(
                    0,
                    connection.getEntryName().lastIndexOf('/') + 1
                )
            );
        }
        throw new IOException("不支持的 Skill resource URL scheme: " + documentUrl);
    }

    private ResourceScope createJarScope(
        Resource skillDocument,
        URL jarUrl,
        String entryPrefix
    ) throws IOException {
        if ("file".equals(jarUrl.getProtocol())) {
            return new JarScope(jarUrl, entryPrefix);
        }
        return new ResolverScope(skillDocument, resolver);
    }

    private String stripResourcePattern(String location) {
        String normalized = location.trim();
        int wildcard = normalized.indexOf('*');
        if (wildcard >= 0) {
            normalized = normalized.substring(0, wildcard);
        }
        return trimTrailingSlashes(normalized);
    }

    private String appendPattern(String location) {
        String normalized = trimTrailingSlashes(location.trim());
        return normalized + "/**/SKILL.md";
    }

    private String trimTrailingSlashes(String value) {
        String normalized = value;
        int colon = normalized.indexOf(':');
        int minimumLength = colon >= 0 ? colon + 2 : 0;
        while (normalized.endsWith("/") && normalized.length() > minimumLength) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeEntryPrefix(String prefix) {
        String normalized = prefix == null ? "" : prefix.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "" : normalized + "/";
    }

    private static String lastPathSegment(String path) {
        String normalized = path.replace('\\', '/');
        int end = normalized.length();
        while (end > 0 && normalized.charAt(end - 1) == '/') {
            end--;
        }
        int slash = normalized.lastIndexOf('/', end - 1);
        return normalized.substring(slash + 1, end);
    }

    private static void validateRelativePath(String path) {
        if (path == null || path.isBlank()
            || path.startsWith("/")
            || path.contains("\\")
            || path.contains("\u0000")
            || path.contains(":")
            || ENCODED_ESCAPE.matcher(path).find()) {
            throw new SkillDefinitionException("Skill resource 相对路径非法: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new SkillDefinitionException("Skill resource 相对路径非法: " + path);
            }
        }
    }

    private static void validateReferenceGlob(String pattern) {
        if (pattern == null
            || !REFERENCE_GLOB.matcher(pattern).matches()
            || pattern.contains("..")
            || pattern.contains("%")
            || pattern.contains(":")
            || pattern.contains("\\")
            || pattern.startsWith("/")) {
            throw new SkillDefinitionException("Skill resource glob 非法: " + pattern);
        }
        String directory = pattern.substring(
            0,
            pattern.length() - "/*.md".length()
        );
        for (String segment : directory.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new SkillDefinitionException("Skill resource glob 非法: " + pattern);
            }
        }
    }

    public static final class SkillResource {
        private final String skillName;
        private final Resource skillDocument;
        private final String source;
        private final ResourceScope scope;

        private SkillResource(
            String skillName,
            Resource skillDocument,
            String source,
            ResourceScope scope
        ) {
            this.skillName = skillName;
            this.skillDocument = skillDocument;
            this.source = source;
            this.scope = scope;
        }

        public String skillName() {
            return skillName;
        }

        public Resource skillDocument() {
            return skillDocument;
        }

        public String source() {
            return source;
        }

        private ResourceScope scope() {
            return scope;
        }

        private String identity() {
            return source;
        }
    }

    private interface ResourceScope {
        Resource resolve(String relativePath) throws IOException;

        Resource[] find(String relativePattern) throws IOException;
    }

    private static final class FileScope implements ResourceScope {
        private final Path skillRoot;
        private final Path realSkillRoot;

        private FileScope(Path skillRoot, Path allowedRoot) throws IOException {
            this.skillRoot = skillRoot.toAbsolutePath().normalize();
            Path realAllowedRoot = allowedRoot.toAbsolutePath().normalize().toRealPath();
            this.realSkillRoot = this.skillRoot.toRealPath();
            if (!realSkillRoot.startsWith(realAllowedRoot)) {
                throw new IOException("filesystem Skill root escaped configured location");
            }
        }

        @Override
        public Resource resolve(String relativePath) throws IOException {
            Path candidate = safePath(relativePath);
            return new org.springframework.core.io.FileSystemResource(candidate);
        }

        @Override
        public Resource[] find(String relativePattern) throws IOException {
            String prefix = relativePattern.substring(
                0,
                relativePattern.length() - "*.md".length()
            );
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            Path directory = safePath(prefix);
            if (!Files.isDirectory(directory)) {
                return new Resource[0];
            }
            try (var paths = Files.list(directory)) {
                return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(path -> {
                        try {
                            safePath(skillRoot.relativize(path).toString().replace('\\', '/'));
                            return new org.springframework.core.io.FileSystemResource(path);
                        } catch (IOException e) {
                            throw new SkillDefinitionException(
                                "filesystem reference 超出 Skill root: " + path, e);
                        }
                    })
                    .toArray(Resource[]::new);
            }
        }

        private Path safePath(String relativePath) throws IOException {
            validateRelativePath(
                relativePath.endsWith("/") ? relativePath.substring(0, relativePath.length() - 1)
                    : relativePath
            );
            Path candidate = skillRoot.resolve(relativePath).normalize();
            if (!candidate.startsWith(skillRoot)) {
                throw new IOException("filesystem resource escaped Skill root");
            }
            Path existing = candidate;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing != null && !existing.toRealPath().startsWith(realSkillRoot)) {
                throw new IOException("filesystem resource escaped Skill root through symlink");
            }
            return candidate;
        }
    }

    private static final class JarScope implements ResourceScope {
        private final URL jarUrl;
        private final String entryPrefix;

        private JarScope(URL jarUrl, String entryPrefix) {
            this.jarUrl = jarUrl;
            this.entryPrefix = normalizeEntryPrefix(entryPrefix);
        }

        @Override
        public Resource resolve(String relativePath) throws IOException {
            return new UrlResource("jar:" + jarUrl + "!/" + entryPrefix + relativePath);
        }

        @Override
        public Resource[] find(String relativePattern) throws IOException {
            String directory = relativePattern.substring(
                0,
                relativePattern.length() - "*.md".length()
            );
            String normalizedDirectory = directory.endsWith("/")
                ? directory
                : directory + "/";
            List<Resource> resources = new ArrayList<>();
            try (JarFile jar = openJar()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory()
                        && name.startsWith(entryPrefix + normalizedDirectory)
                        && name.endsWith(".md")
                        && name.indexOf('/', (entryPrefix + normalizedDirectory).length()) < 0) {
                        resources.add(new UrlResource("jar:" + jarUrl + "!/" + name));
                    }
                }
            }
            resources.sort(Comparator.comparing(Resource::getDescription));
            return resources.toArray(Resource[]::new);
        }

        private JarFile openJar() throws IOException {
            return openFileJar(jarUrl);
        }

        private static String normalizeEntryPrefix(String prefix) {
            String normalized = prefix == null ? "" : prefix;
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized.isEmpty() ? "" : normalized + "/";
        }
    }

    /**
     * Delegates container-specific JAR URLs, such as Spring Boot's {@code jar:nested:},
     * to Spring's resource infrastructure. The Skill document remains the relative
     * resolution base, so related resources cannot drift to another classpath source.
     */
    private static final class ResolverScope implements ResourceScope {
        private final Resource skillDocument;
        private final ResourcePatternResolver resolver;
        private final String baseUrl;

        private ResolverScope(
            Resource skillDocument,
            ResourcePatternResolver resolver
        ) throws IOException {
            this.skillDocument = skillDocument;
            this.resolver = resolver;
            String documentUrl = skillDocument.getURL().toExternalForm();
            if (!documentUrl.endsWith("SKILL.md")) {
                throw new IOException("资源不是 Skill 文档: " + documentUrl);
            }
            this.baseUrl = documentUrl.substring(
                0,
                documentUrl.length() - "SKILL.md".length()
            );
        }

        @Override
        public Resource resolve(String relativePath) throws IOException {
            return skillDocument.createRelative(relativePath);
        }

        @Override
        public Resource[] find(String relativePattern) throws IOException {
            Resource[] resources = resolver.getResources(baseUrl + relativePattern);
            java.util.Arrays.sort(
                resources,
                Comparator.comparing(Resource::getDescription)
            );
            return resources;
        }
    }

    private static JarFile openFileJar(URL jarUrl) throws IOException {
        if (!"file".equals(jarUrl.getProtocol())) {
            throw new IOException("只支持 file JAR URL: " + jarUrl);
        }
        try {
            return new JarFile(Path.of(jarUrl.toURI()).toFile(), false);
        } catch (URISyntaxException e) {
            throw new IOException("JAR URL 不是有效的文件 URI: " + jarUrl, e);
        }
    }
}
