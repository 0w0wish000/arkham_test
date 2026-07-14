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
        List<String> roster = (investigatorIds == null || investigatorIds.isEmpty())
                ? DEFAULT_ROSTER : investigatorIds;

        GameState state = new GameState(
                ChaosBag.standard(),
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
