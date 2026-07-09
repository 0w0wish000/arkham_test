package com.arkham.engine.model;

import java.util.List;

/**
 * A concrete card owned by an investigator (deck / hand / discard). Richer than the
 * wire-level {@link HandCard}: it also carries the play cost and card type used by
 * the engine. Skill cards in this scaffold are only ever committed to tests, never
 * "played" (docs/05 §4).
 *
 * @param cardId     stable per-game identifier (e.g. "c1")
 * @param name       printed title
 * @param cardType   "skill" | "asset" | "event" (informational in this scaffold)
 * @param cost       resource cost to play (0 for skills)
 * @param skillIcons icons used when committed to a skill test
 */
public record CardInstance(String cardId, String name, String cardType, int cost, List<SkillIcon> skillIcons) {

    public CardInstance {
        skillIcons = List.copyOf(skillIcons);
    }

    /** Convenience for skill cards. */
    public static CardInstance skill(String cardId, String name, SkillIcon... icons) {
        return new CardInstance(cardId, name, "skill", 0, List.of(icons));
    }

    /** Number of icons on this card that count toward a test of {@code skill}. */
    public int matchingIcons(SkillType skill) {
        return (int) skillIcons.stream().filter(ic -> ic.matches(skill)).count();
    }

    public boolean eligibleFor(SkillType skill) {
        return skillIcons.stream().anyMatch(ic -> ic.matches(skill));
    }

    /** Project to the wire-level card shape. */
    public HandCard toHandCard() {
        return new HandCard(cardId, name, skillIcons);
    }
}
