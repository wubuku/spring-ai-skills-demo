package com.example.demo.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SkillLoadSession {

    private final Set<String> loadedSkills = new LinkedHashSet<>();

    public synchronized boolean markLoaded(String skillName) {
        return loadedSkills.add(skillName);
    }

    public synchronized List<String> loadedSkills() {
        return List.copyOf(loadedSkills);
    }
}
