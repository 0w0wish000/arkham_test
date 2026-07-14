package com.arkham.engine.scenario;

import com.arkham.engine.ChaosBag;
import com.arkham.engine.RulesEngine;
import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.EnemyDef;
import com.arkham.engine.model.EncounterCard;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.model.Keyword;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.model.SkillIcon;
import com.arkham.engine.model.Skills;
import com.arkham.engine.rng.SeededRng;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the "Spreading Flames (lite)" starting {@link GameState}, faithfully porting
 * the prototype's scenario data: 5 Miskatonic locations, the Servant/Acolyte/Fire
 * Vampire enemy definitions, Joe Diamond (plus a same-location ally so the commit
 * barrier and per-client view filtering are exercised), the "Past Curfew" agenda, the
 * "Where There's Smoke…" act, the 6-card encounter deck and the standard 16-token bag.
 *
 * <p><b>Assumption:</b> to demonstrate multiplayer (docs/05 §2.1 commit barrier), a
 * second investigator "Daniela" starts alongside Joe. Clue counts scale by player
 * count (prototype comment "clueValue × 玩家數"); the act threshold is kept at the
 * prototype's value of 2.
 */
public final class ScenarioFactory {

    public static final String JOE = "joe_diamond";
    public static final String DANIELA = "daniela";

    private ScenarioFactory() {}

    /** Default roster (backward-compatible with the old room path): Joe + Daniela. */
    public static final List<String> DEFAULT_ROSTER = List.of(JOE, DANIELA);

    /** Convenience: a fresh engine with the default roster, seeded for replay. */
    public static RulesEngine newEngine(long seed) {
        return newEngine(seed, DEFAULT_ROSTER);
    }

    /** A fresh engine placing the roster's investigators (docs/09 START_SCENARIO). */
    public static RulesEngine newEngine(long seed, List<String> investigatorIds) {
        return new RulesEngine(createState(investigatorIds), new SeededRng(seed));
    }

    /** A fresh engine for a specific scenario key(如 "sandbox" 測試沙盒;docs/11)。 */
    public static RulesEngine newEngine(long seed, List<String> investigatorIds, String scenarioKey) {
        return newEngine(seed, investigatorIds, scenarioKey, "STANDARD", null);
    }

    /**
     * 完整版(campaign 開打用):難度組混沌袋 + C-lite 牌組管線。
     * 非沙盒場景:每位調查員的牌組(SET_DECK 卡名;未提交 → 預設牌組)→ 洗牌 → 開局抽 5。
     * 沙盒維持固定手牌(專測特殊卡)。
     */
    public static RulesEngine newEngine(long seed, List<String> investigatorIds, String scenarioKey,
                                        String difficulty, Map<String, List<String>> decksByInvestigator) {
        SeededRng rng = new SeededRng(seed);
        GameState state = createState(investigatorIds, scenarioKey, difficulty);
        if (!"sandbox".equals(scenarioKey)) {
            for (Investigator inv : state.orderedInvestigators()) {
                List<String> names = decksByInvestigator == null ? null : decksByInvestigator.get(inv.getId());
                if (names == null || names.isEmpty()) {
                    names = CardCatalog.defaultDeck(inv.getId());
                }
                inv.getDeckPile().clear();
                inv.getDeckPile().addAll(CardCatalog.buildDeck(inv.getId(), names));
                shuffle(inv.getDeckPile(), rng);
                inv.getHand().clear();
                for (int k = 0; k < 5 && !inv.getDeckPile().isEmpty(); k++) {
                    inv.getHand().add(inv.getDeckPile().remove(0));   // 開局起手 5 張
                }
            }
        }
        return new RulesEngine(state, rng);
    }

    /** 依 scenarioKey 選場景;"sandbox" → 測試沙盒,其餘 → Spreading Flames lite。 */
    public static GameState createState(List<String> investigatorIds, String scenarioKey) {
        return createState(investigatorIds, scenarioKey, "STANDARD");
    }

    /** 同上,並依難度組混沌袋(docs/09 §12)。 */
    public static GameState createState(List<String> investigatorIds, String scenarioKey, String difficulty) {
        if ("sandbox".equals(scenarioKey)) return createSandbox(investigatorIds, difficulty);
        return createCoreState(investigatorIds, difficulty);
    }

    /** Fisher–Yates(用引擎中央 RNG,可重現)。 */
    private static void shuffle(List<CardInstance> cards, SeededRng rng) {
        for (int i = cards.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            java.util.Collections.swap(cards, i, j);
        }
    }

    /** Default-roster state (Joe + Daniela). */
    public static GameState createState() {
        return createState(DEFAULT_ROSTER);
    }

    /**
     * Build the initial authoritative state for the lite scenario, placing the given
     * investigators (from the campaign roster). Clue counts scale by head-count
     * (clueValue × players), so difficulty follows the table size (docs/09 §12).
     */
    public static GameState createState(List<String> investigatorIds) {
        return createCoreState(investigatorIds, "STANDARD");
    }

