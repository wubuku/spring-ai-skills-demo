package com.example.demo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Runtime Skill 暴露的业务 API 索引条目。
 *
 * referencePath 只指出受限的 Level 3 资源位置，不直接返回资源正文。
 */
public record RuntimeSkillApiEntry(
    String skillName,
    String path,
    String method,
    String description,
    boolean hierarchical,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String referencePath
) {}
