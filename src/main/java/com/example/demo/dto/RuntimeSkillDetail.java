package com.example.demo.dto;

import java.util.List;

/**
 * Runtime Skill 的 Level 2 详情，以及指向 Level 3 reference 的 API 索引。
 */
public record RuntimeSkillDetail(
    String name,
    String description,
    String version,
    List<RuntimeSkillLink> links,
    boolean hierarchical,
    int apiCount,
    String body,
    List<RuntimeSkillApiEntry> apis
) {
    public RuntimeSkillDetail {
        links = links == null ? List.of() : List.copyOf(links);
        apis = apis == null ? List.of() : List.copyOf(apis);
    }
}
