package com.arkham.engine.model;

/**
 * The current agenda (the encroaching doom). Reaching its doom threshold advances
 * the agenda and, in the lite scenario, loses the game. Projects to {@code AgendaView}.
 */
public final class Agenda {

    private final String name;
    private final int threshold;
    private int doom;

    public Agenda(String name, int threshold) {
        this.name = name;
        this.threshold = threshold;
    }

    public String getName() { return name; }
    public int getThreshold() { return threshold; }
    public int getDoom() { return doom; }

    public void addDoom(int n) { this.doom += n; }
    public boolean atThreshold() { return doom >= threshold; }
}
