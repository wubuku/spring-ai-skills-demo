package com.example.demo.agent;

/**
 * Raised when a runtime Skill document or API index entry violates the repository contract.
 */
public class SkillDefinitionException extends IllegalArgumentException {

    public SkillDefinitionException(String message) {
        super(message);
    }

    public SkillDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
