package com.arkham.engine;

/**
 * A chaos token: either a numeric modifier or a symbol. Ported from the prototype's
 * bag entries ({@code {v:n}} vs {@code {s:'skull'}}).
 */
public sealed interface ChaosToken permits ChaosToken.Numeric, ChaosToken.Symbol {

    /** A numeric modifier token, e.g. +1, 0, -1 … -8. */
    record Numeric(int value) implements ChaosToken {}

    /** A symbol token; its skill-test effect is resolved by {@link com.arkham.engine.SkillTest}. */
    record Symbol(ChaosSymbol symbol) implements ChaosToken {}

    static ChaosToken numeric(int value) { return new Numeric(value); }
    static ChaosToken symbol(ChaosSymbol symbol) { return new Symbol(symbol); }
}
