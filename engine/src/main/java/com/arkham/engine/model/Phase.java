package com.arkham.engine.model;

/**
 * Round phases. Names mirror the {@code Phase} union in protocol/messages.ts
 * exactly so Jackson serialises them to the same JSON string literals.
 */
public enum Phase {
    MYTHOS,
    INVESTIGATION,
    ENEMY,
    UPKEEP
}
