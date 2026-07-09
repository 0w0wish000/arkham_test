package com.arkham.server.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of active game sessions (rooms), keyed by the {@code sessionId}
 * a client supplies when it JOINs. No persistence — this is a LAN / dev scaffold.
 */
@Component
public class SessionManager {

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    public SessionManager(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Get the room for {@code sessionId}, creating a fresh scenario the first time. */
    public GameSession getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> new GameSession(id, mapper));
    }

    public GameSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }
}
