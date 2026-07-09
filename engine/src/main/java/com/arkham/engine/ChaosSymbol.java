package com.arkham.engine;

/**
 * The symbol chaos tokens (docs/05 §6). The four "scenario" symbols
 * (SKULL/CULTIST/TABLET/ELDER_THING) resolve via the scenario reference card;
 * ELDER_SIGN resolves via the investigator; AUTOFAIL forces the skill value to 0.
 * Ported from the prototype's {@code SYMBOL} map.
 */
public enum ChaosSymbol {
    SKULL,
    CULTIST,
    TABLET,
    ELDER_THING,
    AUTOFAIL,
    ELDER_SIGN
}
