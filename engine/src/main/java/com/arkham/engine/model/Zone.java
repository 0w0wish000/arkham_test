package com.arkham.engine.model;

/**
 * Where a card instance currently lives. Not sent over the wire directly, but the
 * engine uses it to track card movement (docs/05 §9 data model).
 */
public enum Zone {
    DECK,
    HAND,
    IN_PLAY,
    DISCARD,
    THREAT_AREA,
    VICTORY_DISPLAY,
    /** Transient state while an event/treachery/skill resolves (docs/05 §10.13). */
    LIMBO
}
