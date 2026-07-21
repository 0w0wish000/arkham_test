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
        List<EncounterData> encounterDeck,
        List<ResolutionData> resolutions, // D2:本章可能結局(章末由玩家投票選定;結果 = XP加成/創傷/日誌旗標)
        List<StartOverride> startOverrides, // D2:設置分支 —— 某旗標成立則改用他地為起點
        List<LocationRemoval> removals) {   // D2:設置分支 —— 某旗標成立則移除該地點(並自連線清除)

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

    /**
     * D2 結局:一種可能的章末結果。{@code win} 標示屬勝/敗結局(章末依實際勝負過濾選項)。
     * {@code outcome} 是系統「算得出來」的部分(XP加成/全體創傷/日誌旗標);人為的、給特定玩家
     * 的敘事指示(如某角色獲得盟友卡)仍走 APPLY_LOG(docs/09 §11.5 混合制)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolutionData(String id, String label, boolean win, ResolutionOutcome outcome) {}

    /**
     * 結局的機制結果。{@code record} = 寫入戰役日誌的旗標(下一章設置分支讀它);
     * {@code note} = 給玩家看的提醒(通常是「需人工 APPLY_LOG」的敘事指示,如發卡給某人)。
     * physical/mentalTrauma = 施加給每位參戰調查員的創傷(僅用於「全體」等級的結局傷害)。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolutionOutcome(int xpBonus, int physicalTrauma, int mentalTrauma,
                                    List<String> record, String note) {}

    /** D2 設置分支:{@code requiresFlag} 旗標成立 → 起點改為 {@code locationId}(取第一個成立者)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StartOverride(String requiresFlag, String locationId) {}

    /** D2 設置分支:{@code requiresFlag} 旗標成立 → 移除地點 {@code locationId}(並自其他地點連線清除)。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocationRemoval(String requiresFlag, String locationId) {}
}
