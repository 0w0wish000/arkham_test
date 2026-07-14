package com.arkham.engine.model;

/**
 * Player intents accepted by {@link com.arkham.engine.RulesEngine#applyIntent}.
 * Mirrors {@code IntentAction} in protocol/messages.ts (docs/05 §4).
 *
 * <p>DRAW … RESIGN are the investigation-phase <em>actions</em> (three per turn).
 * {@link #MULLIGAN} (the one-time opening-hand adjustment), {@link #END_TURN} (pass to
 * the next investigator) and {@link #ADVANCE_ACT} (spend clues) cost no action.
 *
 * <p>Attacks of opportunity: every action provokes <b>except</b> FIGHT, EVADE, PARLEY
 * and RESIGN — see {@link com.arkham.engine.RulesEngine#PROVOKES_ATTACK_OF_OPPORTUNITY}.
 */
public enum IntentAction {
    DRAW,
    GAIN_RESOURCE,
    PLAY_CARD,
    ACTIVATE,
    MOVE,
    INVESTIGATE,
    ENGAGE,
    FIGHT,
    EVADE,
    PARLEY,
    RESIGN,
    MULLIGAN,
    END_TURN,
    ADVANCE_ACT
}
