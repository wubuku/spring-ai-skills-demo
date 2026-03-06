package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private SkillMeta meta;
    private String body;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMeta {
        private String name;
        private String description;
        private List<SkillLink> links;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillLink {
        private String name;
        private String description;
    }
}
