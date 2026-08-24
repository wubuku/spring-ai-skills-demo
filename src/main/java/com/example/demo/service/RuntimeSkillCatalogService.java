package com.example.demo.service;

import com.example.demo.agent.SkillRegistry;
import com.example.demo.agent.SkillRegistry.ApiIndexEntry;
import com.example.demo.dto.RuntimeSkillApiEntry;
import com.example.demo.dto.RuntimeSkillDetail;
import com.example.demo.dto.RuntimeSkillLink;
import com.example.demo.dto.RuntimeSkillSummary;
import com.example.demo.model.Skill;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runtime Skill 的只读观察目录。
 *
 * 这里仅做 SkillRegistry domain object 到公开 DTO 的映射，不重新解析
 * frontmatter、Markdown 或 references。
 */
@Service
public class RuntimeSkillCatalogService {

    private final SkillRegistry skillRegistry;

    public RuntimeSkillCatalogService(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public List<RuntimeSkillSummary> summaries() {
        Map<String, List<ApiIndexEntry>> entriesBySkill = entriesBySkill();
        List<RuntimeSkillSummary> summaries = new ArrayList<>();

        skillRegistry.all().forEach((name, skill) -> {
            List<ApiIndexEntry> entries = entriesBySkill.getOrDefault(name, List.of());
            summaries.add(toSummary(name, skill, entries));
        });
        return List.copyOf(summaries);
    }

    public RuntimeSkillDetail detail(String name) {
        Skill skill = skillRegistry.get(name)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Skill 不存在: " + name));
        Map<String, List<ApiIndexEntry>> entriesBySkill = entriesBySkill();
        List<ApiIndexEntry> entries = entriesBySkill.getOrDefault(name, List.of());
        List<RuntimeSkillApiEntry> apis = entries.stream()
            .map(this::toApiEntry)
            .toList();
        RuntimeSkillSummary summary = toSummary(name, skill, entries);
        return new RuntimeSkillDetail(
            summary.name(),
            summary.description(),
            summary.version(),
            summary.links(),
            summary.hierarchical(),
            summary.apiCount(),
            skill.getBody(),
            apis
        );
    }

    public Map<String, RuntimeSkillApiEntry> apiIndex() {
        LinkedHashMap<String, RuntimeSkillApiEntry> result = new LinkedHashMap<>();
        skillRegistry.getApiIndex().forEach((key, entry) ->
            result.put(key, toApiEntry(entry)));
        return Collections.unmodifiableMap(result);
    }

    private RuntimeSkillSummary toSummary(
        String name,
        Skill skill,
        List<ApiIndexEntry> entries
    ) {
        List<RuntimeSkillLink> links = skill.getMeta().getLinks() == null
            ? List.of()
            : skill.getMeta().getLinks().stream()
                .map(link -> new RuntimeSkillLink(link.getName(), link.getDescription()))
                .toList();
        boolean hierarchical = entries.stream().anyMatch(ApiIndexEntry::isHierarchical);
        return new RuntimeSkillSummary(
            name,
            skill.getMeta().getDescription(),
            skill.getMeta().getVersion(),
            links,
            hierarchical,
            entries.size()
        );
    }

    private RuntimeSkillApiEntry toApiEntry(ApiIndexEntry entry) {
        return new RuntimeSkillApiEntry(
            entry.getSkillName(),
            entry.getPath(),
            entry.getMethod(),
            entry.getDescription(),
            entry.isHierarchical(),
            entry.getReferencePath()
        );
    }

    private Map<String, List<ApiIndexEntry>> entriesBySkill() {
        return skillRegistry.getApiIndex().values().stream()
            .collect(Collectors.groupingBy(
                ApiIndexEntry::getSkillName,
                LinkedHashMap::new,
                Collectors.toList()
            ));
    }
}
