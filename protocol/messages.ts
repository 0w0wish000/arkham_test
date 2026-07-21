/**
 * Arkham 連線協定 — 共享型別(TypeScript)
 * 客戶端直接 import;Java 伺服器以對應的 record/enum 鏡射。
 * 契約說明見 protocol.md。
 */

// ---------- 列舉 ----------
export type Phase = "MYTHOS" | "INVESTIGATION" | "ENEMY" | "UPKEEP";
export type SkillType = "WILLPOWER" | "INTELLECT" | "COMBAT" | "AGILITY";
export type SkillIcon = SkillType | "WILD";
export type Keyword =
  | "HUNTER" | "RETALIATE" | "ALERT" | "ALOOF" | "MASSIVE"
  | "ELUSIVE" | "PATROL" | "PREY" | "PERIL" | "FAST"
  | "SWARMING" | "SURGE" | "HIDDEN";

export type IntentAction =
  | "DRAW" | "GAIN_RESOURCE" | "PLAY_CARD" | "ACTIVATE"
  | "MOVE" | "INVESTIGATE" | "ENGAGE" | "FIGHT" | "EVADE"
  | "PARLEY" | "RESIGN" | "MULLIGAN" | "END_TURN" | "ADVANCE_ACT";

export type ChoiceKind = "COMMIT_CARDS" | "CHOOSE_TARGET" | "CHOOSE_OPTION";

// ---------- 大廳 / 存檔驅動(docs/09)----------
export type Difficulty = "EASY" | "STANDARD" | "HARD" | "EXPERT";
export type SessionStage = "DECKBUILDING" | "IN_SCENARIO";
export type MemberStatus = "ACTIVE" | "SITTING_OUT" | "DEAD";

/** 名冊中一位成員(playerId = 人;investigatorId = 該存檔的角色)。 */
export interface RosterMember {
  playerId: string;
  displayName: string;
  investigatorId: string | null;   // 尚未選角時為 null
  ready: boolean;
  status: MemberStatus;
  connected: boolean;              // 此席位目前是否在線(P6 席位認領的依據)
  physicalTrauma: number;          // 肉體創傷(每點=下章開局 1 傷害;達生命上限→陣亡退役)
  mentalTrauma: number;            // 精神創傷(每點=下章開局 1 恐懼;達理智上限→失常退役)
}

/** 全戰役存檔(docs/09 §5):複製到各玩家本機、可 OFFER_SAVE 載回。 */
export interface SavedMember {
  playerId: string; displayName: string; investigatorId: string | null;
  deck: string[]; xp: number; status: MemberStatus;
  physicalTrauma: number; mentalTrauma: number;   // 創傷跨章保留(docs/09 §9;官方 p20)
}
export interface CampaignSave {
  campaignId: string; name: string; campaignKey: string; difficulty: Difficulty;
  stage: SessionStage;                 // DECKBUILDING | IN_SCENARIO
  roster: SavedMember[];
  deadInvestigators: string[];         // 永久封鎖的角色(docs/09 §10)
  maxXp: number;                       // XP 上限
  snapshot: unknown | null;            // 引擎狀態樹(IN_SCENARIO 才有)
  eventLog: unknown[];
  round: number;
  currentChapter: number;
  campaignLog: CampaignLogEntry[];     // 戰役日誌(D6:旗標/劇本指示套用紀錄;跨章保留)
}

/** 戰役日誌一則(D6/D7):系統記帳「誰在第幾章套用了什麼」。 */
export interface CampaignLogEntry {
  chapter: number;
  by: string;              // displayName（系統寫入的結局/旗標條目 by="系統"）
  action: LogAction;
  text: string;            // 人可讀敘述(如「Alice 的牌組加入 Ancient Stone」/自由記事)
}
// 日誌條目的動作:玩家經 APPLY_LOG 送的 + 系統於章末結局寫入的(RESOLUTION 選定、FLAG 設置分支旗標)
export type LogAction = ApplyLogAction | "RESOLUTION" | "FLAG";
/** 「進行中桌次」清單的一列(取代 room:大家點同一桌就湊在一起)。 */
export interface SessionSummary {
  campaignId: string;
  name: string;
  campaignKey: string;
  difficulty: Difficulty;
  stage: SessionStage;
  memberCount: number;
  members: { displayName: string; investigatorId: string | null }[];
}

