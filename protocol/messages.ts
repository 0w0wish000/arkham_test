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

export type IntentAction =
  | "MOVE" | "INVESTIGATE" | "FIGHT" | "EVADE" | "ENGAGE"
  | "PLAY_CARD" | "ACTIVATE" | "END_TURN" | "ADVANCE_ACT";

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
  eligibleCards: HandCard[]; maxCommit: number;   // 檢定者:大數;同地點隊友:1
}
export interface ChooseTargetOptions { candidates: { id: string; label: string }[]; min: number; max: number; }
export interface ChooseOptionOptions { prompt: string; options: { id: string; label: string }[]; }
export type ChoiceOptions = CommitCardsOptions | ChooseTargetOptions | ChooseOptionOptions;

// ---------- GameStateView(過濾後) ----------
export interface HandCard { cardId: string; name: string; skillIcons: SkillIcon[]; }

export interface SelfView {
  investigatorId: string;
  skills: Record<Lowercase<SkillType>, number>;
  health: number; damage: number; sanity: number; horror: number;
  resources: number; cluesHeld: number; actionsRemaining: number;
  locationId: string; hand: HandCard[]; engagedEnemyIds: string[];
}
export interface OtherInvestigatorView {
  investigatorId: string; locationId: string; damage: number; horror: number; handCount: number;
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
