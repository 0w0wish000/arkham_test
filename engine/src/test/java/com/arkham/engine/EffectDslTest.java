package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.effect.EffectAtom;
import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.model.SkillIcon;
import com.arkham.engine.model.SkillType;
import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.scenario.CardCatalog;
import com.arkham.engine.scenario.ScenarioFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * C1 效果 DSL(docs/11 §C 垂直切片)—— 原子直譯、內建卡等價行為、外部登記層。
 * 皆為確定性(袋只放 +1)。
 */
class EffectDslTest {

    private static GameState baseState() {
        GameState state = new GameState(new ChaosBag(List.of(ChaosToken.numeric(1))),
                new Act("測試", 99), new Agenda("測試", 99), Map.of(), List.of());
        state.addLocation(new LocationCard("loc", "測試地點", 1, 2, true, List.of(), false, null));
        Investigator inv = ScenarioFactory.buildInvestigator("joe_diamond");
        inv.setLocationId("loc");
        inv.setActionsRemaining(3);
        state.addInvestigator(inv);
        state.lockPlayerCount();
        state.setActiveInvestigatorId("joe_diamond");
        return state;
    }

    /** 內建卡改資料化後行為不變:緊急補給 = GainResources(3)。 */
    @Test
    void emergencyCacheGainsThreeResources() {
        GameState state = baseState();
        Investigator joe = state.investigator("joe_diamond");
        joe.getHand().add(CardInstance.event("c1", "Emergency Cache", 0));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        int before = joe.getResources();
        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "c1"));
        assertEquals(before + 3, joe.getResources(), "緊急補給 +3 資源(0 費)");
        assertTrue(joe.getDiscardPile().stream().anyMatch(c -> c.cardId().equals("c1")), "事件打出後進棄牌堆");
    }

    /** 靈光一閃 = DiscoverClues(1):有線索拿 1,無線索優雅落空。 */
    @Test
    void workingAHunchDiscoversClueOrFizzles() {
        GameState state = baseState();
        state.location("loc").setClues(1);
        Investigator joe = state.investigator("joe_diamond");
        joe.gainResources(5);
        joe.getHand().add(CardInstance.event("c1", "Working a Hunch", 2));
        joe.getHand().add(CardInstance.event("c2", "Working a Hunch", 2));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "c1"));
        assertEquals(1, joe.getCluesHeld(), "免檢定取得 1 線索");
        assertEquals(0, state.location("loc").getClues());

        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "c2"));
        assertEquals(1, joe.getCluesHeld(), "此處已無線索 → 不多拿、也不報錯");
    }

    /** 急救 = Heal(1,0)。 */
    @Test
    void firstAidHealsDamage() {
        GameState state = baseState();
        Investigator joe = state.investigator("joe_diamond");
        joe.takeDamage(2);
        joe.gainResources(5);
        joe.getHand().add(CardInstance.event("c1", "First Aid", 1));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "c1"));
        assertEquals(1, joe.getDamage(), "治療 1 點傷害");
    }

    /** 外部登記層:內容管線灌入的效果(多原子組合)可直接生效 —— 不改引擎。 */
    @Test
    void externallyRegisteredEffectApplies() {
        CardCatalog.register("測試百寶袋", "event", 0, List.of());
        CardCatalog.registerEffect("測試百寶袋", List.of(
                new EffectAtom.GainResources(2),
                new EffectAtom.DrawCards(1),
                new EffectAtom.Heal(0, 1)));

        GameState state = baseState();
        Investigator joe = state.investigator("joe_diamond");
        joe.takeHorror(2);
        joe.getDeckPile().add(CardInstance.skill("d1", "牌庫頂", SkillIcon.WILD));
        joe.getHand().add(CardInstance.event("c1", "測試百寶袋", 0));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        int res = joe.getResources();
        int hand = joe.getHand().size();
        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "c1"));
        assertEquals(res + 2, joe.getResources(), "原子①:+2 資源");
        assertEquals(hand, joe.getHand().size(), "原子②:抽 1(打出 −1 + 抽 1 → 持平)");
        assertTrue(joe.getHand().stream().anyMatch(c -> c.cardId().equals("d1")), "抽到牌庫頂");
        assertEquals(1, joe.getHorror(), "原子③:治療 1 恐懼");
    }

    /** B5 常駐修正資料化後查找不變;外部登記的修正也可用。 */
    @Test
    void constantModifiersStillResolveAndAcceptExternal() {
        assertEquals(1, CardCatalog.constantSkillBonus("Magnifying Glass", SkillType.INTELLECT));
        assertEquals(0, CardCatalog.constantSkillBonus("Magnifying Glass", SkillType.COMBAT));
        assertEquals(1, CardCatalog.weaponBonus("Machete"));
        assertEquals(0, CardCatalog.weaponBonus("Magnifying Glass"));

        CardCatalog.registerModifier("測試護符", new CardCatalog.Mods(SkillIcon.WILLPOWER, 2, 0));
        assertEquals(2, CardCatalog.constantSkillBonus("測試護符", SkillType.WILLPOWER), "外部登記的常駐修正生效");
        assertTrue(CardCatalog.modifierNote("測試護符").contains("意志 +2"), "裝備訊息由資料生成");
    }

    /** 沒有效果資料的卡:照樣可打出(進棄牌堆/檯面),不報錯。 */
    @Test
    void cardWithoutEffectStillPlays() {
        GameState state = baseState();
        Investigator joe = state.investigator("joe_diamond");
        joe.gainResources(5);
        joe.getHand().add(CardInstance.asset("c1", "Local Map", 1));
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        eng.applyIntent("joe_diamond", IntentAction.PLAY_CARD, Map.of("cardId", "c1"));
        assertTrue(joe.getPlayArea().stream().anyMatch(c -> c.cardId().equals("c1")), "無特效支援卡照樣進檯面");
    }
}