// ---------- Client → Server ----------
export interface JoinMsg    { type: "JOIN"; sessionId: string; investigatorId: string; }
export interface IntentMsg  { type: "INTENT"; action: IntentAction; payload?: Record<string, unknown>; }
export interface ChoiceResponseMsg { type: "CHOICE_RESPONSE"; requestId: string; choice: ChoiceResponse; }
export interface SaveRequestMsg { type: "SAVE_REQUEST"; }                       // 發起「保存並離開」
export interface SaveVoteMsg    { type: "SAVE_VOTE"; requestId: string; vote: boolean; }
export interface ResumeMsg      { type: "RESUME"; state: unknown; }             // host 送回存檔重建對局
export interface PingMsg    { type: "PING"; }
// 大廳(docs/09):連上後自報身分 → 建桌 / 加入桌 / 離桌
export interface HelloMsg          { type: "HELLO"; playerId: string; displayName: string; }
export interface CreateCampaignMsg { type: "CREATE_CAMPAIGN"; name: string; campaignKey: string; difficulty: Difficulty; }
export interface JoinSessionMsg    { type: "JOIN_SESSION"; campaignId: string; }
export interface LeaveSessionMsg   { type: "LEAVE_SESSION"; }
// 牌組大廳(docs/09 §8.2):選角 → 提交牌組 → 準備完成(屏障 B);主機可強制開打
export interface PickInvestigatorMsg { type: "PICK_INVESTIGATOR"; investigatorId: string; }
export interface SetDeckMsg          { type: "SET_DECK"; deck: string[]; xp: number; }
export interface ReadyDeckMsg        { type: "READY_DECK"; ready: boolean; }
export interface ForceStartMsg       { type: "FORCE_START"; }
// 加載存檔(docs/09 §7):用本機存檔開/續桌;載入完畢(屏障 A)
export interface OfferSaveMsg        { type: "OFFER_SAVE"; save: CampaignSave; }
export interface ReadyLoadMsg        { type: "READY_LOAD"; ready: boolean; }
export interface SitOutMsg           { type: "SIT_OUT"; sitOut: boolean; }   // 本章中離/歸隊(docs/09 §9)
export interface ProposeNewCharacterMsg { type: "PROPOSE_NEW_CHARACTER"; playerId: string; }  // 死亡換角(§10)
// 席位認領(docs/09 P6):換裝置(新 playerId)認回離線席位,繼承角色/牌組/XP;需其餘在線者表決
export interface ClaimSeatMsg        { type: "CLAIM_SEAT"; targetPlayerId: string; }
// 套用劇本指示(docs/09 §11.5 混合制,D7):人(語音共識)決定、系統記帳+同步。限章節之間。
export type ApplyLogAction = "ADD_CARD" | "REMOVE_CARD" | "ADJUST_TRAUMA" | "RECORD";
export interface ApplyLogMsg {
  type: "APPLY_LOG";
  action: ApplyLogAction;
  targetPlayerId?: string;   // ADD_CARD / REMOVE_CARD / ADJUST_TRAUMA 的對象
  cardName?: string;         // ADD_CARD / REMOVE_CARD
  physicalDelta?: number;    // ADJUST_TRAUMA(可負;下限 0;達上限自動退役)
  mentalDelta?: number;
  text?: string;             // RECORD 自由記事(旗標/密語/劇情抉擇)
}
export interface VoteMsg             { type: "VOTE"; requestId: string; yes: boolean; }
// D2 章末結局投票:選定本章到達的結局(每人一票,最高票套用)
export interface ResolveChapterMsg   { type: "RESOLVE_CHAPTER"; resolutionId: string; }
export type ClientMessage =
  | JoinMsg | IntentMsg | ChoiceResponseMsg | SaveRequestMsg | SaveVoteMsg | ResumeMsg | PingMsg
  | HelloMsg | CreateCampaignMsg | JoinSessionMsg | LeaveSessionMsg
  | PickInvestigatorMsg | SetDeckMsg | ReadyDeckMsg | ForceStartMsg
  | OfferSaveMsg | ReadyLoadMsg | SitOutMsg | ProposeNewCharacterMsg | ClaimSeatMsg | ApplyLogMsg | VoteMsg
  | ResolveChapterMsg;

export type ChoiceResponse =
  | { committedCardIds: string[] }   // COMMIT_CARDS
  | { targetIds: string[] }          // CHOOSE_TARGET
  | { optionId: string };            // CHOOSE_OPTION

