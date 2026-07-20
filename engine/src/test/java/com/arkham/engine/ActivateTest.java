package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.arkham.engine.effect.EffectAtom;
import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.EnemyCard;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.scenario.CardCatalog;
import com.arkham.engine.scenario.ScenarioFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** C2 啟動行動:檯面限定、每輪限一次(新回合重置)、引發趁隙攻擊、效果走 DSL 原子。 */
class ActivateTest {

    private static GameState baseState() {
        GameState st = new GameState(new ChaosBag(List.of(ChaosToken.numeric(1))),
                new Act("測試", 99), new Agenda("測試", 99), Map.of(), List.of());
        st.addLocation(new LocationCard("loc", "測試地點", 1, 2, true, List.of(), false, null));
        Investigator inv = ScenarioFactory.buildInvestigator("joe_diamond");
        inv.setLocationId("loc");
        inv.setActionsRemaining(3);
        st.addInvestigator(inv);
        st.lockPlayerCount();
        st.setActiveInvestigatorId("joe_diamond");
        return st;
    }

    @Test
    void activateAppliesAtomsOncePerRoundAndResets() {
        GameState st = baseState();
        Investigator joe = st.investigator("joe_diamond");
        joe.getPlayArea().add(CardInstance.asset("kit", "Field Toolkit", 1));
        RulesEngine eng = new RulesEngine(st, new SeededRng(1));

        int res = joe.getResources();
        eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "kit"));
        assertEquals(res + 2, joe.getResources(), "啟動 → +2 資源");
        assertEquals(2, joe.getActionsRemaining(), "啟動花 1 行動");

        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "kit")),
                "每輪限一次");

        eng.applyIntent("joe_diamond", IntentAction.END_TURN, Map.of("force", true));   // 新回合重置
        int res2 = joe.getResources();
        eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "kit"));
        assertEquals(res2 + 2, joe.getResources(), "下一輪可再啟動");
    }

    @Test
    void guardsAndAttackOfOpportunity() {
        GameState st = baseState();
        Investigator joe = st.investigator("joe_diamond");
        joe.getHand().add(CardInstance.asset("h1", "Field Toolkit", 1));   // 只在手上,不在檯面
        joe.getPlayArea().add(CardInstance.asset("map", "Local Map", 1));  // 檯面但無啟動能力
        RulesEngine eng = new RulesEngine(st, new SeededRng(1));

        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "h1")),
                "手牌不能啟動(需在檯面)");
        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "map")),
                "無啟動能力的卡被擋");

        // 啟動引發趁隙攻擊(官方:非豁免行動)
        joe.getPlayArea().add(CardInstance.asset("kit", "Field Toolkit", 1));
        EnemyCard brute = new EnemyCard("e1", "dummy", "打手", 2, 3, 2, 1, 0, List.of(), "loc");
        brute.setEngagedWith("joe_diamond");
        st.getEnemies().put("e1", brute);
        joe.engage("e1");
        eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "kit"));
        assertEquals(1, joe.getDamage(), "啟動引發趁隙攻擊");
    }

    @Test
    void externalRegistrationWorks() {
        CardCatalog.register("測試治療儀", "asset", 0, List.of());
        CardCatalog.registerActivated("測試治療儀", new CardCatalog.Activated(
                List.of(new EffectAtom.Heal(2, 1)), false, "治 2 傷 1 懼"));
        GameState st = baseState();
        Investigator joe = st.investigator("joe_diamond");
        joe.takeDamage(3);
        joe.takeHorror(2);
        joe.getPlayArea().add(CardInstance.asset("dev", "測試治療儀", 0));
        RulesEngine eng = new RulesEngine(st, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "dev"));
        eng.applyIntent("joe_diamond", IntentAction.ACTIVATE, Map.of("cardId", "dev"));   // 無限次(oncePerRound=false)
        assertEquals(0, joe.getDamage(), "外部登記的啟動效果生效(治 2×2 ≥ 3)");
        assertEquals(0, joe.getHorror());
    }
}