    private static GameState createCoreState(List<String> investigatorIds, String difficulty) {
        List<String> roster = (investigatorIds == null || investigatorIds.isEmpty())
                ? DEFAULT_ROSTER : investigatorIds;

        GameState state = new GameState(
                ChaosBag.forDifficulty(difficulty),
                new Act("Where There's Smoke…", 2),
                new Agenda("Past Curfew", 5),
                enemyDefs(),
                encounterDeck());

        // --- Locations (prototype LOCATIONS) ---
        state.addLocation(new LocationCard("friends_room", "Your Friend's Room", 2, 2,
                true, List.of("dormitories"), false, null));
        state.addLocation(new LocationCard("dormitories", "Dormitories", 2, 1,
                false, List.of("friends_room", "quad"), false, "servant")); // reveal spawns the Servant
        state.addLocation(new LocationCard("quad", "Miskatonic Quad", 1, 1,
                false, List.of("dormitories", "science", "library"), false, null));
        state.addLocation(new LocationCard("science", "Science Building", 3, 2,
                false, List.of("quad"), false, null));
        state.addLocation(new LocationCard("library", "Orne Library", 4, 2,
                false, List.of("quad"), true, null)); // victory location

        // --- Investigators (from roster) ---
        for (String id : roster) {
            Investigator inv = buildInvestigator(id);
            inv.setActionsRemaining(3);   // round 1 skips Mythos (docs/05 §1); 3 actions each
            state.addInvestigator(inv);
        }
        state.setActiveInvestigatorId(roster.get(0));

        // Starting location is revealed at setup: place clueValue × players.
        int players = state.getInvestigators().size();
        LocationCard start = state.location("friends_room");
        start.setClues(start.getClueValue() * players);

        return state;
    }

