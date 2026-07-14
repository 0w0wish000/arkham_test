package com.arkham.engine.model;

/**
 * Player-card types. Mirrors {@code CardType} in protocol/messages.ts.
 *
 * <ul>
 *   <li>{@code SKILL} — never played; only committed to a skill test.</li>
 *   <li>{@code ASSET} — played for its cost and stays in play.</li>
 *   <li>{@code EVENT} — played for its cost, resolves once, then discarded.</li>
 *   <li>{@code WEAKNESS} — set aside and redrawn during the setup draw; cannot be
 *       played or committed.</li>
 * </ul>
 */
public enum CardType {
    SKILL,
    ASSET,
    EVENT,
    WEAKNESS
}
