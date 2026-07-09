package com.arkham.engine.model;

/**
 * An investigator's four base skill values. Serialises to
 * {@code { "willpower": n, "intellect": n, "combat": n, "agility": n }} —
 * i.e. the {@code SelfView.skills} object (a {@code Record<Lowercase<SkillType>, number>})
 * in protocol/messages.ts.
 */
public record Skills(int willpower, int intellect, int combat, int agility) {

    /** Look up a base skill by type. */
    public int of(SkillType type) {
        return switch (type) {
            case WILLPOWER -> willpower;
            case INTELLECT -> intellect;
            case COMBAT -> combat;
            case AGILITY -> agility;
        };
    }
}
