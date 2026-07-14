package com.arkham.engine.view;

/**
 * Another player's investigator as seen by you — hand <em>contents</em> hidden,
 * only {@code handCount} exposed. Mirrors {@code OtherInvestigatorView} in
 * protocol/messages.ts.
 */
public record OtherInvestigatorView(
        String investigatorId,
        String locationId,
        int damage,
        int horror,
        int handCount,
        boolean turnDone) {}
