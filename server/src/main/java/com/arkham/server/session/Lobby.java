package com.arkham.server.session;

import com.arkham.server.dto.ServerMessage;
import com.arkham.server.dto.SessionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lobby (docs/09) — the registry of active campaign "tables", plus the clients
 * currently sitting at the main menu. Replaces the room concept: a client HELLOs to
 * enter the menu (and receives the {@code LOBBY} table list), then creates or joins a
 * {@link CampaignSession}. Whenever the table list changes, everyone still at the menu
 * gets a fresh {@code LOBBY}.
 */
@Component
public class Lobby {

    private final Map<String, CampaignSession> sessions = new ConcurrentHashMap<>();
    private final Set<WebSocketSession> menuClients = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    public Lobby(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Client is now at the main menu (HELLO'd, not in a table) → push it the table list. */
    public void enterMenu(WebSocketSession ws) throws IOException {
        menuClients.add(ws);
        sendLobby(ws);
    }

    public void leaveMenu(WebSocketSession ws) {
        menuClients.remove(ws);
    }

    public CampaignSession create(String name, String campaignKey, String difficulty) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        CampaignSession s = new CampaignSession(id, name, campaignKey, difficulty, mapper);
        sessions.put(id, s);
        return s;
    }

    public CampaignSession get(String campaignId) {
        return sessions.get(campaignId);
    }

    /** 用一份存檔開/取桌:同 campaignId 若已有桌就沿用,否則從存檔還原(docs/09 §7)。 */
    public CampaignSession getOrRestore(com.arkham.server.dto.CampaignSave save) {
        return sessions.computeIfAbsent(save.campaignId(), id -> CampaignSession.restore(save, mapper));
    }

    public void remove(String campaignId) {
        sessions.remove(campaignId);
    }

    /** Broadcast the updated table list to everyone still at the menu. */
    public void broadcastLobby() throws IOException {
        for (WebSocketSession ws : menuClients) {
            sendLobby(ws);
        }
    }

    private void sendLobby(WebSocketSession ws) throws IOException {
        List<SessionSummary> summaries = new ArrayList<>();
        for (CampaignSession s : sessions.values()) {
            summaries.add(s.summary());
        }
        send(ws, new ServerMessage.Lobby(summaries));
    }

    private void send(WebSocketSession ws, ServerMessage msg) throws IOException {
        if (!ws.isOpen()) return;
        try {
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IllegalStateException | IOException closed) {
            // 客戶端在送出瞬間斷線的競態:忽略
        }
    }
}
