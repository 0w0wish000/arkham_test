package com.arkham.server.net;

import com.arkham.server.dto.ClientMessage;
import com.arkham.server.dto.ServerMessage;
import com.arkham.server.session.CampaignSession;
import com.arkham.server.session.GameSession;
import com.arkham.server.session.Lobby;
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
    private static final String ATTR_PLAYER = "playerId";        // 大廳身分(docs/09)
    private static final String ATTR_DISPLAY = "displayName";
    private static final String ATTR_CAMPAIGN = "campaignId";    // 目前所在桌次

    private final SessionManager sessions;
    private final Lobby lobby;
    private final ObjectMapper mapper;

    public GameSocketHandler(SessionManager sessions, Lobby lobby, ObjectMapper mapper) {
        this.sessions = sessions;
        this.lobby = lobby;
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
                GameSession session = activeGame(ws);
                String investigatorId = activeInvestigator(ws);
                if (session == null || investigatorId == null) {
                    send(ws, new ServerMessage.Error("尚未進入對局"));
                    return;
                }
                session.handleIntent(investigatorId, intent.action(), intent.payload());
                maybeSettleChapter(ws);
            }
            case ClientMessage.ChoiceResponse response -> {
                GameSession session = activeGame(ws);
                if (session == null) {
                    send(ws, new ServerMessage.Error("尚未進入對局"));
                    return;
                }
                ClientMessage.ChoiceResponseBody body = response.choice();
                if (body != null && body.optionId() != null) {
                    session.submitOption(response.requestId(), body.optionId());   // 反應能力回答
                } else {
                    session.submitCommit(response.requestId(), committedCardIds(response));
                }
                maybeSettleChapter(ws);
            }
            case ClientMessage.SaveRequest ignored3 -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) {
                    cs.handleSaveRequest(playerId(ws));      // 戰役級存檔(名冊 + 快照)
                } else {
                    GameSession session = currentSession(ws);   // 舊 room 路徑
                    String investigatorId = (String) ws.getAttributes().get(ATTR_INVESTIGATOR);
                    if (session != null && investigatorId != null) session.handleSaveRequest(investigatorId);
                }
            }
            case ClientMessage.SaveVote vote -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) {
                    cs.submitSaveVote(vote.requestId(), playerId(ws), vote.vote());
                } else {
                    GameSession session = currentSession(ws);
                    String investigatorId = (String) ws.getAttributes().get(ATTR_INVESTIGATOR);
                    if (session != null && investigatorId != null) session.submitSaveVote(vote.requestId(), investigatorId, vote.vote());
                }
            }
            case ClientMessage.Resume resume -> {
                GameSession session = activeGame(ws);
                if (session != null) session.resume(resume.state());
            }
            // ---- 大廳(docs/09)----
            case ClientMessage.Hello hello -> {
                ws.getAttributes().put(ATTR_PLAYER, hello.playerId());
                ws.getAttributes().put(ATTR_DISPLAY, hello.displayName());
                lobby.enterMenu(ws);
            }
            case ClientMessage.CreateCampaign create -> {
                String playerId = (String) ws.getAttributes().get(ATTR_PLAYER);
                String displayName = (String) ws.getAttributes().get(ATTR_DISPLAY);
                if (playerId == null) {
                    send(ws, new ServerMessage.Error("請先 HELLO 自報身分,再建立桌次"));
                    return;
                }
                CampaignSession session = lobby.create(create.name(), create.campaignKey(), create.difficulty());
                ws.getAttributes().put(ATTR_CAMPAIGN, session.campaignId());
                lobby.leaveMenu(ws);
                session.join(playerId, displayName, ws);
                lobby.broadcastLobby();
            }
            case ClientMessage.JoinSession joinSession -> {
                String playerId = (String) ws.getAttributes().get(ATTR_PLAYER);
                String displayName = (String) ws.getAttributes().get(ATTR_DISPLAY);
                if (playerId == null) {
                    send(ws, new ServerMessage.Error("請先 HELLO 自報身分,再加入桌次"));
                    return;
                }
                CampaignSession session = lobby.get(joinSession.campaignId());
                if (session == null) {
                    send(ws, new ServerMessage.Error("找不到桌次:" + joinSession.campaignId()));
                    return;
                }
                ws.getAttributes().put(ATTR_CAMPAIGN, session.campaignId());
                lobby.leaveMenu(ws);
                session.join(playerId, displayName, ws);
                lobby.broadcastLobby();
            }
            case ClientMessage.LeaveSession ignoredLeave -> {
                leaveCampaign(ws);
                lobby.enterMenu(ws);        // 回主選單,重收桌次清單
                lobby.broadcastLobby();
            }
            // ---- 牌組大廳(docs/09 §8.2)----
            case ClientMessage.PickInvestigator pick -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.pickInvestigator(playerId(ws), pick.investigatorId());
            }
            case ClientMessage.SetDeck setDeck -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.setDeck(playerId(ws), setDeck.deck(), setDeck.xp());
            }
            case ClientMessage.ReadyDeck ready -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.readyDeck(playerId(ws), ready.ready());
            }
            case ClientMessage.ForceStart ignoredForce -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.forceStart();
            }
            // ---- 加載存檔(docs/09 §7)----
            case ClientMessage.OfferSave offer -> {
                String playerId = playerId(ws);
                String displayName = (String) ws.getAttributes().get(ATTR_DISPLAY);
                if (playerId == null || offer.save() == null || offer.save().campaignId() == null) {
                    send(ws, new ServerMessage.Error("請先 HELLO,並提供有效存檔"));
                    return;
                }
                CampaignSession session = lobby.getOrRestore(offer.save());
                ws.getAttributes().put(ATTR_CAMPAIGN, session.campaignId());
                lobby.leaveMenu(ws);
                session.join(playerId, displayName, ws);   // 接回名冊中的自己;送 SESSION_ROSTER
                lobby.broadcastLobby();
            }
            case ClientMessage.ReadyLoad readyLoad -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.readyLoad(playerId(ws), readyLoad.ready());
            }
            case ClientMessage.SitOut sit -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.sitOut(playerId(ws), sit.sitOut());
            }
            // ---- 死亡換角投票(docs/09 §10)----
            case ClientMessage.ProposeNewCharacter propose -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.proposeNewCharacter(playerId(ws), propose.playerId());
            }
            case ClientMessage.Vote v -> {
                CampaignSession cs = currentCampaign(ws);
                if (cs != null) cs.vote(v.requestId(), playerId(ws), v.yes());
            }
            case ClientMessage.Ping ignored -> send(ws, new ServerMessage.Pong());
        }
    }

    /** Remove this socket's player from its current table (if any); drop the table if empty. */
    private void leaveCampaign(WebSocketSession ws) throws IOException {
        String campaignId = (String) ws.getAttributes().get(ATTR_CAMPAIGN);
        String playerId = (String) ws.getAttributes().get(ATTR_PLAYER);
        if (campaignId != null && playerId != null) {
            CampaignSession session = lobby.get(campaignId);
            if (session != null) {
                session.disconnect(playerId);
                // 全員離線才清桌(接手:有人還在 = 非空 = 保留;全走了 = 清掉,之後從存檔重載)
                if (session.isEmpty()) lobby.remove(campaignId);
            }
            ws.getAttributes().remove(ATTR_CAMPAIGN);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        // 舊 room / 戰役路徑
        String sessionId = (String) ws.getAttributes().get(ATTR_SESSION);
        String investigatorId = (String) ws.getAttributes().get(ATTR_INVESTIGATOR);
        if (sessionId != null && investigatorId != null) {
            GameSession session = sessions.get(sessionId);
            if (session != null) {
                session.leave(investigatorId);
                if (session.isEmpty()) {
                    sessions.remove(sessionId);   // 空房回收(否則舊 room 永不釋放)
                }
            }
        }
        // 大廳路徑(docs/09):離桌 + 退出主選單清單
        try {
            leaveCampaign(ws);
            lobby.leaveMenu(ws);
            lobby.broadcastLobby();
        } catch (IOException ignored) {
            // 連線關閉時的清理失敗不影響其他人
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

    /** D2/D5:對局訊息處理完 → 若勝負已定,觸發跨章結算(回牌組大廳 + XP + 新存檔)。 */
    private void maybeSettleChapter(WebSocketSession ws) throws IOException {
        CampaignSession cs = currentCampaign(ws);
        if (cs != null) cs.settleChapterIfOver();
    }

    private String playerId(WebSocketSession ws) {
        return (String) ws.getAttributes().get(ATTR_PLAYER);
    }

    private CampaignSession currentCampaign(WebSocketSession ws) {
        String campaignId = (String) ws.getAttributes().get(ATTR_CAMPAIGN);
        return campaignId == null ? null : lobby.get(campaignId);
    }

    /** The active game for this socket: a started campaign's game, else the old room session. */
    private GameSession activeGame(WebSocketSession ws) {
        CampaignSession cs = currentCampaign(ws);
        if (cs != null && cs.game() != null) return cs.game();
        return currentSession(ws);
    }

    /** The investigator this socket controls: campaign pick, else the old room attr. */
    private String activeInvestigator(WebSocketSession ws) {
        CampaignSession cs = currentCampaign(ws);
        if (cs != null) {
            String inv = cs.investigatorFor(playerId(ws));
            if (inv != null) return inv;
        }
        return (String) ws.getAttributes().get(ATTR_INVESTIGATOR);
    }

    private void send(WebSocketSession ws, ServerMessage msg) throws IOException {
        ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
    }
}
