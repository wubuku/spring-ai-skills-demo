package com.example.demo.dto;

import java.util.List;

/**
 * Runtime Skill 的 Level 1 目录项。
 */
public record RuntimeSkillSummary(
    String name,
    String description,
    String version,
    List<RuntimeSkillLink> links,
    boolean hierarchical,
    int apiCount
) {
    public RuntimeSkillSummary {
        links = links == null ? List.of() : List.copyOf(links);
    }
}
