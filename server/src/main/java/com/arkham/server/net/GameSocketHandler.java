package com.arkham.server.net;

import com.arkham.server.dto.ClientMessage;
import com.arkham.server.dto.ServerMessage;
import com.arkham.server.session.GameSession;
import com.arkham.server.session.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;

/**
 * The single WebSocket endpoint handler (protocol.md). Parses each incoming JSON frame
 * into a {@link ClientMessage} and routes it:
 * <ul>
 *   <li>JOIN → attach the socket to a {@link GameSession} and send initial STATE</li>
 *   <li>INTENT → {@code engine.applyIntent} then broadcast EVENTs + STATE (or open a barrier)</li>
 *   <li>CHOICE_RESPONSE → feed the pending commit barrier</li>
 *   <li>PING → PONG</li>
 * </ul>
 * The socket's {@code sessionId} / {@code investigatorId} are stashed in the WebSocket
 * attributes at JOIN so later frames and disconnects can be routed.
 */
@Component
public class GameSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_SESSION = "sessionId";
    private static final String ATTR_INVESTIGATOR = "investigatorId";

    private final SessionManager sessions;
    private final ObjectMapper mapper;

    public GameSocketHandler(SessionManager sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        ClientMessage msg;
        try {
            msg = mapper.readValue(message.getPayload(), ClientMessage.class);
        } catch (Exception parseError) {
            send(ws, new ServerMessage.Error("Malformed message: " + parseError.getMessage()));
            return;
        }

        switch (msg) {
            case ClientMessage.Join join -> {
                GameSession session = sessions.getOrCreate(join.sessionId());
                ws.getAttributes().put(ATTR_SESSION, join.sessionId());
                ws.getAttributes().put(ATTR_INVESTIGATOR, join.investigatorId());
                session.join(join.investigatorId(), ws);
            }
            case ClientMessage.Intent intent -> {
                GameSession session = currentSession(ws);
                String investigatorId = (String) ws.getAttributes().get(ATTR_INVESTIGATOR);
                if (session == null || investigatorId == null) {
                    send(ws, new ServerMessage.Error("JOIN before sending intents"));
                    return;
                }
                session.handleIntent(investigatorId, intent.action(), intent.payload());
            }
            case ClientMessage.ChoiceResponse response -> {
                GameSession session = currentSession(ws);
                if (session == null) {
                    send(ws, new ServerMessage.Error("JOIN before responding to choices"));
                    return;
                }
                session.submitCommit(response.requestId(), committedCardIds(response));
            }
            case ClientMessage.Ping ignored -> send(ws, new ServerMessage.Pong());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        String sessionId = (String) ws.getAttributes().get(ATTR_SESSION);
        String investigatorId = (String) ws.getAttributes().get(ATTR_INVESTIGATOR);
        if (sessionId != null && investigatorId != null) {
            GameSession session = sessions.get(sessionId);
            if (session != null) {
                session.leave(investigatorId);
            }
        }
    }

    private static List<String> committedCardIds(ClientMessage.ChoiceResponse response) {
        ClientMessage.ChoiceResponseBody choice = response.choice();
        if (choice != null && choice.committedCardIds() != null) {
            return choice.committedCardIds();
        }
        return List.of();
    }

    private GameSession currentSession(WebSocketSession ws) {
        String sessionId = (String) ws.getAttributes().get(ATTR_SESSION);
        return sessionId == null ? null : sessions.get(sessionId);
    }

    private void send(WebSocketSession ws, ServerMessage msg) throws IOException {
        ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }
}
