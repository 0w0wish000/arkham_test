package com.arkham.engine.model;

import java.util.List;

/**
 * The public projection of a card in hand. Mirrors {@code HandCard} in
 * protocol/messages.ts: {@code { cardId, name, skillIcons }}. This is the only
 * card shape ever sent to a client (in the owner's {@code SelfView.hand} or in a
 * COMMIT_CARDS choice's {@code eligibleCards}).
 */
public record HandCard(String cardId, String name, List<SkillIcon> skillIcons) {
    public HandCard {
        skillIcons = List.copyOf(skillIcons);
    }
}