    /**
     * Investigator registry — skills / health / sanity / a representative opening hand,
     * ported from the deckbuilder's INVESTIGATORS table (prototype/deckbuilder.html).
     * The lite engine only uses hand cards for skill-test commits, so each investigator
     * gets a fixed opening hand of skill cards (full deck-draw comes in a later phase).
     */
    public static Investigator buildInvestigator(String id) {
        return switch (id) {
            case "joe_diamond" -> {
                Investigator inv = new Investigator("joe_diamond", "Joe Diamond",
                        new Skills(2, 4, 3, 3), 7, 7, 5, "friends_room");
                addHand(inv, "joe_diamond",
                        card("Vicious Blow", SkillIcon.COMBAT),
                        card("Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT),
                        card("Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD),
                        card("Deduction", SkillIcon.INTELLECT),
                        card("Perception", SkillIcon.INTELLECT, SkillIcon.INTELLECT));
                yield inv;
            }
            case "daniela" -> {
                Investigator inv = new Investigator("daniela", "Daniela Reyes",
                        new Skills(3, 2, 4, 2), 8, 6, 5, "friends_room");
                addHand(inv, "daniela",
                        card("Vicious Blow", SkillIcon.COMBAT),
                        card("Vicious Blow", SkillIcon.COMBAT),
                        card("Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT),
                        card("Guts", SkillIcon.WILLPOWER, SkillIcon.WILLPOWER),
                        card("Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD));
                yield inv;
            }
            case "dexter_drake" -> {
                Investigator inv = new Investigator("dexter_drake", "Dexter Drake",
                        new Skills(4, 2, 2, 3), 6, 8, 5, "friends_room");
                addHand(inv, "dexter_drake",
                        card("Soul Link", SkillIcon.WILLPOWER, SkillIcon.WILLPOWER),
                        card("Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT),
                        card("Guts", SkillIcon.WILLPOWER, SkillIcon.WILLPOWER),
                        card("Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD),
                        card("Deduction", SkillIcon.INTELLECT));
                yield inv;
            }
            default -> throw new IllegalArgumentException("Unknown investigator: " + id);
        };
    }

    /** Whether {@code id} is a pickable investigator this scenario can place. */
    public static boolean isKnownInvestigator(String id) {
        return "joe_diamond".equals(id) || "daniela".equals(id) || "dexter_drake".equals(id);
    }

    private record CardSpec(String name, SkillIcon[] icons) {}
    private static CardSpec card(String name, SkillIcon... icons) { return new CardSpec(name, icons); }
    private static void addHand(Investigator inv, String prefix, CardSpec... specs) {
        int n = 1;
        for (CardSpec s : specs) {
            inv.getHand().add(CardInstance.skill(prefix + "-c" + (n++), s.name(), s.icons()));
        }
    }

    // ------------------------------------------------------------------
    // 測試沙盒(docs/11):專供試牌組 / 試特殊卡
    //   線索充足、弱敵(訓練假人,0 傷害)、高資源、手牌塞滿可打的特殊卡、
    //   密謀幾乎不推進(無時間壓力)、神話不抽卡。
    // ------------------------------------------------------------------
    public static GameState createSandbox(List<String> investigatorIds) {
        return createSandbox(investigatorIds, "STANDARD");
    }

    public static GameState createSandbox(List<String> investigatorIds, String difficulty) {
        List<String> roster = (investigatorIds == null || investigatorIds.isEmpty()) ? DEFAULT_ROSTER : investigatorIds;

        GameState state = new GameState(
                ChaosBag.forDifficulty(difficulty),
                new Act("訓練:湊齊 3 線索", 3),
                new Agenda("計時(測試 · 極慢)", 99),   // 幾乎不推進 → 無時間壓力
                sandboxEnemyDefs(),
                List.of());                             // 空遭遇牌堆(神話不抽卡)

        state.addLocation(new LocationCard("test_hub", "測試大廳", 1, 5,
                true, List.of("test_yard"), false, null));        // 遮蔽低、線索多(clueValue5×人數)
        state.addLocation(new LocationCard("test_yard", "訓練場", 2, 2,
                true, List.of("test_hub"), false, "dummy"));      // 已揭示;進入生「訓練假人」

        for (String id : roster) {
            Investigator inv = buildInvestigator(id);
            inv.setLocationId("test_hub");   // buildInvestigator 預設 friends_room,沙盒沒有 → 改測試大廳
            inv.setActionsRemaining(3);
            inv.gainResources(10);           // 5 → 15,夠打所有測試卡
            sandboxHand(inv, id);            // 手牌換成可打的特殊卡
            state.addInvestigator(inv);
        }
        state.setActiveInvestigatorId(roster.get(0));

        int players = state.getInvestigators().size();
        LocationCard hub = state.location("test_hub");
        hub.setClues(hub.getClueValue() * players);
        return state;
    }

    /** 沙盒手牌:一組可直接打出/投入的特殊測試卡(對應 RulesEngine.applyCardEffect)。 */
    private static void sandboxHand(Investigator inv, String prefix) {
        inv.getHand().clear();
        int n = 1;
        inv.getHand().add(CardInstance.event(prefix + "-x" + (n++), "Emergency Cache", 0));
        inv.getHand().add(CardInstance.event(prefix + "-x" + (n++), "Working a Hunch", 2, SkillIcon.INTELLECT));
        inv.getHand().add(CardInstance.event(prefix + "-x" + (n++), "First Aid", 1));
        inv.getHand().add(CardInstance.asset(prefix + "-x" + (n++), "Magnifying Glass", 1, SkillIcon.INTELLECT));
        inv.getHand().add(CardInstance.asset(prefix + "-x" + (n++), "Machete", 3, SkillIcon.COMBAT));
        inv.getHand().add(CardInstance.skill(prefix + "-x" + (n++), "Deduction", SkillIcon.INTELLECT));
        inv.getHand().add(CardInstance.skill(prefix + "-x" + (n++), "Vicious Blow", SkillIcon.COMBAT));
        inv.getHand().add(CardInstance.skill(prefix + "-x" + (n++), "Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD));
    }

    /** 訓練假人:戰2 / 生命3 / 閃2,不造成傷害/恐懼,無關鍵字 —— 安全反覆練戰鬥/閃避。 */
    private static Map<String, EnemyDef> sandboxEnemyDefs() {
        Map<String, EnemyDef> defs = new LinkedHashMap<>();
        defs.put("dummy", new EnemyDef("dummy", "訓練假人", 2, 3, 2, 0, 0, List.of()));
        return defs;
    }

    /** Enemy definitions (prototype ENEMY_DEF). */
    private static Map<String, EnemyDef> enemyDefs() {
        Map<String, EnemyDef> defs = new LinkedHashMap<>();
        defs.put("servant", new EnemyDef("servant", "Servant of Flame",
                4, 5, 2, 1, 1, List.of(Keyword.HUNTER, Keyword.RETALIATE)));
        defs.put("acolyte", new EnemyDef("acolyte", "Acolyte",
                1, 1, 2, 1, 0, List.of(Keyword.HUNTER)));
        defs.put("wraith", new EnemyDef("wraith", "Fire Vampire",
                3, 3, 3, 0, 2, List.of(Keyword.HUNTER, Keyword.ALERT)));
        return defs;
    }

    /** Encounter deck (prototype encounterDeck), drawn one per investigator each Mythos. */
    private static List<EncounterCard> encounterDeck() {
        return List.of(
                EncounterCard.enemy("acolyte"),
                EncounterCard.treachery("Frozen in Fear", EncounterCard.Effect.HORROR, 1),
                EncounterCard.enemy("wraith"),
                EncounterCard.treachery("Cosmic Evils", EncounterCard.Effect.DOOM, 1),
                EncounterCard.enemy("acolyte"),
                EncounterCard.treachery("Quiet Halls", EncounterCard.Effect.NOTHING, 0));
    }
}
