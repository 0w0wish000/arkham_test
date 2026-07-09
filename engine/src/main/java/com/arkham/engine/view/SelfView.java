package com.arkham.engine.view;

import com.arkham.engine.model.HandCard;
import com.arkham.engine.model.Skills;

import java.util.List;

/**
 * The viewing player's own investigator — the only view that includes hand contents.
 * Mirrors {@code SelfView} in protocol/messages.ts. {@code health}/{@code sanity} are
 * maxima; {@code damage}/{@code horror} are marked amounts.
 */
public record SelfView(
        String investigatorId,
        Skills skills,
        int health,
        int damage,
        int sanity,
        int horror,
        int resources,
        int cluesHeld,
        int actionsRemaining,
        String locationId,
        List<HandCard> hand,
        List<String> engagedEnemyIds) {}
