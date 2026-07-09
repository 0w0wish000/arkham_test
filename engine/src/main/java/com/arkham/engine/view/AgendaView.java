package com.arkham.engine.view;

/** The agenda row. Mirrors {@code AgendaView} in protocol/messages.ts. */
public record AgendaView(String name, int doom, int threshold) {}
