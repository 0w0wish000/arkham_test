package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.SkillType;
import com.arkham.engine.rng.SeededRng;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the 8-step skill test (docs/05 §2). Tokens are made deterministic by using
 * single-token bags, so the assertions isolate the base + commit + token arithmetic.
 */
class SkillTestTest {

    private static ChaosBag bagOf(ChaosToken token) {
        return new ChaosBag(List.of(token));
    }

    /** Prototype example: Joe combat 3 vs Servant fight 4, token -1, no help ⇒ 3-1=2 < 4, fail. */
    @Test
    void unaidedTestFails() {
        SkillTest.Result r = SkillTest.run(
                SkillType.COMBAT, 3, 4, /*committedIcons*/ 0, bagOf(ChaosToken.numeric(-1)), new SeededRng(1));

        assertEquals(3, r.effectiveSkill());
        assertEquals(2, r.modifiedSkillValue());
        assertFalse(r.success());
    }

    /** Prototype example: same test but commit Overpower(+2) + ally Vicious Blow(+1) ⇒ 6-1=5 ≥ 4, success. */
    @Test
    void teammateCommitAddsToValueAndSucceeds() {
        SkillTest.Result r = SkillTest.run(
                SkillType.COMBAT, 3, 4, /*committedIcons*/ 3, bagOf(ChaosToken.numeric(-1)), new SeededRng(1));

        assertEquals(6, r.effectiveSkill());        // base 3 + committed 3
        assertEquals(5, r.modifiedSkillValue());    // 6 + (-1)
        assertTrue(r.success());                    // 5 ≥ 4
    }

    /** ⊗ auto-fail forces the modified skill value to 0 regardless of base/commit (docs/05 §2). */
    @Test
    void autofailForcesSkillValueZero() {
        SkillTest.Result r = SkillTest.run(
                SkillType.COMBAT, 9, 1, /*committedIcons*/ 5,
                bagOf(ChaosToken.symbol(ChaosSymbol.AUTOFAIL)), new SeededRng(1));

        assertTrue(r.autofail());
        assertEquals(0, r.modifiedSkillValue());
        assertFalse(r.success()); // even 14 potential ⇒ 0 < 1
    }

    /** ⭐ elder sign contributes +1 and is flagged for the investigator's ability. */
    @Test
    void elderSignAddsOneAndIsFlagged() {
        SkillTest.Result r = SkillTest.run(
                SkillType.INTELLECT, 4, 5, /*committedIcons*/ 0,
                bagOf(ChaosToken.symbol(ChaosSymbol.ELDER_SIGN)), new SeededRng(1));

        assertTrue(r.elderSign());
        assertEquals(5, r.modifiedSkillValue()); // 4 + 1
        assertTrue(r.success());
    }

    /** A positive numeric token can push a base test over the difficulty. */
    @Test
    void positiveTokenCanSucceed() {
        SkillTest.Result r = SkillTest.run(
                SkillType.INTELLECT, 4, 5, 0, bagOf(ChaosToken.numeric(1)), new SeededRng(1));

        assertEquals(5, r.modifiedSkillValue());
        assertTrue(r.success());
    }

    /** The modified skill value cannot be reduced below 0. */
    @Test
    void skillValueClampedAtZero() {
        SkillTest.Result r = SkillTest.run(
                SkillType.WILLPOWER, 2, 1, 0, bagOf(ChaosToken.numeric(-4)), new SeededRng(1));

        assertEquals(0, r.modifiedSkillValue()); // 2 - 4 = -2 clamped to 0
        assertFalse(r.success());
    }
}
