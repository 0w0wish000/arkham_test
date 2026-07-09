package com.arkham.engine.model;

/**
 * The four investigator skills. Mirrors {@code SkillType} in protocol/messages.ts.
 * The lower-cased names are also the JSON keys of {@code SelfView.skills}.
 */
public enum SkillType {
    WILLPOWER,
    INTELLECT,
    COMBAT,
    AGILITY
}
