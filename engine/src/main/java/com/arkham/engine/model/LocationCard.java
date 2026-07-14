package com.arkham.engine.model;

import java.util.List;

/**
 * Authoritative, mutable location state. Projects to {@code LocationView}.
 * A location enters play unrevealed (docs/05 §10.10); the first investigator to
 * move in reveals it and places {@code clueValue × investigatorCount} clues.
 */
public final class LocationCard {

    private final String id;
    private final String name;
    private final int shroud;
    private final int clueValue;
    private final List<String> connections;
    private final boolean victory;
    /** Enemy definition key to spawn when this location is first revealed (nullable). */
    private final String spawnDefKey;

    private boolean revealed;
    private int clues;
    /** Doom on this location — counts toward the agenda's threshold (rules: "all doom in play"). */
    private int doom;

    public LocationCard(String id, String name, int shroud, int clueValue,
                        boolean revealed, List<String> connections,
                        boolean victory, String spawnDefKey) {
        this.id = id;
        this.name = name;
        this.shroud = shroud;
        this.clueValue = clueValue;
        this.revealed = revealed;
        this.connections = List.copyOf(connections);
        this.victory = victory;
        this.spawnDefKey = spawnDefKey;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getShroud() { return shroud; }
    public int getClueValue() { return clueValue; }
    public List<String> getConnections() { return connections; }
    public boolean isVictory() { return victory; }
    public String getSpawnDefKey() { return spawnDefKey; }

    public boolean isRevealed() { return revealed; }
    public int getClues() { return clues; }
    public int getDoom() { return doom; }

    public void setRevealed(boolean revealed) { this.revealed = revealed; }
    public void setClues(int clues) { this.clues = clues; }
    public void removeClue() { if (clues > 0) clues--; }

    public void addDoom(int n) { this.doom += Math.max(0, n); }
    public void clearDoom() { this.doom = 0; }

    public boolean connectsTo(String otherLocationId) {
        return connections.contains(otherLocationId);
    }
}
