package com.arkham.engine.view;

import com.arkham.engine.model.HandCard;

import java.util.List;

/**
 * Another player's investigator as seen by you — hand <em>contents</em> hidden, only
 * {@code handCount} exposed. Assets in play are public, so {@code inPlay} is not.
 * Mirrors {@code OtherInvestigatorView} in protocol/messages.ts.
 */
public record OtherInvestigatorView(
        String investigatorId,
        String locationId,
        int damage,
        int horror,
        int handCount,
        List<HandCard> inPlay,
        boolean turnTaken,
        String elimination) {}
