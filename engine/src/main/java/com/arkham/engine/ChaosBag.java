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

    public ChaosBag(List<ChaosToken> tokens) {
        this.tokens = new ArrayList<>(tokens);
    }

    /**
     * The standard 16-token bag:
     * -4, -3, -2×2, -1×3, 0×2, +1, cultist, skull×2, tablet, autofail, elder-sign.
     * Difficulty variants swap tokens, so a scenario may build its own bag instead.
     */
    public static ChaosBag standard() {
        List<ChaosToken> t = new ArrayList<>(16);
        t.add(ChaosToken.numeric(1));
        t.add(ChaosToken.numeric(0));
        t.add(ChaosToken.numeric(0));
        t.add(ChaosToken.numeric(-1));
        t.add(ChaosToken.numeric(-1));
        t.add(ChaosToken.numeric(-1));
        t.add(ChaosToken.numeric(-2));
        t.add(ChaosToken.numeric(-2));
        t.add(ChaosToken.numeric(-3));
        t.add(ChaosToken.numeric(-4));
        t.add(ChaosToken.symbol(ChaosSymbol.CULTIST));
        t.add(ChaosToken.symbol(ChaosSymbol.SKULL));
        t.add(ChaosToken.symbol(ChaosSymbol.SKULL));
        t.add(ChaosToken.symbol(ChaosSymbol.TABLET));
        t.add(ChaosToken.symbol(ChaosSymbol.AUTOFAIL));
        t.add(ChaosToken.symbol(ChaosSymbol.ELDER_SIGN));
        return new ChaosBag(t);
    }

    /** Reveal a token using the central seeded RNG (uniform, with replacement). */
    public ChaosToken draw(SeededRng rng) {
        return tokens.get(rng.nextInt(tokens.size()));
    }

    public int size() {
        return tokens.size();
    }

    /** Immutable snapshot of the bag's contents (order is irrelevant). */
    public List<ChaosToken> tokens() {
        return List.copyOf(tokens);
    }

    /** Public wire summary: token count only. */
    public ChaosBagSummary summary() {
        return new ChaosBagSummary(tokens.size());
    }
}
