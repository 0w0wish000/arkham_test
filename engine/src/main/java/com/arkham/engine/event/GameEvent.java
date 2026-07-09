package com.arkham.engine.event;

/**
 * A broadcastable narration / animation event produced by the engine. Maps directly
 * to the server's {@code EVENT} message ({@code { type:"EVENT", event, message }} in
 * protocol/messages.ts): {@code event} is a machine code (e.g. "MOVE", "SKILL_TEST",
 * "ENEMY_ATTACK", "MYTHOS", "GAME_OVER"), {@code message} is human-readable text.
 */
public record GameEvent(String event, String message) {

    public static GameEvent of(String event, String message) {
        return new GameEvent(event, message);
    }
}
