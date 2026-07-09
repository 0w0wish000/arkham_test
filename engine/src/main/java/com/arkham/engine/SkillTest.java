package com.arkham.engine;

import com.arkham.engine.model.SkillType;
import com.arkham.engine.rng.SeededRng;

/**
 * The core skill-test loop, docs/05 §2 (8 steps), ported from the prototype's
 * {@code runSkillTest}. This class is <strong>pure and deterministic</strong>: given the
 * same inputs and the same RNG state it always returns the same {@link Result}.
 *
 * <p>The multi-client "commit barrier" (docs/05 §2.1) lives in
 * {@link RulesEngine}; by the time {@link #run} is called the committed matching/wild
 * icon count from every participant has already been summed into
 * {@code committedIcons}.
 *
 * <pre>
 *   Step 1  determine skill .................. caller supplies {@code skill}
 *   Step 2  commit cards ..................... {@code effectiveSkill = baseSkill + committedIcons}
 *   Step 3  reveal token ..................... {@code bag.draw(rng)}
 *   Step 4  resolve symbol ................... {@link #resolveSymbol}
 *   Step 5  modified skill value ............. autofail ⇒ 0, else max(0, effectiveSkill + mod)
 *   Step 6  success / failure ................ modified ≥ difficulty
 *   Step 7  apply result ..................... caller ({@link RulesEngine})
 *   Step 8  end test (return token to bag) ... no-op (draw is non-mutating)
 * </pre>
 */
public final class SkillTest {

    private SkillTest() {}

    /**
     * The outcome of one test.
     *
     * @param skill              tested skill
     * @param baseSkill          investigator's base skill value
     * @param committedIcons     summed matching/wild icons committed (performer + allies)
     * @param difficulty         the value to meet or beat
     * @param token              the revealed chaos token
     * @param tokenModifier      the numeric modifier applied (0 when autofail)
     * @param autofail           true if the auto-fail (⊗) symbol was revealed
     * @param elderSign          true if the elder-sign (⭐) symbol was revealed
     * @param modifiedSkillValue the final skill value after the token (step 5)
     * @param success            modifiedSkillValue ≥ difficulty and not autofail (step 6)
     */
    public record Result(
            SkillType skill,
            int baseSkill,
            int committedIcons,
            int difficulty,
            ChaosToken token,
            int tokenModifier,
            boolean autofail,
            boolean elderSign,
            int modifiedSkillValue,
            boolean success) {

        public int effectiveSkill() {
            return baseSkill + committedIcons;
        }
    }

    /**
     * Run a full skill test.
     *
     * @param skill          the skill being tested (step 1)
     * @param baseSkill      the investigator's base value for that skill
     * @param difficulty     the difficulty to beat (shroud / fight / evade)
     * @param committedIcons total matching/wild icons committed across all participants
     * @param bag            the chaos bag to draw from
     * @param rng            the central seeded RNG
     */
    public static Result run(SkillType skill, int baseSkill, int difficulty,
                             int committedIcons, ChaosBag bag, SeededRng rng) {
        int effectiveSkill = baseSkill + committedIcons;          // step 2
        ChaosToken token = bag.draw(rng);                         // step 3

        Symbolic sym = resolveSymbol(token);                      // step 4
        int modified = sym.autofail()                             // step 5
                ? 0
                : Math.max(0, effectiveSkill + sym.modifier());   // skill value can't drop below 0
        boolean success = !sym.autofail() && modified >= difficulty; // step 6

        return new Result(
                skill, baseSkill, committedIcons, difficulty, token,
                sym.autofail() ? 0 : sym.modifier(), sym.autofail(), sym.elderSign(),
                modified, success);
    }

    /** Resolution of a revealed token into (modifier, autofail, elderSign). */
    private record Symbolic(int modifier, boolean autofail, boolean elderSign) {}

    /**
     * Step 4 — resolve a token's effect. Numeric tokens apply their value directly;
     * symbol modifiers use the lite scenario's reference-card values (prototype {@code SYMBOL}).
     * A real scenario would override these per its reference card (docs/05 §6).
     */
    private static Symbolic resolveSymbol(ChaosToken token) {
        return switch (token) {
            case ChaosToken.Numeric n -> new Symbolic(n.value(), false, false);
            case ChaosToken.Symbol s -> switch (s.symbol()) {
                case AUTOFAIL    -> new Symbolic(0, true, false);
                case ELDER_SIGN  -> new Symbolic(+1, false, true);
                case SKULL       -> new Symbolic(-2, false, false);
                case CULTIST     -> new Symbolic(-1, false, false);
                case TABLET      -> new Symbolic(-3, false, false);
                case ELDER_THING -> new Symbolic(-2, false, false);
            };
        };
    }
}
