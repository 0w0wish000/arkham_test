package com.arkham.engine.view;

import java.util.List;

/**
 * The per-client filtered snapshot of the game. Mirrors {@code GameStateView} in
 * protocol/messages.ts field-for-field, so serialising this with Jackson produces
 * exactly the JSON the TypeScript client expects.
 *
 * <p>Filtering (done by {@link com.arkham.engine.RulesEngine#viewFor}): {@code you}
 * holds the recipient's full hand; {@code otherInvestigators} expose only hand counts;
 * the encounter-deck order is never included; the chaos bag is summarised to a count.
 */
public record GameStateView(
        int round,
        String phase,
        String activeInvestigatorId,
        SelfView you,
        List<OtherInvestigatorView> otherInvestigators,
        List<LocationView> locations,
        List<EnemyView> enemies,
        ActView act,
        AgendaView agenda,
        ChaosBagSummary chaosBagSummary) {}
