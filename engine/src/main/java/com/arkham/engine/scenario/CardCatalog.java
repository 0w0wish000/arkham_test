package com.arkham.engine.scenario;

import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.SkillIcon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡名 → 卡片規格(型別 / 費用 / 技能圖示)的目錄,涵蓋選卡器
 * (prototype/deckbuilder.html 的 starter / pool / SEARCH_DB)出現的卡名。
 * C-lite 牌組管線用:把 SET_DECK 送來的「卡名清單」實體化成可抽、可投入、
 * 可打出的 {@link CardInstance}。效果面:多數卡尚無特效(打出顯示「尚無特效」),
 * 但技能圖示已可用於檢定投入 —— 完整效果走 docs/11 §C 的 DSL。
 *
 * <p>未知卡名 → 0 費事件、無圖示(可打出、無效果),牌組不會因此壞掉。
 */
public final class CardCatalog {

    private CardCatalog() {}

    private record Spec(String type, int cost, SkillIcon[] icons) {}

    private static final SkillIcon[] NONE = {};
    private static final Map<String, Spec> CARDS = new LinkedHashMap<>();

    private static void skill(String name, SkillIcon... icons) { CARDS.put(name, new Spec("skill", 0, icons)); }
    private static void event(String name, int cost, SkillIcon... icons) { CARDS.put(name, new Spec("event", cost, icons)); }
    private static void asset(String name, int cost, SkillIcon... icons) { CARDS.put(name, new Spec("asset", cost, icons)); }
    private static void weakness(String name) { CARDS.put(name, new Spec("weakness", 0, NONE)); }

    static {
        final SkillIcon W = SkillIcon.WILLPOWER, I = SkillIcon.INTELLECT,
                C = SkillIcon.COMBAT, A = SkillIcon.AGILITY, X = SkillIcon.WILD;

        // ---- 技能(圖示 = 投入點數)----
        skill("Deduction", I);
        skill("Vicious Blow", C);
        skill("Perception", I, I);
        skill("Overpower", C, C);
        skill("Guts", W, W);
        skill("Unexpected Courage", X, X);
        skill("Soul Link", W, W);
        skill("Scrape By", X);

        // ---- 事件 ----
        event("Detective's Intuition", 2, I, I);
        event("Gather Intel", 1, I);
        event("Through the Cracks", 0, A);
        event("Working a Hunch", 2, I);
        event("Lesson Learned", 1, I);
        event("Right Tool for the Job", 2, I);
        event("Scene of the Crime", 1, I);
        event("Emergency Cache", 0);
        event("Cryptic Research", 2, I);
        event("Get behind me!", 0, C);
        event("Counterattack", 2, C);
        event("Ward of Protection", 1, W);
        event("Premonition", 1, W);
        event("Will of the Cosmos", 2, W);
        event("Spiritual Intuition", 1, W);
        event("Decisive Strike", 1, C);
        event("Mind over Matter", 1, I);
        event("First Aid", 1);

        // ---- 支援 ----
        asset("Magnifying Glass", 1, I);
        asset("Fingerprint Kit", 3, I);
        asset("Dorothy Simmons", 3, W);
        asset("Laboratory Assistant", 2, I);
        asset("Local Map", 1);
        asset("Sharp Rhetoric", 3, I);
        asset("Bodyguard", 2, C);
        asset("Endurance", 2);
        asset("Logan Hastings", 4, C);
        asset("M1911", 3, C);
        asset("Machete", 3, C);
        asset("Fedora", 2, A);
        asset("Hand-Crank Flashlight", 2);
        asset("Daniela's Wrench", 3, C);
        asset("Aleksey Saburov", 4, C);
        asset("Resilience", 1);
        asset("Bandages", 1, W);
        asset("Cloak of Resonance", 2, W);
        asset("Cosmic Flame", 2, W);
        asset("Second Sight", 1, W);
        asset("Jim Culver", 3, W);
        asset("Lucky Charm", 2, W);
        asset("Sticky Fingers", 2, A);
        asset("Studious", 0, I);
        asset("Higher Education", 0, W);
        asset("Charisma", 0);
        asset("Relic Hunter", 0);
        asset("Old Book of Lore", 3, I);
        asset("Unbridled Knowledge", 2, I);
        asset("Mysterious Grimoire", 3, I);
        asset("Arcane Studies", 0, W);
        asset("Sledgehammer", 4, C);
        asset("Winchester Model 12", 5, C);
        asset("Shrivelling", 3, W);
        asset("Lucky Cigarette Case", 3, A);
        asset("Fire Axe", 1, C);

        // ---- 招牌弱點(抽到卡手上;不可打出、無圖示不可投入)----
        weakness("Dead Ends");
        weakness("Wounded");
        weakness("Paranoia");
    }

