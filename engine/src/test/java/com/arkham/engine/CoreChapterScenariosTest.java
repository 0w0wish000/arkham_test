package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.GameState;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.scenario.ScenarioData;
import com.arkham.engine.scenario.ScenarioFactory;
import com.arkham.engine.scenario.ScenarioRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D1-lite『狂熱之夜』三章劇本檔(core_ch1/ch2/ch3)整合驗證:每章都能經真實載入路徑
 * ({@link ScenarioRepository} Jackson 反序列化 → {@link ScenarioFactory#createFromData})
 * 建出合法 {@link GameState},且劇本內部自洽(起點/連線/敵人 defKey/幕密謀非空)。
 * 純機制資料驗證,不涉 FFG 敘事文本。
 */
class CoreChapterScenariosTest {

    @ParameterizedTest
    @ValueSource(strings = {"core_ch1", "core_ch2", "core_ch3"})
    void chapterLoadsAndBuildsConsistentState(String key) {
        ScenarioData d = ScenarioRepository.find(key).orElseThrow(() -> new AssertionError(key + " 載入失敗"));
        assertEquals(key, d.key());
        assertFalse(d.locations().isEmpty(), "地點非空");
        assertFalse(d.acts().isEmpty(), "幕牌組非空");
        assertFalse(d.agendas().isEmpty(), "密謀牌組非空");

        // 內部自洽:所有連線 / spawnDefKey / 遭遇敵人的 defKey 都要指得到已定義的目標
        var locIds = d.locations().stream().map(ScenarioData.LocationData::id).toList();
        var defKeys = d.enemies().stream().map(ScenarioData.EnemyDefData::defKey).toList();
        assertTrue(locIds.contains(d.startLocationId()), "起點在地點清單內");
        for (var l : d.locations()) {
            for (String c : l.connections()) {
                assertTrue(locIds.contains(c), key + ": 連線 " + l.id() + "→" + c + " 指向不存在的地點");
            }
            if (l.spawnDefKey() != null) {
                assertTrue(defKeys.contains(l.spawnDefKey()), key + ": " + l.id() + " 生怪 defKey 未定義");
            }
        }
        for (var e : d.encounterDeck()) {
            if ("ENEMY".equals(e.type())) {
                assertTrue(defKeys.contains(e.defKey()), key + ": 遭遇敵人 defKey 未定義:" + e.defKey());
            }
        }

        // 真實建州:單人 Joe,起點揭示且線索 = clueValue × 人數
        GameState state = ScenarioFactory.createFromData(d, List.of("joe_diamond"), "STANDARD");
        LocationCard start = state.location(d.startLocationId());
        assertNotNull(start, "起點卡建出");
        assertTrue(start.isRevealed(), "起點開場已揭示");
        int expectedClues = startClueValue(d) * state.getPlayerCount();
        assertEquals(expectedClues, start.getClues(), key + ": 起點線索 = clueValue × 人數");
        assertEquals("joe_diamond", state.getActiveInvestigatorId());
        assertEquals(d.startLocationId(), state.investigator("joe_diamond").getLocationId());
        assertNotNull(state.getAct(), "首幕就位");
        assertNotNull(state.getAgenda(), "首密謀就位");
    }

    private static int startClueValue(ScenarioData d) {
        return d.locations().stream()
                .filter(l -> l.id().equals(d.startLocationId()))
                .findFirst().orElseThrow().clueValue();
    }

    /** D2 設置分支:第 2 章帶 house_burned 旗標 → 起點改河岸城、你的房子移出遊戲(並自連線清除)。 */
    @Test
    void houseBurnedFlagRewritesChapterTwoSetup() {
        ScenarioData d = ScenarioRepository.find("core_ch2").orElseThrow();

        // 無旗標:預設起點你的房子、地點在場
        GameState normal = ScenarioFactory.createFromData(d, List.of("joe_diamond"), "STANDARD");
        assertEquals("your_house", normal.investigator("joe_diamond").getLocationId());
        assertNotNull(normal.location("your_house"), "無旗標 → 你的房子在場");

        // house_burned:起點改河岸城、你的房子不在場、且無地點仍連向它
        GameState burned = ScenarioFactory.createFromData(
                d, List.of("joe_diamond"), "STANDARD", java.util.Set.of("house_burned"));
        assertEquals("rivertown", burned.investigator("joe_diamond").getLocationId(), "起點改河岸城");
        assertNull(burned.location("your_house"), "你的房子移出遊戲");
        for (LocationCard l : burned.getLocations().values()) {
            assertFalse(l.getConnections().contains("your_house"),
                    l.getId() + " 仍連向已移除的你的房子");
        }
    }
}
