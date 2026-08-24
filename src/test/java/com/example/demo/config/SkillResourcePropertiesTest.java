package com.example.demo.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillResourcePropertiesTest {

    @Test
    void defaultsToTheApplicationClasspathSkillLocation() {
        SkillResourceProperties properties = new SkillResourceProperties();

        assertThat(properties.getLocations()).containsExactly("classpath*:skills");
    }

    @Test
    void trimsAndFiltersConfiguredLocations() {
        SkillResourceProperties properties = new SkillResourceProperties();
        properties.setLocations(List.of(
            " classpath*:skills ",
            " ",
            "file:/opt/company-skills"
        ));

        assertThat(properties.getLocations())
            .containsExactly("classpath*:skills", "file:/opt/company-skills");
    }
}
