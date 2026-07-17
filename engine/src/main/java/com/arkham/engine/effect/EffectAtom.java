package com.arkham.engine.effect;

/**
 * C1 效果 DSL —— 卡牌效果的最小宣告單位(docs/11 §C)。
 *
 * <p>設計:卡牌效果 = 一串「原子」的資料清單,存放在
 * {@link com.arkham.engine.scenario.CardCatalog}(內建)或由內容管線
 * {@code registerEffect} 灌入(外部);規則直譯器在
 * {@code RulesEngine#applyCardEffect} —— 資料與規則分離,
 * 未來授權卡池只要「填資料」,不必改引擎。
 *
 * <p>原子逐步擴充:目前涵蓋沙盒五卡所需 + 抽牌/治恐懼;
 * 目標選擇、持續效果、條件分支屬後續刀(docs/11 C1 完整版)。
 */
public sealed interface EffectAtom {

    /** +n 資源。 */
    record GainResources(int n) implements EffectAtom {}

    /** 抽 n 張(走引擎抽牌規則:空堆 → 棄牌堆洗回 +1 恐懼)。 */
    record DrawCards(int n) implements EffectAtom {}

    /** 治療 damage 點傷害、horror 點恐懼。 */
    record Heal(int damage, int horror) implements EffectAtom {}

    /** 免檢定:從所在地點發現 n 個線索(不足則取到空為止)。 */
    record DiscoverClues(int n) implements EffectAtom {}
}
