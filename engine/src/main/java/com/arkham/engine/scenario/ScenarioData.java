package com.arkham.engine.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A1 場景資料 schema(docs/11 §A)—— 一個劇本的宣告式定義,對應
 * {@code engine/src/main/resources/scenarios/<key>.json}。
 *
 * <p>這是「劇本結構」資料(地點圖 / 幕 / 密謀 / 敵人數值 / 遭遇組成),
 * 全部是自家 schema 與遊戲數值,不含 FFG 敘事文本;由
 * {@link ScenarioRepository} 載入、{@link ScenarioFactory} 據以建 {@code GameState}。
 * 未來擴充欄位(多張幕/密謀牌組 A4、混沌袋覆寫 A5、結局分支 D2)往這裡加。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioData(
        String key,                      // 劇本鍵(= 大廳 campaignKey;檔名同)
        String name,
        ActData act,                     // 單張劇本用(與 acts 擇一;acts 優先)
        AgendaData agenda,
        List<ActData> acts,              // A4:幕牌組(依序推進,最後一張推進=勝利結局)
        List<AgendaData> agendas,        // A4:密謀牌組(最後一張達門檻=敗北結局)
        String startLocationId,          // 全員起始地點(開場已揭示、放 clueValue × 人數)
        List<LocationData> locations,
        List<EnemyDefData> enemies,
        List<EncounterData> encounterDeck) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActData(String name, int threshold) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgendaData(String name, int threshold) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationData(String id, String name, int shroud, int clueValue,
                               boolean revealed, List<String> connections,
                               boolean victory, String spawnDefKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnemyDefData(String defKey, String name, int fight, int health, int evade,
                               int damage, int horror, List<String> keywords) {}

    /** type=ENEMY 用 defKey;type=TREACHERY 用 name+effect(HORROR/DOOM/NOTHING)+amount。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EncounterData(String type, String defKey, String name, String effect, int amount) {}
}
