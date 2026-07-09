package com.arkham.engine.model;

/**
 * A card in the encounter deck drawn during the Mythos phase. Ported from the
 * prototype's {@code encounterDeck}. Kept as a flat record for simplicity:
 * {@code type == ENEMY} uses {@code defKey}; {@code type == TREACHERY} uses
 * {@code name} + {@code effect} + {@code amount}.
 */
public record EncounterCard(Type type, String defKey, String name, Effect effect, int amount) {

    public enum Type { ENEMY, TREACHERY }

    /** Treachery effects present in the lite scenario. */
    public enum Effect { HORROR, DOOM, NOTHING }

    public static EncounterCard enemy(String defKey) {
        return new EncounterCard(Type.ENEMY, defKey, null, null, 0);
    }

    public static EncounterCard treachery(String name, Effect effect, int amount) {
        return new EncounterCard(Type.TREACHERY, null, name, effect, amount);
    }
}
