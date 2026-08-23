package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private SkillMeta meta;
    private String body;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillMeta {
        private String name;
        private String description;
        private String version;
        private List<SkillLink> links;
        private String license;
        private Map<String, Object> metadata;
        private Map<String, Object> additionalMetadata = new LinkedHashMap<>();

        @JsonAnySetter
        public void addAdditionalMetadata(String key, Object value) {
            additionalMetadata.put(key, value);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillLink {
        private String name;
        private String description;
    }
}
