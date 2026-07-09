package com.arkham.engine.view;

/**
 * Public summary of the chaos bag sent to clients: only the token count, never the
 * contents order (the draw happens server-side, protocol.md). Serialises to
 * {@code { "total": n }} — the {@code chaosBagSummary} field of {@code GameStateView}.
 */
public record ChaosBagSummary(int total) {}
