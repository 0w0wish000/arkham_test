package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.GameState;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.scenario.ScenarioData;
import com.arkham.engine.scenario.ScenarioFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A4 幕/密謀牌組化:多張逐張推進 —— 非最後一張推進續玩、
 * 密謀推進移除場上毀滅、最後一張才觸發結局。單張劇本(佇列空)行為不變。
 */
class ActAgendaDeckTest {

    /** 兩幕兩密謀的迷你劇本(門檻刻意小,測推進即可)。 */
    private static ScenarioData twoStage() {
        return new ScenarioData("t2", "兩段測試", null, null,
                List.of(new ScenarioData.ActData("第一幕", 1), new ScenarioData.ActData("第二幕", 1)),
                List.of(new ScenarioData.AgendaData("密謀一", 1), new ScenarioData.AgendaData("密謀二", 9)),
                "loc",
                List.of(new ScenarioData.LocationData("loc", "測試地點", 1, 2, true, List.of(), false, null)),
                List.of(),   // 無敵人
                List.of());  // 空遭遇
    }

    @Test
    void actDeckAdvancesThenFinalActWins() {
        GameState state = ScenarioFactory.createFromData(twoStage(), List.of("joe_diamond"), "STANDARD");
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));
        Investigator joe = state.investigator("joe_diamond");
        joe.gainClue();

        eng.applyIntent("joe_diamond", IntentAction.ADVANCE_ACT, Map.of());
        assertFalse(state.isGameOver(), "還有第二幕 → 只換幕不結束");
        assertEquals("第二幕", state.getAct().getName());
        assertEquals(0, state.getActQueue().size(), "幕佇列耗盡");

        joe.gainClue();
        eng.applyIntent("joe_diamond", IntentAction.ADVANCE_ACT, Map.of());
        assertTrue(state.isGameOver() && state.isWon(), "最後一幕推進 → 勝利結局");
    }

    @Test
    void agendaAdvanceClearsDoomAndOnlyFinalLoses() {
        GameState state = ScenarioFactory.createFromData(twoStage(), List.of("joe_diamond"), "STANDARD");
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));

        // 第 1 輪結束 → 第 2 輪神話:密謀一 +1 = 門檻 1 → 推進到密謀二、毀滅歸零、續玩
        eng.applyIntent("joe_diamond", IntentAction.END_TURN, Map.of("force", true));
        assertFalse(state.isGameOver(), "密謀一推進後仍有密謀二 → 續玩");
        assertEquals("密謀二", state.getAgenda().getName());
        assertEquals(0, state.getAgenda().getDoom(), "新密謀從 0 毀滅開始(推進時移除場上毀滅)");
        assertEquals(0, state.getAgendaQueue().size());
    }

    /** 單張劇本(core.json)行為不變:推進幕即勝利。 */
    @Test
    void singleStageScenarioUnchanged() {
        GameState state = ScenarioFactory.createState(List.of("joe_diamond", "daniela"), "core", "STANDARD");
        assertTrue(state.getActQueue().isEmpty() && state.getAgendaQueue().isEmpty(), "core 仍是單張");
        RulesEngine eng = new RulesEngine(state, new SeededRng(1));
        Investigator joe = state.investigator("joe_diamond");
        joe.gainClue();
        joe.gainClue();
        eng.applyIntent("joe_diamond", IntentAction.ADVANCE_ACT, Map.of());
        assertTrue(state.isGameOver() && state.isWon(), "單張:推進幕=勝利(行為不變)");
    }
}
