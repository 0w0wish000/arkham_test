package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.model.SkillIcon;
import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.scenario.ScenarioFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * B6/E3 整備手牌上限自選棄牌:超限記帳、擋本人行動(隊友不受影響)、
 * 張數驗證、離線自動棄後備、續玩重掃。
 */
class HandLimitTest {

    private static GameState state() {
        GameState st = new GameState(new ChaosBag(List.of(ChaosToken.numeric(1))),
                new Act("測試", 99), new Agenda("測試", 99), Map.of(), List.of());
        st.addLocation(new LocationCard("loc", "測試地點", 1, 2, true, List.of(), false, null));
        for (String id : List.of("joe_diamond", "daniela")) {
            Investigator inv = ScenarioFactory.buildInvestigator(id);
            inv.setLocationId("loc");
            inv.setActionsRemaining(3);
            inv.getHand().clear();
            st.addInvestigator(inv);
        }
        st.lockPlayerCount();
        st.setActiveInvestigatorId("joe_diamond");
        return st;
    }

    private static void stuffHand(Investigator inv, int n) {
        for (int i = 0; i < n; i++) {
            inv.getHand().add(CardInstance.skill(inv.getId() + "-h" + i, "手牌" + i, SkillIcon.WILD));
        }
    }

    @Test
    void overLimitBlocksOwnerUntilChosenDiscard() {
        GameState st = state();
        Investigator joe = st.investigator("joe_diamond");
        stuffHand(joe, 10);                       // 牌庫空 → 整備不再加抽
        RulesEngine eng = new RulesEngine(st, new SeededRng(1));
        eng.applyIntent("joe_diamond", IntentAction.END_TURN, Map.of("force", true));

        assertEquals(2, eng.pendingDiscardsView().get("joe_diamond"), "超限 10-8 → 欠棄 2");
        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.GAIN_RESOURCE, Map.of()),
                "欠棄者不能行動");
        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.END_TURN, Map.of()),
                "欠棄者不能宣告打完");
        // 隊友不受影響(自由順序)
        eng.applyIntent("daniela", IntentAction.GAIN_RESOURCE, Map.of());

        // 張數要剛好、卡要在手牌
        assertThrows(IllegalArgumentException.class,
                () -> eng.resolveDiscard("joe_diamond", List.of("joe_diamond-h0")));
        assertThrows(IllegalArgumentException.class,
                () -> eng.resolveDiscard("joe_diamond", List.of("沒這張", "joe_diamond-h0")));

        eng.resolveDiscard("joe_diamond", List.of("joe_diamond-h3", "joe_diamond-h7"));
        assertEquals(8, joe.getHand().size(), "棄到上限");
        assertTrue(joe.getHand().stream().noneMatch(c -> c.cardId().equals("joe_diamond-h3")), "棄的是玩家選的那兩張");
        assertFalse(eng.pendingDiscardsView().containsKey("joe_diamond"));
        eng.applyIntent("joe_diamond", IntentAction.GAIN_RESOURCE, Map.of());   // 解鎖後可行動
    }

    @Test
    void autoDiscardFallbackAndResumeRescan() {
        GameState st = state();
        Investigator joe = st.investigator("joe_diamond");
        stuffHand(joe, 9);
        // 續玩重掃:建引擎當下就欠棄(存檔時欠的照樣欠)
        RulesEngine eng = new RulesEngine(st, new SeededRng(1));
        assertEquals(1, eng.pendingDiscardsView().get("joe_diamond"), "續玩重掃出欠棄");

        // 離線逾時後備:自動棄最舊
        String oldest = joe.getHand().get(0).cardId();
        eng.autoDiscardIfPending("joe_diamond");
        assertEquals(8, joe.getHand().size());
        assertTrue(joe.getHand().stream().noneMatch(c -> c.cardId().equals(oldest)), "自動棄最舊");
        assertFalse(eng.pendingDiscardsView().containsKey("joe_diamond"));
    }
}
