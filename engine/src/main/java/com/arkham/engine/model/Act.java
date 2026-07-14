package com.arkham.engine.model;

/**
 * The current act (the investigators' objective). Advancing it spends clues and,
 * in the lite scenario, wins the game. Projects to {@code ActView}.
 */
public final class Act {

    private final String name;
    private final int threshold;
    private int cluesSpent;

    @com.fasterxml.jackson.annotation.JsonCreator
    public Act(@com.fasterxml.jackson.annotation.JsonProperty("name") String name,
               @com.fasterxml.jackson.annotation.JsonProperty("threshold") int threshold) {
        this.name = name;
        this.threshold = threshold;
    }

    public String getName() { return name; }
    public int getThreshold() { return threshold; }
    public int getCluesSpent() { return cluesSpent; }

    public void spendClues(int n) { this.cluesSpent += n; }
}
