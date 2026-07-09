package com.arkham.server.session;

import com.arkham.engine.RulesEngine;
import com.arkham.engine.event.GameEvent;
import com.arkham.engine.model.ChoiceKind;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.scenario.ScenarioFactory;
import com.arkham.server.dto.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One game room: an authoritative {@link RulesEngine} plus the connected clients
 * (investigatorId → socket). Turns intents into engine calls and pushes each client
 * its own filtered {@code STATE} (protocol.md).
 *
 * <p>It also drives the COMMIT_CARDS <b>barrier</b> (docs/05 §2.1): when an intent opens
 * a skill test, the session sends a {@code CHOICE_REQUEST} to every eligible committer
 * and waits for all connected ones to respond before calling
 * {@link RulesEngine#resolveCommit} — the multi-client synchronisation point.
 *
 * <p>All public methods are {@code synchronized}: a room is single-threaded, which also
 * serialises writes to the (non-thread-safe) WebSocket sessions.
 */
public final class GameSession {

    private final String sessionId;
    private final ObjectMapper mapper;
    private final RulesEngine engine;
    private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

    private Barrier barrier; // non-null while a commit barrier is open

    public GameSession(String sessionId, ObjectMapper mapper) {
        this.sessionId = sessionId;
        this.mapper = mapper;
        // Deterministic per-room seed so a session is reproducible/replayable.
        this.engine = ScenarioFactory.newEngine((long) sessionId.hashCode());
    }

    public String sessionId() {
        return sessionId;
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /** Attach a client as {@code investigatorId} and send it its initial state. */
    public synchronized void join(String investigatorId, WebSocketSession ws) throws IOException {
        if (engine.state().investigator(investigatorId) == null) {
            send(ws, new ServerMessage.Error("Unknown investigator: " + investigatorId));
            return;
        }
        clients.put(investigatorId, ws);
        send(ws, new ServerMessage.State(engine.viewFor(investigatorId)));
    }

    public synchronized void leave(String investigatorId) {
        clients.remove(investigatorId);
    }

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    public synchronized void handleIntent(String investigatorId, IntentAction action,
                                          Map<String, Object> payload) throws IOException {
        List<GameEvent> events;
        try {
            events = engine.applyIntent(investigatorId, action, payload);
        } catch (RuntimeException ex) {
            sendTo(investigatorId, new ServerMessage.Error(ex.getMessage()));
            return;
        }
        broadcastEvents(events);
        if (engine.hasPendingCommit()) {
            openBarrier();
        } else {
            broadcastState();
        }
    }

    // ------------------------------------------------------------------
    // Commit barrier (docs/05 §2.1)
    // ------------------------------------------------------------------

    private void openBarrier() throws IOException {
        RulesEngine.PendingCommit pc = engine.pendingCommit();
        barrier = new Barrier();
        for (String invId : pc.eligibleInvestigatorIds()) {
            WebSocketSession ws = clients.get(invId);
            if (ws == null) {
                barrier.responses.put(invId, List.of()); // absent committer ⇒ auto-skip
                continue;
            }
            String requestId = UUID.randomUUID().toString();
            barrier.requestIdByInv.put(invId, requestId);
            barrier.outstanding.add(invId);
            send(ws, new ServerMessage.ChoiceRequest(requestId, ChoiceKind.COMMIT_CARDS,
                    engine.commitOptionsFor(invId)));
        }
        if (barrier.outstanding.isEmpty()) {
            resolveBarrier(); // nobody left to ask (all eligible committers disconnected)
        }
    }

    /** Record one committer's response; resolve the test once everyone has answered. */
    public synchronized void submitCommit(String requestId, List<String> committedCardIds) throws IOException {
        if (barrier == null) {
            return; // stale / unexpected response
        }
        String invId = barrier.investigatorForRequest(requestId);
        if (invId == null || !barrier.outstanding.contains(invId)) {
            return;
        }
        barrier.responses.put(invId, committedCardIds);
        barrier.outstanding.remove(invId);
        if (barrier.outstanding.isEmpty()) {
            resolveBarrier();
        }
    }

    private void resolveBarrier() throws IOException {
        Map<String, List<String>> responses = barrier.responses;
        barrier = null;
        List<GameEvent> events;
        try {
            events = engine.resolveCommit(responses);
        } catch (RuntimeException ex) {
            broadcast(new ServerMessage.Error(ex.getMessage()));
            return;
        }
        broadcastEvents(events);
        broadcastState();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Push every connected client its own filtered view. */
    public synchronized void broadcastState() throws IOException {
        for (Map.Entry<String, WebSocketSession> e : clients.entrySet()) {
            send(e.getValue(), new ServerMessage.State(engine.viewFor(e.getKey())));
        }
    }

    private void broadcastEvents(List<GameEvent> events) throws IOException {
        for (GameEvent ev : events) {
            broadcast(new ServerMessage.Event(ev.event(), ev.message()));
        }
    }

    private void broadcast(ServerMessage msg) throws IOException {
        for (WebSocketSession ws : clients.values()) {
            send(ws, msg);
        }
    }

    private void sendTo(String investigatorId, ServerMessage msg) throws IOException {
        WebSocketSession ws = clients.get(investigatorId);
        if (ws != null) {
            send(ws, msg);
        }
    }

    private void send(WebSocketSession ws, ServerMessage msg) throws IOException {
        if (ws.isOpen()) {
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        }
    }

    /** Tracks one open commit barrier. */
    private static final class Barrier {
        final Map<String, String> requestIdByInv = new HashMap<>();
        final Set<String> outstanding = new HashSet<>();
        final Map<String, List<String>> responses = new HashMap<>();

        String investigatorForRequest(String requestId) {
            return requestIdByInv.entrySet().stream()
                    .filter(e -> e.getValue().equals(requestId))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
    }
}
