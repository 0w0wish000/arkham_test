package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * 移植自 fix/rules-flow-conformance 的官方規則(docs/11)單元測試 —— 皆為確定性。
 * 涵蓋:撤退不減人數縮放、個別淘汰制、場上毀滅計數、開局手牌調整、
 * 打牌/交戰引發趁隙攻擊,以及「隊友投入上限 1 張」的設計守門(官方 p15,刻意不採該分支的放寬)。
 */
class PortedConformanceTest {

    private static ChaosBag bagOfPlusOne() {
        return new ChaosBag(List.of(ChaosToken.numeric(1)));
    }

    /** 兩人局:joe + daniela 同在已揭示的 loc;loc2 未揭示(線索值 2)。 */
    private static GameState twoPlayerState(int agendaThreshold) {
        GameState state = new GameState(bagOfPlusOne(),
                new Act("測試", 99), new Agenda("測試", agendaThreshold), Map.of(), List.of());
        state.addLocation(new LocationCard("loc", "起點", 1, 2, true, List.of("loc2"), false, null));
        state.addLocation(new LocationCard("loc2", "未揭示點", 1, 2, false, List.of("loc"), false, null));
        for (String id : List.of("joe_diamond", "daniela")) {
            Investigator inv = ScenarioFactory.buildInvestigator(id);
            inv.setLocationId("loc");
            inv.setActionsRemaining(3);
            state.addInvestigator(inv);
        }
        state.lockPlayerCount();   // 開局鎖定人數 = 2(之後撤退/淘汰不減)
        state.setActiveInvestigatorId("joe_diamond");
        return state;
    }

    private static EnemyCard enemyAt(GameState state, String id, String loc,
                                     int damage, String engagedWith) {
        EnemyCard e = new EnemyCard(id, "dummy", "測試敵人-" + id, 2, 3, 2, damage, 0, List.of(), loc);
        if (engagedWith != null) {
            e.setEngagedWith(engagedWith);
            state.investigator(engagedWith).engage(id);
        }
        state.getEnemies().put(id, e);
        return e;
    }

    // ------------------------------------------------------------------
    // 撤退(官方 p14)
    // ------------------------------------------------------------------

    @Test
    void resignHasNoAoOAndKeepsPlayerCountScaling() {
        GameState state = twoPlayerState(99);
        EnemyCard brute = enemyAt(state, "e1", "loc", 1, "joe_diamond");
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        // 撤退:不引發趁隙攻擊;交戰敵人解交戰留在地點
        eng.applyIntent("joe_diamond", IntentAction.RESIGN, Map.of());
        Investigator joe = state.investigator("joe_diamond");
        assertTrue(joe.isEliminated(), "撤退後應退場");
        assertEquals(Investigator.Elimination.RESIGNED, joe.getElimination());
        assertEquals(0, joe.getDamage(), "撤退不引發趁隙攻擊(官方:Resign 豁免)");
        assertNull(brute.getEngagedWith(), "退場者的交戰敵人應解除交戰");
        assertFalse(state.isGameOver(), "還有隊友在場,劇本繼續");

        // 人數縮放不變:daniela 揭示 loc2 → 線索 = 線索值2 × 開局人數2 = 4
        eng.applyIntent("daniela", IntentAction.MOVE, Map.of("toLocationId", "loc2"));
        assertEquals(2, state.getPlayerCount(), "撤退不減開局鎖定的人數");
        assertEquals(4, state.location("loc2").getClues(), "揭示線索 = 線索值 × 開局人數(不因撤退縮水)");
    }

    // ------------------------------------------------------------------
    // 個別淘汰制(官方 p41)
    // ------------------------------------------------------------------

