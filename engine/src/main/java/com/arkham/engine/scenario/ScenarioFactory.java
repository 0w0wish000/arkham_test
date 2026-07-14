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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the "Spreading Flames (lite)" starting {@link GameState}: 5 Miskatonic
 * locations, the Servant/Acolyte/Fire Vampire enemy definitions, Joe Diamond and
 * Daniela Reyes (two investigators, so the commit barrier and per-client view filtering
 * are exercised), the "Past Curfew" agenda, the "Where There's Smoke…" act, the encounter
 * deck and the standard 16-token bag.
 *
 * <p>{@link #newEngine} also performs the setup that needs randomness (hence the seed):
 * shuffle the encounter deck, shuffle each player deck, then deal the five-card opening
 * hand — setting any weakness aside, redrawing, and shuffling the set-aside weaknesses
 * back in. The one-time hand adjustment (mulligan) is a player choice and is left to
 * {@link RulesEngine#mulligan}.
 */
public final class ScenarioFactory {

    public static final String JOE = "joe_diamond";
    public static final String DANIELA = "daniela";

    private ScenarioFactory() {}

    /** A fresh, fully set-up engine, seeded for deterministic play/replay. */
    public static RulesEngine newEngine(long seed) {
        GameState state = createState();
        SeededRng rng = new SeededRng(seed);
        setUp(state, rng);
        return new RulesEngine(state, rng);
    }

    /**
     * Randomised setup: shuffle every deck and deal opening hands. Weaknesses drawn into
     * the opening hand are set aside, replaced, and shuffled back into the deck.
     */
    public static void setUp(GameState state, SeededRng rng) {
        rng.shuffle(state.getEncounterDeck());

        for (Investigator inv : state.orderedInvestigators()) {
            rng.shuffle(inv.getDeck());

            List<CardInstance> setAside = new ArrayList<>();
            while (inv.getHand().size() < Investigator.OPENING_HAND_SIZE) {
                CardInstance card = inv.drawFromDeck();
                if (card == null) {
                    break; // deck exhausted (cannot happen with the decks below)
                }
                if (card.isWeakness()) {
                    inv.getHand().remove(card);
                    setAside.add(card); // a weakness is set aside and redrawn
                }
            }
            inv.getDeck().addAll(setAside); // the set-aside weaknesses are shuffled back in
            rng.shuffle(inv.getDeck());
        }
    }

    /** Build the initial authoritative state (no randomness — see {@link #setUp}). */
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

        // --- Investigators (five starting resources each) ---
        Investigator joe = new Investigator(JOE, "Joe Diamond",
                new Skills(2, 4, 3, 3), 7, 7, 5, "friends_room");
        joe.getDeck().addAll(joeDeck());
        state.addInvestigator(joe);

        Investigator daniela = new Investigator(DANIELA, "Daniela Reyes",
                new Skills(3, 2, 4, 2), 8, 6, 5, "friends_room");
        daniela.getDeck().addAll(danielaDeck());
        state.addInvestigator(daniela);

        state.setActiveInvestigatorId(JOE);
        joe.setActionsRemaining(3);
        daniela.setActionsRemaining(3);

        // Starting location is revealed at setup: place clueValue × player count.
        LocationCard start = state.location("friends_room");
        start.setClues(start.getClueValue() * state.getPlayerCount());

        return state;
    }

    /** Joe: an intellect deck with a weapon, an ally and one weakness. */
    private static List<CardInstance> joeDeck() {
        List<CardInstance> deck = new ArrayList<>();
        deck.add(CardInstance.weapon("joe-a1", ".45 Automatic", 4, 1, SkillIcon.COMBAT));
        deck.add(CardInstance.asset("joe-a2", "Beat Cop", 4, SkillIcon.COMBAT));
        deck.add(CardInstance.asset("joe-a3", "Magnifying Glass", 1, SkillIcon.INTELLECT));
        deck.add(CardInstance.event("joe-e1", "Evidence!", 1, SkillIcon.INTELLECT));
        deck.add(CardInstance.event("joe-e2", "Working a Hunch", 2, SkillIcon.INTELLECT));
        deck.add(CardInstance.event("joe-e3", "Dodge", 1, SkillIcon.AGILITY));
        deck.add(CardInstance.skill("joe-s1", "Deduction", SkillIcon.INTELLECT));
        deck.add(CardInstance.skill("joe-s2", "Deduction", SkillIcon.INTELLECT));
        deck.add(CardInstance.skill("joe-s3", "Vicious Blow", SkillIcon.COMBAT));
        deck.add(CardInstance.skill("joe-s4", "Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT));
        deck.add(CardInstance.skill("joe-s5", "Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD));
        deck.add(CardInstance.skill("joe-s6", "Guts", SkillIcon.WILLPOWER, SkillIcon.WILLPOWER));
        deck.add(CardInstance.skill("joe-s7", "Perception", SkillIcon.INTELLECT, SkillIcon.INTELLECT));
        deck.add(CardInstance.skill("joe-s8", "Manual Dexterity", SkillIcon.AGILITY, SkillIcon.AGILITY));
        deck.add(CardInstance.weakness("joe-w1", "Hunch Deck Gone Cold"));
        return deck;
    }

    /** Daniela: a combat deck with a weapon, an ally and one weakness. */
    private static List<CardInstance> danielaDeck() {
        List<CardInstance> deck = new ArrayList<>();
        deck.add(CardInstance.weapon("dan-a1", "Fire Axe", 3, 1, SkillIcon.COMBAT));
        deck.add(CardInstance.weapon("dan-a2", "Sledgehammer", 4, 2, SkillIcon.COMBAT));
        deck.add(CardInstance.asset("dan-a3", "Leather Jacket", 3, SkillIcon.COMBAT));
        deck.add(CardInstance.event("dan-e1", "Dynamite Blast", 5, SkillIcon.COMBAT));
        deck.add(CardInstance.event("dan-e2", "Dodge", 1, SkillIcon.AGILITY));
        deck.add(CardInstance.event("dan-e3", "Emergency Aid", 2, SkillIcon.WILLPOWER));
        deck.add(CardInstance.skill("dan-s1", "Vicious Blow", SkillIcon.COMBAT));
        deck.add(CardInstance.skill("dan-s2", "Vicious Blow", SkillIcon.COMBAT));
        deck.add(CardInstance.skill("dan-s3", "Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT));
        deck.add(CardInstance.skill("dan-s4", "Guts", SkillIcon.WILLPOWER, SkillIcon.WILLPOWER));
        deck.add(CardInstance.skill("dan-s5", "Unexpected Courage", SkillIcon.WILD, SkillIcon.WILD));
        deck.add(CardInstance.skill("dan-s6", "Manual Dexterity", SkillIcon.AGILITY, SkillIcon.AGILITY));
        deck.add(CardInstance.skill("dan-s7", "Perception", SkillIcon.INTELLECT, SkillIcon.INTELLECT));
        deck.add(CardInstance.skill("dan-s8", "Overpower", SkillIcon.COMBAT, SkillIcon.COMBAT));
        deck.add(CardInstance.weakness("dan-w1", "Reckless"));
        return deck;
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

    /** Encounter deck, shuffled in {@link #setUp} and drawn one per investigator each Mythos. */
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
