package com.arkham.engine.model;

import java.util.List;

/**
 * A concrete card owned by an investigator (deck / hand / in-play / discard). Richer
 * than the wire-level {@link HandCard}: it also carries the play cost, the card type
 * and any static bonus the card grants while in play.
 *
 * @param cardId      stable per-game identifier (e.g. "joe-c1")
 * @param name        printed title
 * @param cardType    skill / asset / event / weakness
 * @param cost        resource cost to play (0 for skills and weaknesses)
 * @param weaponBonus extra damage this asset adds to a successful Fight while in play
 * @param skillIcons  icons used when committed to a skill test
 */
public record CardInstance(String cardId, String name, CardType cardType, int cost,
                           int weaponBonus, List<SkillIcon> skillIcons) {

    public CardInstance {
        skillIcons = List.copyOf(skillIcons);
    }

    public static CardInstance skill(String cardId, String name, SkillIcon... icons) {
        return new CardInstance(cardId, name, CardType.SKILL, 0, 0, List.of(icons));
    }

    /** An asset with no combat bonus (e.g. an ally or a tome). */
    public static CardInstance asset(String cardId, String name, int cost, SkillIcon... icons) {
        return new CardInstance(cardId, name, CardType.ASSET, cost, 0, List.of(icons));
    }

    /** A weapon asset: while in play it adds {@code weaponBonus} damage to a won Fight. */
    public static CardInstance weapon(String cardId, String name, int cost, int weaponBonus,
                                      SkillIcon... icons) {
        return new CardInstance(cardId, name, CardType.ASSET, cost, weaponBonus, List.of(icons));
    }

    public static CardInstance event(String cardId, String name, int cost, SkillIcon... icons) {
        return new CardInstance(cardId, name, CardType.EVENT, cost, 0, List.of(icons));
    }

    /** A weakness: set aside and redrawn during the setup draw; never playable/committable. */
    public static CardInstance weakness(String cardId, String name) {
        return new CardInstance(cardId, name, CardType.WEAKNESS, 0, 0, List.of());
    }

    public boolean isWeakness() {
        return cardType == CardType.WEAKNESS;
    }

    /** Number of icons on this card that count toward a test of {@code skill}. */
    public int matchingIcons(SkillType skill) {
        return (int) skillIcons.stream().filter(ic -> ic.matches(skill)).count();
    }

    /** Weaknesses can never be committed, whatever icons they might carry. */
    public boolean eligibleFor(SkillType skill) {
        return !isWeakness() && skillIcons.stream().anyMatch(ic -> ic.matches(skill));
    }

    /** Project to the wire-level card shape. */
    public HandCard toHandCard() {
        return new HandCard(cardId, name, cardType, cost, skillIcons);
    }
}
