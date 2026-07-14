package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.EnemyCard;
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
 * 能力/時機引擎原型(docs/11 §B)單元測試 —— 皆為確定性(袋只放 +1,無隨機)。
 */
class AbilityEngineTest {

    /** 袋內只有 +1 → 檢定必成功,測試可重現。 */
    private static ChaosBag bagOfPlusOne() {
        return new ChaosBag(List.of(ChaosToken.numeric(1)));
    }

    private static GameState baseState(String investigatorId) {
        GameState state = new GameState(bagOfPlusOne(),
                new Act("測試", 99), new Agenda("測試", 99), Map.of(), List.of());
        state.addLocation(new LocationCard("loc", "測試地點", 1, 2, true, List.of(), false, null));
        Investigator inv = ScenarioFactory.buildInvestigator(investigatorId);
        inv.setLocationId("loc");
        inv.setActionsRemaining(3);
        state.addInvestigator(inv);
        state.setActiveInvestigatorId(investigatorId);
        state.location("loc").setClues(3);
        return state;
    }

    @Test
    void joeReactionAsksThenDrawsAndIsOncePerRound() {
        GameState state = baseState("joe_diamond");
        Investigator joe = state.investigator("joe_diamond");
        joe.getDeckPile().add(CardInstance.skill("t-d1", "Test Card A", SkillIcon.WILD));
        joe.getDeckPile().add(CardInstance.skill("t-d2", "Test Card B", SkillIcon.WILD));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        // 第一次成功調查 → 反應能力浮出(引擎暫停等擁有者回答)
        eng.applyIntent("joe_diamond", IntentAction.INVESTIGATE, Map.of());
        eng.resolveCommit(Map.of("joe_diamond", List.of()));   // +1 → 智力5 vs 遮蔽1 必成功
        assertTrue(eng.hasPendingOption(), "成功調查後應詢問 Joe 的反應能力");
        assertEquals("joe_diamond", eng.pendingOptionInfo().investigatorId());

        int handBefore = joe.getHand().size();
        eng.resolveOption(true);   // 使用 → 抽 1
        assertEquals(handBefore + 1, joe.getHand().size(), "使用能力應抽 1 張");
        assertFalse(eng.hasPendingOption());

        // 同一輪第二次成功調查 → 已用過(每回合限一次)→ 不再詢問
        eng.applyIntent("joe_diamond", IntentAction.INVESTIGATE, Map.of());
        eng.resolveCommit(Map.of("joe_diamond", List.of()));
        assertFalse(eng.hasPendingOption(), "每回合限一次:同輪不應再詢問");
    }

    @Test
    void joeSkipDoesNotDrawButStillCountsAsAnswered() {
        GameState state = baseState("joe_diamond");
        Investigator joe = state.investigator("joe_diamond");
        joe.getDeckPile().add(CardInstance.skill("t-d1", "Test Card A", SkillIcon.WILD));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.INVESTIGATE, Map.of());
        eng.resolveCommit(Map.of("joe_diamond", List.of()));
        assertTrue(eng.hasPendingOption());
        int handBefore = joe.getHand().size();
        eng.resolveOption(false);   // 跳過
        assertEquals(handBefore, joe.getHand().size(), "跳過不抽牌");
        assertFalse(eng.hasPendingOption());
    }

    @Test
    void danielaForcedCounterDamagesAttacker() {
        GameState state = baseState("daniela");
        // 一隻與 Daniela 交戰的敵人(戰2/命3/閃2,攻擊 1傷0懼)
        EnemyCard brute = new EnemyCard("e1", "dummy", "測試打手", 2, 3, 2, 1, 0, List.of(), "loc");
        brute.setEngagedWith("daniela");
        state.getEnemies().put("e1", brute);
        state.investigator("daniela").engage("e1");
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        // 強制全體結束 → 敵人階段:打手攻擊 Daniela → 強制能力自動反傷 1
        eng.applyIntent("daniela", IntentAction.END_TURN, Map.of("force", true));
        assertEquals(1, state.investigator("daniela").getDamage(), "Daniela 吃到 1 傷");
        assertEquals(1, brute.getDamageOn(), "強制能力自動對攻擊者反傷 1(不需詢問)");
        assertFalse(eng.hasPendingOption(), "強制能力不產生詢問");
    }
}
