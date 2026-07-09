package com.arkham.engine.view;

/** The act row. Mirrors {@code ActView} in protocol/messages.ts. */
public record ActView(String name, int cluesSpent, int threshold) {}
