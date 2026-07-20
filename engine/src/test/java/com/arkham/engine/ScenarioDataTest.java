package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.EnemyDef;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.Keyword;
import com.arkham.engine.scenario.ScenarioData;
import com.arkham.engine.scenario.ScenarioFactory;
import com.arkham.engine.scenario.ScenarioRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A1/A2 場景資料化:core.json 載入 → 建州內容與原硬編劇本等價
 * (5 地點 / 幕 2 / 密謀 5 / 3 敵種 / 6 張遭遇 / 起點線索 × 人數)。
 */
class ScenarioDataTest {

    @Test
    void coreScenarioLoadsFromJson() {
        ScenarioData d = ScenarioRepository.find("core").orElseThrow();
        assertEquals("core", d.key());
        assertEquals(5, d.locations().size(), "5 個地點");
        assertEquals(2, d.act().threshold(), "幕門檻 2");
        assertEquals(5, d.agenda().threshold(), "密謀門檻 5");
        assertEquals(3, d.enemies().size(), "3 種敵人");
        assertEquals(6, d.encounterDeck().size(), "6 張遭遇");
        assertEquals("friends_room", d.startLocationId());
    }

    @Test
    void builtStateMatchesLegacyScenario() {
        GameState state = ScenarioFactory.createState(List.of("joe_diamond", "daniela"), "core", "STANDARD");

        // 地點圖:起點已揭示、線索 = clueValue2 × 2 人;宿舍未揭示且進入生 servant
        assertEquals(4, state.location("friends_room").getClues(), "起點線索 = 2 × 2 人");
        assertTrue(state.location("friends_room").isRevealed());
        assertTrue(!state.location("dormitories").isRevealed());
        assertEquals("servant", state.location("dormitories").getSpawnDefKey());
        assertTrue(state.location("dormitories").connectsTo("quad"));
        assertTrue(state.location("library").isVictory(), "圖書館是勝利地點");

        // 敵人定義含關鍵字
        EnemyDef servant = state.getEnemyDefs().get("servant");
        assertNotNull(servant);
        assertEquals(4, servant.fight());
        assertTrue(servant.keywords().contains(Keyword.HUNTER) && servant.keywords().contains(Keyword.RETALIATE));

        // 幕/密謀 + 人數鎖定
        assertEquals(2, state.getAct().getThreshold());
        assertEquals(5, state.getAgenda().getThreshold());
        assertEquals(2, state.getPlayerCount());
        assertEquals("joe_diamond", state.getActiveInvestigatorId());
        assertEquals("friends_room", state.investigator("daniela").getLocationId(), "全員在資料指定的起點");
    }

    @Test
    void unknownCampaignKeyFallsBackToCore() {
        GameState state = ScenarioFactory.createState(List.of("joe_diamond"), "dwl", "STANDARD");
        assertNotNull(state.location("friends_room"), "查無 dwl 資料 → 後備 core 劇本");
        assertEquals(2, state.location("friends_room").getClues(), "單人:線索 = 2 × 1");
    }
}
