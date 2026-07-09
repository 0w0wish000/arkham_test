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

    /** Convenience: a fresh engine seeded for deterministic play/replay. */
    public static RulesEngine newEngine(long seed) {
        return new RulesEngine(createState(), new SeededRng(seed));
    }

    /** Build the initial authoritative state for the lite scenario. */
    public static GameState createState() {
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

        // --- Investigators ---
        Investigator joe = new Investigator(JOE, "Joe Diamond",
                new Skills(2, 4, 3, 3), 7, 7, 5, "friends_room");
        joe.getHand().add(CardInstance.skill("c1", "Vicious Blow", SkillIcon.COMBAT));
        joe.getHand().add(CardInstance.skill("c2", "Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT));
        joe.getHand().add(CardInstance.skill("c3", "Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD));
        joe.getHand().add(CardInstance.skill("c4", "Deduction", SkillIcon.INTELLECT));
        state.addInvestigator(joe);

        Investigator daniela = new Investigator(DANIELA, "Daniela Reyes",
                new Skills(3, 2, 4, 2), 8, 6, 5, "friends_room");
        daniela.getHand().add(CardInstance.skill("c5", "Vicious Blow", SkillIcon.COMBAT));
        daniela.getHand().add(CardInstance.skill("c6", "Guts", SkillIcon.WILLPOWER, SkillIcon.WILLPOWER));
        state.addInvestigator(daniela);

        state.setActiveInvestigatorId(JOE);

        // Joe's turn (round 1 skips Mythos, docs/05 §1); each investigator gets 3 actions.
        joe.setActionsRemaining(3);
        daniela.setActionsRemaining(3);

        // Starting location is revealed at setup: place clueValue × players.
        int players = state.getInvestigators().size();
        LocationCard start = state.location("friends_room");
        start.setClues(start.getClueValue() * players);

        return state;
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