    // ---- B5 常駐修正層(lite):檯面支援卡提供的持續加值,由引擎「推導」而非改欄位 ----
    private static final Map<String, SkillIcon> CONSTANT_SKILL = Map.of(
            "Magnifying Glass", SkillIcon.INTELLECT);   // +1 智力(持續)
    private static final Map<String, Integer> WEAPON_BONUS = Map.of(
            "Machete", 1);                              // 戰鬥傷害 +1(持續)

    /** 這張檯面卡對某技能的常駐加值(目前每卡 +1;完整修正層見 docs/11 B5)。 */
    public static int constantSkillBonus(String cardName, com.arkham.engine.model.SkillType type) {
        SkillIcon ic = CONSTANT_SKILL.get(cardName);
        return ic != null && ic.matches(type) ? 1 : 0;
    }

    /** 這張檯面卡提供的武器傷害加值。 */
    public static int weaponBonus(String cardName) {
        return WEAPON_BONUS.getOrDefault(cardName, 0);
    }

    /** 外部登記層(G1):伺服器啟動時由 content/cards/generated/ 灌入真卡資料;查找優先於內建。 */
    private static final Map<String, Spec> EXTERNAL = new java.util.concurrent.ConcurrentHashMap<>();

    /** 登記一張外部卡(type ∈ asset/event/skill;icons 為 SkillIcon 名)。 */
    public static void register(String name, String type, int cost, List<SkillIcon> icons) {
        EXTERNAL.put(name, new Spec(type, Math.max(0, cost), icons.toArray(new SkillIcon[0])));
    }

    public static int externalCount() { return EXTERNAL.size(); }

    /** 把一份卡名清單實體化成牌堆(cardId 依 {@code prefix-d1..} 編)。 */
    public static List<CardInstance> buildDeck(String idPrefix, List<String> names) {
        List<CardInstance> deck = new java.util.ArrayList<>();
        int n = 1;
        for (String name : names) {
            Spec s = EXTERNAL.get(name);                                      // 真卡資料優先
            if (s == null) s = CARDS.getOrDefault(name, new Spec("event", 0, NONE));   // 內建 → 未知=0費無效果
            deck.add(new CardInstance(idPrefix + "-d" + (n++), name, s.type(), s.cost(), List.of(s.icons())));
        }
        return deck;
    }

    /** 預設牌組(玩家沒用選卡器提交時;約 15 張,取自該角色的 starter 卡池)。 */
    public static List<String> defaultDeck(String investigatorId) {
        return switch (investigatorId) {
            case "joe_diamond" -> List.of(
                    "Magnifying Glass", "Fingerprint Kit", "Dorothy Simmons", "Working a Hunch",
                    "Emergency Cache", "Emergency Cache", "Deduction", "Deduction",
                    "Perception", "Perception", "Overpower", "Overpower",
                    "Unexpected Courage", "Unexpected Courage", "Vicious Blow");
            case "daniela" -> List.of(
                    "Daniela's Wrench", "Machete", "M1911", "Bodyguard", "Bandages",
                    "Emergency Cache", "Emergency Cache", "Get behind me!",
                    "Vicious Blow", "Vicious Blow", "Overpower", "Overpower",
                    "Guts", "Guts", "Unexpected Courage");
            case "dexter_drake" -> List.of(
                    "Cosmic Flame", "Second Sight", "Jim Culver", "Lucky Charm",
                    "Ward of Protection", "Spiritual Intuition", "Emergency Cache", "Emergency Cache",
                    "Soul Link", "Soul Link", "Guts", "Overpower",
                    "Unexpected Courage", "Unexpected Courage", "Premonition");
            default -> List.of("Emergency Cache", "Unexpected Courage", "Unexpected Courage",
                    "Guts", "Overpower", "Deduction");
        };
    }
}
