import type {
  ClientMessage, ServerMessage, ChoiceRequestMsg, SavePromptMsg, SaveSnapshotMsg,
  LobbyMsg, SessionRosterMsg, CampaignSnapshotMsg, LogHistoryMsg, VotePromptMsg, CampaignLogMsg,
  ResolutionPromptMsg,
  GameStateView, IntentAction, ChoiceResponse, Difficulty, CampaignSave,
} from "../protocol";

interface Handlers {
  // 大廳(docs/09)
  onLobby?: (msg: LobbyMsg) => void;
  onSessionRoster?: (msg: SessionRosterMsg) => void;
  onCampaignSnapshot?: (msg: CampaignSnapshotMsg) => void;   // 存檔複製到本機
  onLogHistory?: (msg: LogHistoryMsg) => void;               // 載入後 log 回放
  onVotePrompt?: (msg: VotePromptMsg) => void;               // 死亡換角/席位認領投票彈窗
  onCampaignLog?: (msg: CampaignLogMsg) => void;             // 戰役日誌同步(D6)
  onResolutionPrompt?: (msg: ResolutionPromptMsg) => void;   // 章末結局投票(D2)
  // 戰役板
  onState?: (view: GameStateView) => void;
  onEvent?: (message: string) => void;
  onChoiceRequest?: (msg: ChoiceRequestMsg) => void;
  onSavePrompt?: (msg: SavePromptMsg) => void;
  onSaveSnapshot?: (msg: SaveSnapshotMsg) => void;
  onError?: (message: string) => void;
}

/**
 * 與 Java 權威伺服器的 WebSocket 連線。
 * 連上後由呼叫端主動 `hello()` 進大廳(不再自動 JOIN 房間);
 * 只送「意圖 / 決策 / 大廳指令」,只收「狀態 / 事件 / 大廳訊息」。
 */
export class Connection {
  private ws?: WebSocket;

  constructor(private url: string, private handlers: Handlers = {}) {}

  /** 只負責把連線開起來(不自動 JOIN);resolve 於 open。 */
  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => resolve();
      ws.onerror = (e) => reject(e);
      ws.onmessage = (ev) => this.handle(JSON.parse(ev.data) as ServerMessage);
    });
  }

  private handle(msg: ServerMessage) {
    switch (msg.type) {
      case "LOBBY": this.handlers.onLobby?.(msg); break;
      case "SESSION_ROSTER": this.handlers.onSessionRoster?.(msg); break;
      case "CAMPAIGN_SNAPSHOT": this.handlers.onCampaignSnapshot?.(msg); break;
      case "LOG_HISTORY": this.handlers.onLogHistory?.(msg); break;
      case "VOTE_PROMPT": this.handlers.onVotePrompt?.(msg); break;
      case "CAMPAIGN_LOG": this.handlers.onCampaignLog?.(msg); break;
      case "RESOLUTION_PROMPT": this.handlers.onResolutionPrompt?.(msg); break;
      case "STATE": this.handlers.onState?.(msg.view); break;
      case "EVENT": this.handlers.onEvent?.(msg.message); break;
      case "CHOICE_REQUEST": this.handlers.onChoiceRequest?.(msg); break;
      case "SAVE_PROMPT": this.handlers.onSavePrompt?.(msg); break;
      case "SAVE_SNAPSHOT": this.handlers.onSaveSnapshot?.(msg); break;
      case "ERROR": this.handlers.onError?.(msg.message); break;
      case "PONG": break;
    }
  }

  send(msg: ClientMessage) { this.ws?.send(JSON.stringify(msg)); }

  // ---- 大廳(docs/09)----
  hello(playerId: string, displayName: string) { this.send({ type: "HELLO", playerId, displayName }); }
  createCampaign(name: string, campaignKey: string, difficulty: Difficulty) {
    this.send({ type: "CREATE_CAMPAIGN", name, campaignKey, difficulty });
  }
  joinSession(campaignId: string) { this.send({ type: "JOIN_SESSION", campaignId }); }
  leaveSession() { this.send({ type: "LEAVE_SESSION" }); }
  // 牌組大廳(docs/09 §8.2)
  pickInvestigator(investigatorId: string) { this.send({ type: "PICK_INVESTIGATOR", investigatorId }); }
  setDeck(deck: string[], xp: number) { this.send({ type: "SET_DECK", deck, xp }); }
  readyDeck(ready: boolean) { this.send({ type: "READY_DECK", ready }); }
  forceStart() { this.send({ type: "FORCE_START" }); }
  // 加載存檔(docs/09 §7)
  offerSave(save: CampaignSave) { this.send({ type: "OFFER_SAVE", save }); }
  readyLoad(ready: boolean) { this.send({ type: "READY_LOAD", ready }); }
  sitOut(sitOut: boolean) { this.send({ type: "SIT_OUT", sitOut }); }
  // 死亡換角投票(docs/09 §10)
  proposeNewCharacter(playerId: string) { this.send({ type: "PROPOSE_NEW_CHARACTER", playerId }); }
  claimSeat(targetPlayerId: string) { this.send({ type: "CLAIM_SEAT", targetPlayerId }); }   // 席位認領(P6)
  applyLog(req: Omit<import("../protocol").ApplyLogMsg, "type">) { this.send({ type: "APPLY_LOG", ...req }); }   // 劇本指示(D7)
  vote(requestId: string, yes: boolean) { this.send({ type: "VOTE", requestId, yes }); }
  resolveChapter(resolutionId: string) { this.send({ type: "RESOLVE_CHAPTER", resolutionId }); }   // 章末結局投票(D2)

  // ---- 戰役板 ----
  join(sessionId: string, investigatorId: string) { this.send({ type: "JOIN", sessionId, investigatorId }); }
  intent(action: IntentAction, payload?: Record<string, unknown>) {
    this.send({ type: "INTENT", action, payload });
  }
  respond(requestId: string, choice: ChoiceResponse) {
    this.send({ type: "CHOICE_RESPONSE", requestId, choice });
  }
  saveRequest() { this.send({ type: "SAVE_REQUEST" }); }
  saveVote(requestId: string, vote: boolean) { this.send({ type: "SAVE_VOTE", requestId, vote }); }
  resume(state: unknown) { this.send({ type: "RESUME", state }); }
}
