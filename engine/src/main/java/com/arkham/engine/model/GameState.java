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
    private Act act;        // 目前這張幕(A4:推進時換下一張)
    private Agenda agenda;  // 目前這張密謀
    /** A4 幕/密謀牌組:剩餘的牌(空 = 單張劇本,推進即結局)。舊存檔缺欄位 → 空。 */
    private final java.util.List<Act> actQueue = new java.util.ArrayList<>();
    private final java.util.List<Agenda> agendaQueue = new java.util.ArrayList<>();

    private final List<EncounterCard> encounterDeck;
    private int encounterPointer;

    private int enemySeq;
    private boolean gameOver;
    private boolean won;
    private String outcomeMessage;

    @com.fasterxml.jackson.annotation.JsonCreator
    public GameState(@com.fasterxml.jackson.annotation.JsonProperty("chaosBag") ChaosBag chaosBag,
                     @com.fasterxml.jackson.annotation.JsonProperty("act") Act act,
                     @com.fasterxml.jackson.annotation.JsonProperty("agenda") Agenda agenda,
                     @com.fasterxml.jackson.annotation.JsonProperty("enemyDefs") Map<String, EnemyDef> enemyDefs,
                     @com.fasterxml.jackson.annotation.JsonProperty("encounterDeck") List<EncounterCard> encounterDeck) {
        this.chaosBag = chaosBag;
        this.act = act;
        this.agenda = agenda;
        this.enemyDefs = Map.copyOf(enemyDefs);
        this.encounterDeck = new ArrayList<>(encounterDeck);
    }

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

    public java.util.List<Act> getActQueue() { return actQueue; }
    public java.util.List<Agenda> getAgendaQueue() { return agendaQueue; }

    /** 推進到下一張幕;沒有下一張 → null(= 最後一幕,推進即勝利結局)。 */
    public Act advanceActCard() {
        if (actQueue.isEmpty()) return null;
        act = actQueue.remove(0);
        return act;
    }

    /** 推進到下一張密謀;沒有下一張 → null(= 最後一張,達門檻即敗北結局)。 */
    public Agenda advanceAgendaCard() {
        if (agendaQueue.isEmpty()) return null;
        agenda = agendaQueue.remove(0);
        return agenda;
    }

    public List<EncounterCard> getEncounterDeck() { return encounterDeck; }

    public void addInvestigator(Investigator inv) {
        investigators.put(inv.getId(), inv);
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

    /**
     * 開局固定的玩家數 —— 撤退/淘汰不減(官方 p14:人數縮放判定不因撤退改變)。
     * 任何「×玩家數」縮放一律讀這裡,不要讀 investigators.size()。
     */
    private int playerCount;
    public int getPlayerCount() { return playerCount > 0 ? playerCount : investigators.size(); }
    public void lockPlayerCount() { this.playerCount = investigators.size(); }

    /** 仍在場(未淘汰)的調查員。 */
    public java.util.List<Investigator> investigatorsInPlay() {
        return orderedInvestigators().stream().filter(Investigator::isInPlay).toList();
    }

    /** 全員退場 → 劇本以「未達成結局」收場(官方 p41)。 */
    public boolean allEliminated() { return investigatorsInPlay().isEmpty(); }

    public boolean isGameOver() { return gameOver; }
    public boolean isWon() { return won; }
    public String getOutcomeMessage() { return outcomeMessage; }

    public void endGame(boolean won, String message) {
        this.gameOver = true;
        this.won = won;
        this.outcomeMessage = message;
    }
}
