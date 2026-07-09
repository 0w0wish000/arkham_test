package com.arkham.engine.model;

import java.util.List;

/**
 * Immutable enemy definition (the printed card). {@link EnemyCard} instances are
 * spawned from these by the engine. Ported from the prototype's {@code ENEMY_DEF}.
 */
public record EnemyDef(String defKey, String name, int fight, int health, int evade,
                       int damage, int horror, List<Keyword> keywords) {

    public EnemyDef {
        keywords = List.copyOf(keywords);
    }
}
