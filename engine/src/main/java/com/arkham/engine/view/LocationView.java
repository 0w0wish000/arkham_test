package com.arkham.engine.view;

import java.util.List;

/**
 * A location as sent to clients. Mirrors {@code LocationView} in protocol/messages.ts.
 * {@code enemyIds} are computed from enemy positions at view time.
 */
public record LocationView(
        String id,
        String name,
        boolean revealed,
        int shroud,
        int clues,
        List<String> connections,
        List<String> enemyIds,
        boolean victory) {}
