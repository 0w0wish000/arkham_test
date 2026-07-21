package com.arkham.server.dto;

import com.arkham.engine.model.IntentAction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Client → Server messages. Mirrors {@code ClientMessage} in protocol/messages.ts.
 * The {@code "type"} field selects the concrete record; Jackson reads it as the type
 * discriminator (it is not bound to a record component).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientMessage.Join.class, name = "JOIN"),
        @JsonSubTypes.Type(value = ClientMessage.Intent.class, name = "INTENT"),
        @JsonSubTypes.Type(value = ClientMessage.ChoiceResponse.class, name = "CHOICE_RESPONSE"),
        @JsonSubTypes.Type(value = ClientMessage.SaveRequest.class, name = "SAVE_REQUEST"),
        @JsonSubTypes.Type(value = ClientMessage.SaveVote.class, name = "SAVE_VOTE"),
        @JsonSubTypes.Type(value = ClientMessage.Resume.class, name = "RESUME"),
        @JsonSubTypes.Type(value = ClientMessage.Ping.class, name = "PING"),
        @JsonSubTypes.Type(value = ClientMessage.Hello.class, name = "HELLO"),
        @JsonSubTypes.Type(value = ClientMessage.CreateCampaign.class, name = "CREATE_CAMPAIGN"),
        @JsonSubTypes.Type(value = ClientMessage.JoinSession.class, name = "JOIN_SESSION"),
        @JsonSubTypes.Type(value = ClientMessage.LeaveSession.class, name = "LEAVE_SESSION"),
        @JsonSubTypes.Type(value = ClientMessage.PickInvestigator.class, name = "PICK_INVESTIGATOR"),
        @JsonSubTypes.Type(value = ClientMessage.SetDeck.class, name = "SET_DECK"),
        @JsonSubTypes.Type(value = ClientMessage.ReadyDeck.class, name = "READY_DECK"),
        @JsonSubTypes.Type(value = ClientMessage.ForceStart.class, name = "FORCE_START"),
        @JsonSubTypes.Type(value = ClientMessage.OfferSave.class, name = "OFFER_SAVE"),
        @JsonSubTypes.Type(value = ClientMessage.ReadyLoad.class, name = "READY_LOAD"),
        @JsonSubTypes.Type(value = ClientMessage.SitOut.class, name = "SIT_OUT"),
        @JsonSubTypes.Type(value = ClientMessage.ProposeNewCharacter.class, name = "PROPOSE_NEW_CHARACTER"),
        @JsonSubTypes.Type(value = ClientMessage.ClaimSeat.class, name = "CLAIM_SEAT"),
        @JsonSubTypes.Type(value = ClientMessage.ApplyLog.class, name = "APPLY_LOG"),
        @JsonSubTypes.Type(value = ClientMessage.Vote.class, name = "VOTE"),
        @JsonSubTypes.Type(value = ClientMessage.ResolveChapter.class, name = "RESOLVE_CHAPTER")
})
public sealed interface ClientMessage
        permits ClientMessage.Join, ClientMessage.Intent, ClientMessage.ChoiceResponse,
                ClientMessage.SaveRequest, ClientMessage.SaveVote, ClientMessage.Resume, ClientMessage.Ping,
                ClientMessage.Hello, ClientMessage.CreateCampaign, ClientMessage.JoinSession, ClientMessage.LeaveSession,
                ClientMessage.PickInvestigator, ClientMessage.SetDeck, ClientMessage.ReadyDeck, ClientMessage.ForceStart,
                ClientMessage.OfferSave, ClientMessage.ReadyLoad, ClientMessage.SitOut,
                ClientMessage.ProposeNewCharacter, ClientMessage.ClaimSeat, ClientMessage.ApplyLog, ClientMessage.Vote,
                ClientMessage.ResolveChapter {

    /** {@code { type:"JOIN", sessionId, investigatorId }} */
    record Join(String sessionId, String investigatorId) implements ClientMessage {}

    /** {@code { type:"INTENT", action, payload? }} */
    record Intent(IntentAction action, Map<String, Object> payload) implements ClientMessage {}

    /** {@code { type:"CHOICE_RESPONSE", requestId, choice }} */
    record ChoiceResponse(String requestId, ChoiceResponseBody choice) implements ClientMessage {}

    /** {@code { type:"PING" }} */
    record Ping() implements ClientMessage {}

    /** {@code { type:"SAVE_REQUEST" }} — 玩家發起「保存並離開」;伺服器隨即向全員發 SAVE_PROMPT */
    record SaveRequest() implements ClientMessage {}

    /** {@code { type:"SAVE_VOTE", requestId, vote }} — 回應存檔提示 */
    record SaveVote(String requestId, boolean vote) implements ClientMessage {}

    /** {@code { type:"RESUME", state }} — host 送回存檔的 state 以重建對局 */
    record Resume(Object state) implements ClientMessage {}

    /** {@code { type:"HELLO", playerId, displayName }} — 連上後自報身分(docs/09) */
    record Hello(String playerId, String displayName) implements ClientMessage {}

    /** {@code { type:"CREATE_CAMPAIGN", name, campaignKey, difficulty }} — 開新檔案建桌 */
    record CreateCampaign(String name, String campaignKey, String difficulty) implements ClientMessage {}

    /** {@code { type:"JOIN_SESSION", campaignId }} — 加入進行中桌次 */
    record JoinSession(String campaignId) implements ClientMessage {}

    /** {@code { type:"LEAVE_SESSION" }} — 離桌回主選單 */
    record LeaveSession() implements ClientMessage {}

    /** {@code { type:"PICK_INVESTIGATOR", investigatorId }} — 牌組大廳選角(docs/09 §8.2) */
    record PickInvestigator(String investigatorId) implements ClientMessage {}

    /** {@code { type:"SET_DECK", deck, xp }} — 提交/更新牌組(deck=卡名清單) */
    record SetDeck(List<String> deck, int xp) implements ClientMessage {}

    /** {@code { type:"READY_DECK", ready }} — 牌組完成/反悔(屏障 B) */
    record ReadyDeck(boolean ready) implements ClientMessage {}

    /** {@code { type:"FORCE_START" }} — 主機強制越過牌組屏障開打 */
    record ForceStart() implements ClientMessage {}

    /** {@code { type:"OFFER_SAVE", save }} — 用本機存檔開桌/載入(docs/09 §7) */
    record OfferSave(CampaignSave save) implements ClientMessage {}

    /** {@code { type:"READY_LOAD", ready }} — 我已載入戰役快照(屏障 A) */
    record ReadyLoad(boolean ready) implements ClientMessage {}

    /** {@code { type:"SIT_OUT", sitOut }} — 本章中離(true)/ 歸隊(false)(docs/09 §9) */
    record SitOut(boolean sitOut) implements ClientMessage {}

    /** {@code { type:"PROPOSE_NEW_CHARACTER", playerId }} — 對死亡者發起換角投票(docs/09 §10) */
    record ProposeNewCharacter(String playerId) implements ClientMessage {}

    /** {@code { type:"CLAIM_SEAT", targetPlayerId }} — 認領離線席位(docs/09 P6;換裝置回歸) */
    record ClaimSeat(String targetPlayerId) implements ClientMessage {}

    /** {@code { type:"APPLY_LOG", action, … }} — 套用劇本指示(docs/09 §11.5 混合制,D7) */
    record ApplyLog(String action, String targetPlayerId, String cardName,
                    Integer physicalDelta, Integer mentalDelta, String text) implements ClientMessage {}

    /** {@code { type:"VOTE", requestId, yes }} — 換角 / 席位認領投票 */
    record Vote(String requestId, boolean yes) implements ClientMessage {}

    /** {@code { type:"RESOLVE_CHAPTER", resolutionId }} — D2 章末結局投票:選定本章到達的結局 */
    record ResolveChapter(String resolutionId) implements ClientMessage {}

    /**
     * The {@code choice} union in protocol/messages.ts. All fields optional; only the
     * one matching the request's kind is populated (COMMIT_CARDS ⇒ committedCardIds).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceResponseBody(List<String> committedCardIds, List<String> targetIds, String optionId) {}
}
