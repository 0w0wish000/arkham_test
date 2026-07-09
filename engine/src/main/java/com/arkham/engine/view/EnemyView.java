package com.arkham.engine.view;

import com.arkham.engine.model.Keyword;

import java.util.List;

/**
 * An enemy as sent to clients. Mirrors {@code EnemyView} in protocol/messages.ts.
 * {@code engagedWith} is the engaged investigator id, or {@code null} if unengaged
 * (serialises to JSON {@code null}).
 */
public record EnemyView(
        String id,
        String name,
        int fight,
        int health,
        int damageOn,
        int evade,
        List<Keyword> keywords,
        String engagedWith,
        boolean exhausted,
        String locationId) {}
