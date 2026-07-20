package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.arkham.engine.model.EncounterCard;
import com.arkham.engine.scenario.ScenarioFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A3-lite:遭遇牌堆開局以中央種子洗牌 —— 同種子可重現、不同種子順序不同。 */
class EncounterShuffleTest {

    private static List<String> orderFor(long seed) {
        RulesEngine eng = ScenarioFactory.newEngine(seed, List.of("joe_diamond"), "core", "STANDARD", null);
        return eng.state().getEncounterDeck().stream()
                .map(c -> c.type() == EncounterCard.Type.ENEMY ? c.defKey() : c.name())
                .toList();
    }

    @Test
    void shuffleIsSeededAndReproducible() {
        assertEquals(orderFor(42L), orderFor(42L), "同種子 → 同順序(存檔續玩可重現)");
        assertNotEquals(orderFor(42L), orderFor(1337L), "不同種子 → 不同順序(不再固定牌序)");
        assertEquals(6, orderFor(42L).size(), "牌堆內容不變,只換順序");
    }
}
