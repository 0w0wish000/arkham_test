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

    @com.fasterxml.jackson.annotation.JsonCreator
    public LocationCard(@com.fasterxml.jackson.annotation.JsonProperty("id") String id,
                        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                        @com.fasterxml.jackson.annotation.JsonProperty("shroud") int shroud,
                        @com.fasterxml.jackson.annotation.JsonProperty("clueValue") int clueValue,
                        @com.fasterxml.jackson.annotation.JsonProperty("revealed") boolean revealed,
                        @com.fasterxml.jackson.annotation.JsonProperty("connections") List<String> connections,
                        @com.fasterxml.jackson.annotation.JsonProperty("victory") boolean victory,
                        @com.fasterxml.jackson.annotation.JsonProperty("spawnDefKey") String spawnDefKey) {
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

    public void setRevealed(boolean revealed) { this.revealed = revealed; }
    public void setClues(int clues) { this.clues = clues; }
    public void removeClue() { if (clues > 0) clues--; }

    public boolean connectsTo(String otherLocationId) {
        return connections.contains(otherLocationId);
    }
}
