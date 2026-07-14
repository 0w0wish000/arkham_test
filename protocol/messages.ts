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
  | "ELUSIVE" | "PATROL" | "PREY" | "PERIL" | "FAST";

export type CardType = "SKILL" | "ASSET" | "EVENT" | "WEAKNESS";
export type Elimination = "DAMAGE" | "HORROR" | "RESIGNED";

/**
 * 調查階段的行動(每回合三個):DRAW … RESIGN。
 * MULLIGAN(起始手牌調整一次)、END_TURN(換下一位)、ADVANCE_ACT(花線索推進幕)不算行動。
 * 趁隙攻擊:除 FIGHT / EVADE / PARLEY / RESIGN 外,所有行動都會觸發。
 */
export type IntentAction =
  | "DRAW" | "GAIN_RESOURCE" | "PLAY_CARD" | "ACTIVATE"
  | "MOVE" | "INVESTIGATE" | "ENGAGE" | "FIGHT" | "EVADE"
  | "PARLEY" | "RESIGN"
  | "MULLIGAN" | "END_TURN" | "ADVANCE_ACT";

export type ChoiceKind = "COMMIT_CARDS" | "CHOOSE_TARGET" | "CHOOSE_OPTION";

// ---------- Client → Server ----------
export interface JoinMsg    { type: "JOIN"; sessionId: string; investigatorId: string; }
export interface IntentMsg  { type: "INTENT"; action: IntentAction; payload?: Record<string, unknown>; }
export interface ChoiceResponseMsg { type: "CHOICE_RESPONSE"; requestId: string; choice: ChoiceResponse; }
export interface PingMsg    { type: "PING"; }
export type ClientMessage = JoinMsg | IntentMsg | ChoiceResponseMsg | PingMsg;

export type ChoiceResponse =
  | { committedCardIds: string[] }   // COMMIT_CARDS
  | { targetIds: string[] }          // CHOOSE_TARGET
  | { optionId: string };            // CHOOSE_OPTION

// ---------- Server → Client ----------
export interface StateMsg   { type: "STATE"; view: GameStateView; }
export interface EventMsg   { type: "EVENT"; event: string; message: string; }
export interface ChoiceRequestMsg { type: "CHOICE_REQUEST"; requestId: string; kind: ChoiceKind; options: ChoiceOptions; }
export interface ErrorMsg   { type: "ERROR"; message: string; }
export interface PongMsg    { type: "PONG"; }
export type ServerMessage = StateMsg | EventMsg | ChoiceRequestMsg | ErrorMsg | PongMsg;

export interface CommitCardsOptions {
  skill: SkillType; base: number; difficulty: number;
  // 檢定者與同地點的隊友都可投入,張數不設限 —— maxCommit 即你手上符合圖示的張數
  eligibleCards: HandCard[]; maxCommit: number;
}
export interface ChooseTargetOptions { candidates: { id: string; label: string }[]; min: number; max: number; }
export interface ChooseOptionOptions { prompt: string; options: { id: string; label: string }[]; }
export type ChoiceOptions = CommitCardsOptions | ChooseTargetOptions | ChooseOptionOptions;

// ---------- GameStateView(過濾後) ----------
export interface HandCard {
  cardId: string; name: string; cardType: CardType; cost: number; skillIcons: SkillIcon[];
}

/** 只有自己看得到手牌內容;牌庫只給張數,順序永遠不上線。 */
export interface SelfView {
  investigatorId: string;
  skills: Record<Lowercase<SkillType>, number>;
  health: number; damage: number; sanity: number; horror: number;
  resources: number; cluesHeld: number; actionsRemaining: number;
  locationId: string;
  hand: HandCard[];
  inPlay: HandCard[];          // 場上的支援卡(公開資訊)
  deckCount: number;
  discardCount: number;
  engagedEnemyIds: string[];
  turnTaken: boolean;          // 本輪已行動完(小卡翻面)
  elimination: Elimination | null;  // 非 null 即已退場
}
export interface OtherInvestigatorView {
  investigatorId: string; locationId: string; damage: number; horror: number; handCount: number;
  inPlay: HandCard[];
  turnTaken: boolean;
  elimination: Elimination | null;
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
/** doom = 場上所有毀滅標記(密謀 + 地點 + 敵人),門檻就是拿這個總數去比。 */
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