    @Test
    void defeatDropsCluesUnengagesEnemyAndScenarioContinues() {
        GameState state = twoPlayerState(99);
        Investigator joe = state.investigator("joe_diamond");
        joe.gainClue();
        joe.gainClue();
        EnemyCard killer = enemyAt(state, "e1", "loc", 99, "joe_diamond");   // 一擊必殺
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        // 抽牌行動 → 趁隙攻擊 → joe 被擊敗(傷害)
        int handBefore = joe.getHand().size();
        eng.applyIntent("joe_diamond", IntentAction.DRAW, Map.of());
        assertEquals(Investigator.Elimination.DAMAGE, joe.getElimination(), "傷害達上限 → 被擊敗退場");
        assertEquals(handBefore, joe.getHand().size(), "退場後該行動中止,不再抽牌");
        assertEquals(2, state.location("loc").getClues(), "被擊敗者的線索掉落在所在地點");
        assertEquals(0, joe.getCluesHeld());
        assertNull(killer.getEngagedWith(), "威脅區敵人解交戰、留在地點");
        assertTrue(state.getEnemies().containsKey("e1"), "敵人不移除,留在場上");
        assertFalse(state.isGameOver(), "個別淘汰:其餘調查員繼續劇本");

        // 淘汰者不能再行動
        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.DRAW, Map.of()));

        // 最後一人也退場 → 全員退場,未達成結局
        eng.applyIntent("daniela", IntentAction.RESIGN, Map.of());
        assertTrue(state.isGameOver(), "全員退場 → 劇本結束");
        assertFalse(state.isWon(), "以「未達成任何結局」收場");
    }

    // ------------------------------------------------------------------
    // 場上毀滅(官方 p18:計入所有卡上的毀滅)
    // ------------------------------------------------------------------

    @Test
    void doomOnEnemiesAndLocationsCountsTowardAgenda() {
        GameState state = twoPlayerState(3);                 // 密謀門檻 3
        enemyAt(state, "e1", "loc2", 1, null).addDoom(1);    // 敵人卡上 1 毀滅(無人在 loc2,不會交戰)
        state.location("loc2").addDoom(1);                   // 地點卡上 1 毀滅
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        // 強制回合結算 → 第 2 輪神話階段:密謀 +1 → 場上毀滅 = 1+1+1 = 3 ≥ 門檻
        eng.applyIntent("joe_diamond", IntentAction.END_TURN, Map.of("force", true));
        assertEquals(1, state.getAgenda().getDoom(), "密謀卡自身只放了 1 毀滅");
        assertTrue(state.isGameOver(), "計入敵人/地點毀滅後達門檻 → 密謀推進結束劇本");
        assertFalse(state.isWon());
    }

    // ------------------------------------------------------------------
    // 開局手牌調整(官方 p20)
    // ------------------------------------------------------------------

    @Test
    void mulliganRedrawsOnceAndReshufflesAside() {
        GameState state = twoPlayerState(99);
        Investigator joe = state.investigator("joe_diamond");
        joe.getHand().clear();   // 清掉登記表的示範起手,改用可控的兩張
        joe.getHand().add(CardInstance.skill("h1", "換掉的牌", SkillIcon.WILD));
        joe.getHand().add(CardInstance.skill("h2", "留著的牌", SkillIcon.WILD));
        joe.getDeckPile().add(CardInstance.skill("d1", "牌庫頂", SkillIcon.WILD));
        joe.getDeckPile().add(CardInstance.skill("d2", "牌庫二", SkillIcon.WILD));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.MULLIGAN, Map.of("cardIds", List.of("h1")));
        assertEquals(2, joe.getHand().size(), "換掉 1 張、補抽 1 張 → 手牌數不變");
        assertTrue(joe.getHand().stream().noneMatch(c -> c.cardId().equals("h1")), "換掉的牌離開手牌");
        assertTrue(joe.getHand().stream().anyMatch(c -> c.cardId().equals("d1")), "補抽來自牌庫頂");
        assertEquals(2, joe.getDeckPile().size(), "放一旁的牌洗回牌庫(1 抽出 + 1 洗回)");
        assertTrue(joe.getDeckPile().stream().anyMatch(c -> c.cardId().equals("h1")));
        assertEquals(3, joe.getActionsRemaining(), "手牌調整不是行動,不消耗行動數");

        // 每人限一次
        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("joe_diamond", IntentAction.MULLIGAN, Map.of("cardIds", List.of("h2"))));

        // 已開始行動的人不能再調整
        eng.applyIntent("daniela", IntentAction.GAIN_RESOURCE, Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> eng.applyIntent("daniela", IntentAction.MULLIGAN, Map.of("cardIds", List.of())));
    }

    // ------------------------------------------------------------------
    // 趁隙攻擊範圍(官方 p22:僅戰鬥/閃避/交涉/撤退豁免)
    // ------------------------------------------------------------------

    @Test
    void playCardAndEngageProvokeAttackOfOpportunity() {
        GameState state = twoPlayerState(99);
        Investigator joe = state.investigator("joe_diamond");
        joe.gainResources(5);
        joe.getHand().add(CardInstance.asset("a1", "測試道具", 1, SkillIcon.WILD));
        enemyAt(state, "e1", "loc", 1, "joe_diamond");        // 已交戰:每次趁隙 1 傷
        EnemyCard aloofer = enemyAt(state, "e2", "loc", 1, null);   // 同地點未交戰
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "a1"));
        assertEquals(1, joe.getDamage(), "打出非快速卡引發趁隙攻擊");

        eng.applyIntent("joe_diamond", IntentAction.ENGAGE, Map.of("enemyId", "e2"));
        assertEquals(2, joe.getDamage(), "交戰行動也引發(已交戰敵人)趁隙攻擊");
        assertEquals("joe_diamond", aloofer.getEngagedWith());

        // 取資源同樣會引發
        eng.applyIntent("daniela", IntentAction.GAIN_RESOURCE, Map.of());
        assertEquals(0, state.investigator("daniela").getDamage(), "沒交戰就沒有趁隙攻擊");
    }

    // ------------------------------------------------------------------
    // 設計守門:隊友投入上限 1 張(官方 p15)—— 刻意不採該分支的放寬
    // ------------------------------------------------------------------

    @Test
    void allyCommitLimitStaysOne() {
        GameState state = twoPlayerState(99);
        state.location("loc").setClues(3);   // 調查需有線索
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("daniela", IntentAction.INVESTIGATE, Map.of());
        assertTrue(eng.hasPendingCommit(), "調查 → 進入投入屏障");
        assertEquals(1, eng.commitOptionsFor("joe_diamond").maxCommit(),
                "同地點隊友最多投入 1 張(官方 p15;守住此上限)");
        assertTrue(eng.commitOptionsFor("daniela").maxCommit() > 1, "檢定者自己不受 1 張限制");
    }
}
