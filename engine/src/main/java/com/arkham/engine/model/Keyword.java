package com.arkham.engine.model;

/**
 * Enemy / card keywords. Mirrors the {@code Keyword} union in protocol/messages.ts.
 * Engine behaviour for each keyword is documented in docs/05 §5 (Keyword Registry);
 * only HUNTER, RETALIATE and ALERT carry behaviour in this lite scaffold.
 */
public enum Keyword {
    HUNTER,
    RETALIATE,
    ALERT,
    ALOOF,
    MASSIVE,
    ELUSIVE,
    PATROL,
    PREY,
    PERIL,
    FAST,
    SWARMING,
    SURGE,
    HIDDEN
}
