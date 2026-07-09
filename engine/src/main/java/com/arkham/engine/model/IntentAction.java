package com.arkham.engine.model;

/**
 * Player intents accepted by {@link com.arkham.engine.RulesEngine#applyIntent}.
 * Mirrors {@code IntentAction} in protocol/messages.ts (docs/05 §4).
 * PLAY_CARD and ACTIVATE are part of the contract but not implemented in this
 * lite scaffold.
 */
public enum IntentAction {
    MOVE,
    INVESTIGATE,
    FIGHT,
    EVADE,
    ENGAGE,
    PLAY_CARD,
    ACTIVATE,
    END_TURN,
    ADVANCE_ACT
}
