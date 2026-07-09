package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.rng.SeededRng;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChaosBagTest {

    @Test
    void standardBagHasSixteenTokens() {
        assertEquals(16, ChaosBag.standard().size());
    }

    @Test
    void standardBagCompositionMatchesPrototypeFreshBag() {
        List<ChaosToken> tokens = ChaosBag.standard().tokens();

        assertEquals(2, countNumeric(tokens, 1));
        assertEquals(2, countNumeric(tokens, 0));
        assertEquals(3, countNumeric(tokens, -1));
        assertEquals(2, countNumeric(tokens, -2));
        assertEquals(1, countNumeric(tokens, -3));
        assertEquals(1, countNumeric(tokens, -4));

        assertEquals(2, countSymbol(tokens, ChaosSymbol.SKULL));
        assertEquals(1, countSymbol(tokens, ChaosSymbol.CULTIST));
        assertEquals(1, countSymbol(tokens, ChaosSymbol.AUTOFAIL));
        assertEquals(1, countSymbol(tokens, ChaosSymbol.ELDER_SIGN));

        // 11 numeric + 5 symbol = 16
        assertEquals(11, tokens.stream().filter(t -> t instanceof ChaosToken.Numeric).count());
        assertEquals(5, tokens.stream().filter(t -> t instanceof ChaosToken.Symbol).count());
    }

    @Test
    void seededDrawIsDeterministic() {
        ChaosBag a = ChaosBag.standard();
        ChaosBag b = ChaosBag.standard();
        SeededRng ra = new SeededRng(42);
        SeededRng rb = new SeededRng(42);

        List<ChaosToken> drawsA = new ArrayList<>();
        List<ChaosToken> drawsB = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            drawsA.add(a.draw(ra));
            drawsB.add(b.draw(rb));
        }
        // Same seed ⇒ identical sequence (records compare by value).
        assertEquals(drawsA, drawsB);
    }

    @Test
    void differentSeedsGenerallyDiverge() {
        ChaosBag bag = ChaosBag.standard();
        SeededRng r1 = new SeededRng(1);
        SeededRng r2 = new SeededRng(2);

        List<ChaosToken> s1 = new ArrayList<>();
        List<ChaosToken> s2 = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            s1.add(bag.draw(r1));
            s2.add(bag.draw(r2));
        }
        assertNotEquals(s1, s2);
    }

    @Test
    void drawDoesNotDepleteTheBag() {
        ChaosBag bag = ChaosBag.standard();
        SeededRng rng = new SeededRng(7);
        for (int i = 0; i < 50; i++) {
            bag.draw(rng);
        }
        assertEquals(16, bag.size()); // draw is with replacement (token returns in step 8)
        assertTrue(bag.size() > 0);
    }

    private static long countNumeric(List<ChaosToken> tokens, int value) {
        return tokens.stream()
                .filter(t -> t instanceof ChaosToken.Numeric n && n.value() == value)
                .count();
    }

    private static long countSymbol(List<ChaosToken> tokens, ChaosSymbol symbol) {
        return tokens.stream()
                .filter(t -> t instanceof ChaosToken.Symbol s && s.symbol() == symbol)
                .count();
    }
}
