package com.arkham.engine.model;

/**
 * Skill-test icons printed on cards. Mirrors {@code SkillIcon = SkillType | "WILD"}
 * in protocol/messages.ts. WILD (the wild / {@literal ⚡} icon) matches any skill.
 */
public enum SkillIcon {
    WILLPOWER,
    INTELLECT,
    COMBAT,
    AGILITY,
    WILD;

    /** True if this icon counts toward a test of the given skill (matching or wild). */
    public boolean matches(SkillType skill) {
        return this == WILD || this.name().equals(skill.name());
    }
}
