package com.arkham.engine.model;

import com.arkham.engine.ChaosBag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete, authoritative game state. Mutated in place by the
 * {@link com.arkham.engine.RulesEngine}; never sent to a client directly — clients
 * receive per-player filtered {@code GameStateView}s.
 *
 * <p>Investigators are stored in an ordered map so player order (docs/05 §1) is
 * stable. Locations are keyed by id; enemies by their spawned id.
 */
public final class GameState {

    private int round = 1;
    private Phase phase = Phase.INVESTIGATION;
    private String activeInvestigatorId;

    private final Map<String, Investigator> investigators = new LinkedHashMap<>();
    private final Map<String, LocationCard> locations = new LinkedHashMap<>();
    private final Map<String, EnemyCard> enemies = new LinkedHashMap<>();
    private final Map<String, EnemyDef> enemyDefs;

    private final ChaosBag chaosBag;
    private final Act act;
    private final Agenda agenda;

    private final List<EncounterCard> encounterDeck;
    private int encounterPointer;

    private int enemySeq;
    private boolean gameOver;
    private boolean won;
    private String outcomeMessage;

    /**
     * The player count fixed at setup. Resigning does <b>not</b> reduce it (rules:
     * "撤退後在玩家人數判定時不會扣除"), so anything scaled by player count — clue
     * placement above all — must read this, never {@code investigators.size()}.
     */
    private int playerCount;

    public GameState(ChaosBag chaosBag, Act act, Agenda agenda,
                     Map<String, EnemyDef> enemyDefs, List<EncounterCard> encounterDeck) {
        this.chaosBag = chaosBag;
        this.act = act;
        this.agenda = agenda;
        this.enemyDefs = Map.copyOf(enemyDefs);
        this.encounterDeck = new ArrayList<>(encounterDeck);
    }

    public int getPlayerCount() { return playerCount; }

    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
    public void incrementRound() { this.round++; }

    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }

    public String getActiveInvestigatorId() { return activeInvestigatorId; }
    public void setActiveInvestigatorId(String id) { this.activeInvestigatorId = id; }

    public Map<String, Investigator> getInvestigators() { return investigators; }
    public Map<String, LocationCard> getLocations() { return locations; }
    public Map<String, EnemyCard> getEnemies() { return enemies; }
    public Map<String, EnemyDef> getEnemyDefs() { return enemyDefs; }

    public ChaosBag getChaosBag() { return chaosBag; }
    public Act getAct() { return act; }
    public Agenda getAgenda() { return agenda; }

    public List<EncounterCard> getEncounterDeck() { return encounterDeck; }

    public void addInvestigator(Investigator inv) {
        investigators.put(inv.getId(), inv);
        playerCount = investigators.size();
        if (activeInvestigatorId == null) activeInvestigatorId = inv.getId();
    }

    public void addLocation(LocationCard loc) { locations.put(loc.getId(), loc); }

    public Investigator investigator(String id) { return investigators.get(id); }
    public LocationCard location(String id) { return locations.get(id); }
    public EnemyCard enemy(String id) { return enemies.get(id); }

    /** Player order: insertion order of the investigators map (docs/05 §1). */
    public List<Investigator> orderedInvestigators() {
        return new ArrayList<>(investigators.values());
    }

    /** Player order, restricted to the investigators still in the game. */
    public List<Investigator> investigatorsInPlay() {
        return investigators.values().stream().filter(Investigator::isInPlay).toList();
    }

    /** Everyone has been defeated or resigned — the scenario ends with no resolution. */
    public boolean allEliminated() {
        return investigatorsInPlay().isEmpty();
    }

    /**
     * The next investigator in seat order (after {@code fromId}, wrapping) who is still in
     * play and has not yet taken their turn; {@code null} if there is none. This is only
     * the <em>default</em> offer — turn order is the team's to choose (docs/05 §4.1), so
     * any other investigator who has not acted may take the turn instead.
     */
    public Investigator nextToAct(String fromId) {
        List<Investigator> order = orderedInvestigators();
        int start = 0;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).getId().equals(fromId)) {
                start = i;
                break;
            }
        }
        for (int step = 1; step <= order.size(); step++) {
            Investigator candidate = order.get((start + step) % order.size());
            if (candidate.isInPlay() && !candidate.isTurnTaken()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * All doom currently in play: on the agenda, on locations and on enemies. The agenda
     * advances when this total reaches its threshold (rules: "場上所有毀滅標記").
     */
    public int totalDoomInPlay() {
        int total = agenda.getDoom();
        for (LocationCard loc : locations.values()) {
            total += loc.getDoom();
        }
        for (EnemyCard e : enemies.values()) {
            total += e.getDoom();
        }
        return total;
    }

    /** Advancing the agenda discards every doom token in play. */
    public void clearAllDoomInPlay() {
        agenda.clearDoom();
        locations.values().forEach(LocationCard::clearDoom);
        enemies.values().forEach(EnemyCard::clearDoom);
    }

    public List<EnemyCard> enemiesAt(String locationId) {
        return enemies.values().stream()
                .filter(e -> locationId.equals(e.getLocationId()))
                .toList();
    }

    public List<String> enemyIdsAt(String locationId) {
        return enemies.values().stream()
                .filter(e -> locationId.equals(e.getLocationId()))
                .map(EnemyCard::getId)
                .toList();
    }

    /** Draw the next encounter card, wrapping around the deck (as the prototype does). */
    public EncounterCard drawEncounter() {
        if (encounterDeck.isEmpty()) return null;
        EncounterCard card = encounterDeck.get(encounterPointer % encounterDeck.size());
        encounterPointer++;
        return card;
    }

    public String nextEnemyId() { return "e" + (++enemySeq); }

    public boolean isGameOver() { return gameOver; }
    public boolean isWon() { return won; }
    public String getOutcomeMessage() { return outcomeMessage; }

    public void endGame(boolean won, String message) {
        this.gameOver = true;
        this.won = won;
        this.outcomeMessage = message;
    }
}
