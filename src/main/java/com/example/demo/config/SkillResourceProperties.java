package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime Skill resource roots.
 *
 * The roots are deployment configuration. They are never taken from model input.
 */
@ConfigurationProperties(prefix = "app.skills")
public class SkillResourceProperties {

    private List<String> locations = new ArrayList<>(List.of("classpath*:skills"));

    public SkillResourceProperties() {
    }

    public SkillResourceProperties(List<String> locations) {
        setLocations(locations);
    }

    public List<String> getLocations() {
        return List.copyOf(locations);
    }

    public void setLocations(List<String> locations) {
        if (locations == null) {
            this.locations = new ArrayList<>();
            return;
        }
        this.locations = locations.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(location -> !location.isEmpty())
            .toList();
    }
}
