package com.arkham.engine;

/**
 * A chaos token: either a numeric modifier or a symbol. Ported from the prototype's
 * bag entries ({@code {v:n}} vs {@code {s:'skull'}}).
 */
// 存檔序列化/反序列化:以欄位推斷子型別(Numeric 有 value、Symbol 有 symbol),不需額外 type 欄位。
@com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.DEDUCTION)
@com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(ChaosToken.Numeric.class),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(ChaosToken.Symbol.class)
})
public sealed interface ChaosToken permits ChaosToken.Numeric, ChaosToken.Symbol {

    /** A numeric modifier token, e.g. +1, 0, -1 … -8. */
    record Numeric(int value) implements ChaosToken {}

    /** A symbol token; its skill-test effect is resolved by {@link com.arkham.engine.SkillTest}. */
    record Symbol(ChaosSymbol symbol) implements ChaosToken {}

    static ChaosToken numeric(int value) { return new Numeric(value); }
    static ChaosToken symbol(ChaosSymbol symbol) { return new Symbol(symbol); }
}
