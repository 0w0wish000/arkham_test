package com.arkham.server.dto;

import com.arkham.engine.model.ChoiceKind;
import com.arkham.engine.protocol.ChoiceOptions;
import com.arkham.engine.view.GameStateView;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Server → Client messages. Mirrors {@code ServerMessage} in protocol/messages.ts.
 * Serialising a variant writes {@code "type": "<NAME>"} plus that variant's fields
 * (e.g. STATE ⇒ {@code { type:"STATE", view:{…} }}). The engine's view / choice-option
 * records are reused directly as the payloads.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.State.class, name = "STATE"),
        @JsonSubTypes.Type(value = ServerMessage.Event.class, name = "EVENT"),
        @JsonSubTypes.Type(value = ServerMessage.ChoiceRequest.class, name = "CHOICE_REQUEST"),
        @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "ERROR"),
        @JsonSubTypes.Type(value = ServerMessage.SavePrompt.class, name = "SAVE_PROMPT"),
        @JsonSubTypes.Type(value = ServerMessage.SaveSnapshot.class, name = "SAVE_SNAPSHOT"),
        @JsonSubTypes.Type(value = ServerMessage.Pong.class, name = "PONG"),
        @JsonSubTypes.Type(value = ServerMessage.Lobby.class, name = "LOBBY"),
        @JsonSubTypes.Type(value = ServerMessage.SessionRoster.class, name = "SESSION_ROSTER"),
        @JsonSubTypes.Type(value = ServerMessage.CampaignSnapshot.class, name = "CAMPAIGN_SNAPSHOT"),
        @JsonSubTypes.Type(value = ServerMessage.LogHistory.class, name = "LOG_HISTORY"),
        @JsonSubTypes.Type(value = ServerMessage.VotePrompt.class, name = "VOTE_PROMPT"),
        @JsonSubTypes.Type(value = ServerMessage.CampaignLog.class, name = "CAMPAIGN_LOG")
})
public sealed interface ServerMessage
        permits ServerMessage.State, ServerMessage.Event, ServerMessage.ChoiceRequest,
                ServerMessage.Error, ServerMessage.SavePrompt, ServerMessage.SaveSnapshot, ServerMessage.Pong,
                ServerMessage.Lobby, ServerMessage.SessionRoster,
                ServerMessage.CampaignSnapshot, ServerMessage.LogHistory, ServerMessage.VotePrompt,
                ServerMessage.CampaignLog {

    /** {@code { type:"STATE", view }} — the recipient's filtered snapshot. */
    record State(GameStateView view) implements ServerMessage {}

    /** {@code { type:"EVENT", event, message }} — narration / animation event. */
    record Event(String event, String message) implements ServerMessage {}

    /** {@code { type:"CHOICE_REQUEST", requestId, kind, options }} */
    record ChoiceRequest(String requestId, ChoiceKind kind, ChoiceOptions options) implements ServerMessage {}

    /** {@code { type:"ERROR", message }} */
    record Error(String message) implements ServerMessage {}

    /** {@code { type:"SAVE_PROMPT", requestId, requestedBy }} — 各客戶端彈窗「是否存檔?」 */
    record SavePrompt(String requestId, String requestedBy) implements ServerMessage {}

    /** {@code { type:"SAVE_SNAPSHOT", scenario, round, state, eventLog }} — 存檔文本複製到各玩家本機 */
    record SaveSnapshot(String scenario, int round, Object state,
                        java.util.List<com.arkham.engine.event.GameEvent> eventLog) implements ServerMessage {}

    /** {@code { type:"PONG" }} */
    record Pong() implements ServerMessage {}

    /** {@code { type:"LOBBY", activeSessions }} — 進行中桌次清單(取代 room,docs/09)。 */
    record Lobby(java.util.List<SessionSummary> activeSessions) implements ServerMessage {}

    /** {@code { type:"SESSION_ROSTER", … }} — 一桌的名冊 + 屏障進度(驅動大廳 UI,docs/09)。 */
    record SessionRoster(String campaignId, String name, String campaignKey, String stage,
                         String difficulty, java.util.List<RosterMember> members, boolean canForce,
                         java.util.List<String> deadInvestigators, int currentChapter)
            implements ServerMessage {}

    /** {@code { type:"CAMPAIGN_SNAPSHOT", save }} — 全戰役存檔複製到各玩家本機(docs/09 §7)。 */
    record CampaignSnapshot(CampaignSave save) implements ServerMessage {}

    /** {@code { type:"LOG_HISTORY", entries }} — 載入後回放的出牌/事件紀錄。 */
    record LogHistory(java.util.List<com.arkham.engine.event.GameEvent> entries) implements ServerMessage {}

    /** {@code { type:"VOTE_PROMPT", requestId, subject, reason }} — 換角投票彈窗(docs/09 §10)。 */
    record VotePrompt(String requestId, String subject, String reason) implements ServerMessage {}

    /** {@code { type:"CAMPAIGN_LOG", entries }} — 戰役日誌全量同步(D6;入桌與變更時)。 */
    record CampaignLog(java.util.List<CampaignSave.LogEntry> entries) implements ServerMessage {}
}