// ---------- Server → Client ----------
export interface StateMsg   { type: "STATE"; view: GameStateView; }
export interface EventMsg   { type: "EVENT"; event: string; message: string; }
export interface ChoiceRequestMsg { type: "CHOICE_REQUEST"; requestId: string; kind: ChoiceKind; options: ChoiceOptions; }
export interface ErrorMsg   { type: "ERROR"; message: string; }
export interface SavePromptMsg   { type: "SAVE_PROMPT"; requestId: string; requestedBy: string; }   // 全員彈窗「是否存檔?」
export interface SaveSnapshotMsg { type: "SAVE_SNAPSHOT"; scenario: string; round: number; state: unknown; eventLog: unknown[]; } // 複製到各本機
export interface PongMsg    { type: "PONG"; }
// 大廳(docs/09):桌次清單 + 名冊/屏障進度
export interface LobbyMsg         { type: "LOBBY"; activeSessions: SessionSummary[]; }
export interface SessionRosterMsg { type: "SESSION_ROSTER"; campaignId: string; name: string; campaignKey: string;
  stage: SessionStage | "LOADING" | "RESOLUTION"; difficulty: Difficulty; members: RosterMember[]; canForce: boolean;
  deadInvestigators: string[]; currentChapter: number; }
// 加載存檔(docs/09 §7):全戰役存檔複製到本機;載入後 log 回放
export interface CampaignSnapshotMsg { type: "CAMPAIGN_SNAPSHOT"; save: CampaignSave; }
export interface LogHistoryMsg       { type: "LOG_HISTORY"; entries: { event: string; message: string }[]; }
// 死亡換角投票(docs/09 §10)
export interface VotePromptMsg       { type: "VOTE_PROMPT"; requestId: string; subject: string; reason: string; }
// 戰役日誌(D6):入桌與變更時全量同步
export interface CampaignLogMsg      { type: "CAMPAIGN_LOG"; entries: CampaignLogEntry[]; }
// D2 章末結局投票:對局結束後,系統依實際勝負列出本章可選結局,全員各投一票
export interface ResolutionOption { id: string; label: string; win: boolean; }
export interface ResolutionPromptMsg { type: "RESOLUTION_PROMPT"; requestId: string; chapter: number; options: ResolutionOption[]; }
export type ServerMessage =
  | StateMsg | EventMsg | ChoiceRequestMsg | ErrorMsg | SavePromptMsg | SaveSnapshotMsg | PongMsg
  | LobbyMsg | SessionRosterMsg | CampaignSnapshotMsg | LogHistoryMsg | VotePromptMsg | CampaignLogMsg
  | ResolutionPromptMsg;

export interface CommitCardsOptions {
  skill: SkillType; base: number; difficulty: number;
  eligibleCards: HandCard[]; maxCommit: number;   // 檢定者:大數;同地點隊友:1
}
export interface ChooseTargetOptions { candidates: { id: string; label: string }[]; min: number; max: number; }
export interface ChooseOptionOptions { prompt: string; options: { id: string; label: string }[]; }
export type ChoiceOptions = CommitCardsOptions | ChooseTargetOptions | ChooseOptionOptions;

// ---------- GameStateView(過濾後) ----------
export interface HandCard { cardId: string; name: string; cardType: string; cost: number; skillIcons: SkillIcon[]; }

export interface SelfView {
  investigatorId: string;
  skills: Record<Lowercase<SkillType>, number>;
  health: number; damage: number; sanity: number; horror: number;
  resources: number; cluesHeld: number; actionsRemaining: number;
  locationId: string; hand: HandCard[]; playArea: HandCard[];
  deckCount: number;      // 牌堆剩餘(C-lite 牌組管線)
  turnDone: boolean;      // 本輪已按「我打完了」(END_TURN 屏障)
  elimination: string | null;   // null=在場;DAMAGE/HORROR/RESIGNED=已退場(個別淘汰制)
  engagedEnemyIds: string[];
}
export interface OtherInvestigatorView {
  investigatorId: string; locationId: string; damage: number; horror: number; handCount: number;
  turnDone: boolean; elimination: string | null;
}
export interface LocationView {
  id: string; name: string; revealed: boolean; shroud: number; clues: number;
  connections: string[]; enemyIds: string[]; victory?: boolean;
}
export interface EnemyView {
  id: string; name: string; fight: number; health: number; damageOn: number;
  evade: number; keywords: Keyword[]; engagedWith: string | null; exhausted: boolean; locationId: string;
}
export interface ActView    { name: string; cluesSpent: number; threshold: number; }
export interface AgendaView { name: string; doom: number; threshold: number; }

export interface GameStateView {
  round: number;
  phase: Phase;
  activeInvestigatorId: string;
  you: SelfView;
  otherInvestigators: OtherInvestigatorView[];
  locations: LocationView[];
  enemies: EnemyView[];
  act: ActView;
  agenda: AgendaView;
  chaosBagSummary: { total: number };
}
