package com.arkham.engine;

import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.view.ChaosBagSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * A multiset of chaos tokens. A skill test draws one token (steps 3–4 of docs/05 §2)
 * and returns it afterward (step 8), so within a single test a draw is a uniform
 * pick <em>with replacement</em> — modelled here as a non-mutating {@link #draw}.
 *
 * <p>{@link #standard()} ports the prototype's {@code freshBag()}: the 16-token
 * Revised Core "Standard" bag.
 */
public final class ChaosBag {

    private final List<ChaosToken> tokens;

    @com.fasterxml.jackson.annotation.JsonCreator
    public ChaosBag(@com.fasterxml.jackson.annotation.JsonProperty("tokens") List<ChaosToken> tokens) {
        this.tokens = new ArrayList<>(tokens);
    }

    /**
     * The standard 16-token bag(官方規則書 p7):
     * +1, 0×2, -1×3, -2×2, -3, -4, skull×2, cultist, tablet, autofail, elder-sign.
     */
    public static ChaosBag standard() {
        return forDifficulty("STANDARD");
    }

    /**
     * 依難度組袋(docs/09 §12:基準難度影響混沌袋組成)。
     * STANDARD = 官方規則書 p7 首戰 16 顆:+1、0×2、-1×3、-2×2、-3、-4、
     * 骷髏×2、異教徒、石板、自動失敗、古老印記(2026-07 對照官方 PDF 核實)。
     * 其餘難度為核心盒難度曲線的 lite 近似(正式版依戰役指南);
     * 袋量刻意相異(EASY 14 / STANDARD 16 / HARD 17 / EXPERT 18),便於前端與測試辨識。
     */
    public static ChaosBag forDifficulty(String difficulty) {
        String d = difficulty == null ? "STANDARD" : difficulty.toUpperCase();
        List<ChaosToken> t = new ArrayList<>(18);
        switch (d) {
            case "EASY" ->     addNumeric(t, 1, 1, 0, 0, 0, -1, -1, -2);                      // 8 數值
            case "HARD" ->     addNumeric(t, 0, 0, -1, -1, -2, -2, -3, -3, -4, -5, -6);       // 11 數值
            case "EXPERT" ->   addNumeric(t, 0, -1, -1, -2, -2, -3, -3, -4, -4, -5, -6, -8);  // 12 數值
            default ->         addNumeric(t, 1, 0, 0, -1, -1, -1, -2, -2, -3, -4);            // STANDARD 10 數值(官方)
        }
        t.add(ChaosToken.symbol(ChaosSymbol.SKULL));
        t.add(ChaosToken.symbol(ChaosSymbol.SKULL));
        t.add(ChaosToken.symbol(ChaosSymbol.CULTIST));
        t.add(ChaosToken.symbol(ChaosSymbol.TABLET));
        t.add(ChaosToken.symbol(ChaosSymbol.AUTOFAIL));
        t.add(ChaosToken.symbol(ChaosSymbol.ELDER_SIGN));
        return new ChaosBag(t);
    }

    private static void addNumeric(List<ChaosToken> t, int... values) {
        for (int v : values) {
            t.add(ChaosToken.numeric(v));
        }
    }

    /** Reveal a token using the central seeded RNG (uniform, with replacement). */
    public ChaosToken draw(SeededRng rng) {
        return tokens.get(rng.nextInt(tokens.size()));
    }

    public int size() {
        return tokens.size();
    }

    /** Immutable snapshot of the bag's contents (order is irrelevant). */
    @com.fasterxml.jackson.annotation.JsonProperty("tokens")
    public List<ChaosToken> tokens() {
        return List.copyOf(tokens);
    }

    /** Public wire summary: token count only. */
    public ChaosBagSummary summary() {
        return new ChaosBagSummary(tokens.size());
    }
}
