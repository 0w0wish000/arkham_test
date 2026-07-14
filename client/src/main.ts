import { Connection } from "./net/Connection";
import { Lobby } from "./ui/Lobby";
import { loadProfile, createProfile, renameProfile } from "./profile";
import { storeSave, listSaves, deleteSave } from "./saves";
import type { CommitCardsOptions } from "./protocol";
// 戰役板(進戰役才載入;docs/09 P2 由 START_SCENARIO/STATE 觸發)
import { GameView } from "./render/GameView";
import { Hud } from "./ui/Hud";

async function main() {
  const lobby = new Lobby();
  let profile = loadProfile();
  const wsUrl = `ws://${location.host}/ws/game`;

  // 加載遮罩:轉場空檔蓋住全畫面(連線 / 載入存檔 / 進戰役板 / 開選卡器)
  const mask = document.getElementById("loading-mask") as HTMLElement;
  const showMask = (t: string) => { (document.getElementById("lm-text") as HTMLElement).textContent = t; mask.hidden = false; };
  const hideMask = () => { mask.hidden = true; };

  const pendingLog: string[] = [];   // LOG_HISTORY 在 board 就緒前先暫存,進板後補播
  const gotoMenu = (name: string) => { lobby.showMenu(name); lobby.renderSaves(listSaves()); };

  // ── 戰役板:第一次收到 STATE 才初始化(大廳階段不會觸發)──
  let board: { view: GameView; hud: Hud } | null = null;
  async function ensureBoard(): Promise<{ view: GameView; hud: Hud }> {
    if (board) return board;
    const view = new GameView();
    await view.init(document.getElementById("app")!);
    const hud = new Hud();
    hud.onIntent = (action, payload) => conn.intent(action, payload);
    hud.onCommit = (requestId, ids) => conn.respond(requestId, { committedCardIds: ids });
    hud.onSave = () => { conn.saveRequest(); hud.log("已發起存檔請求,等待隊友確認…"); };
    view.onMove = (toLocationId) => conn.intent("MOVE", { toLocationId });
    lobby.show("game");
    board = { view, hud };
    for (const m of pendingLog) hud.log(m);   // 補播載入前收到的 log 回放
    pendingLog.length = 0;
    return board;
  }

  const conn = new Connection(wsUrl, {
    // 大廳
    onLobby: (msg) => { hideMask(); lobby.renderLobby(msg.activeSessions); },
    onSessionRoster: (msg) => { hideMask(); lobby.renderRoster(msg); },
    // 事件旁白:在板上 → HUD log;在大廳 → 大廳 log
    onEvent: (m) => { if (board) board.hud.log(m); else lobby.logEvent(m); },
    // 戰役板(P2+):首次進板時遮罩(PixiJS 初始化),畫好即收
    onState: async (v) => {
      if (!board) showMask("進入戰役…");
      const b = await ensureBoard();
      b.view.render(v); b.hud.render(v);
      hideMask();
    },
    onChoiceRequest: (req) => {
      if (req.kind === "COMMIT_CARDS" && board) {
        board.hud.showCommit(req.requestId, req.options as CommitCardsOptions);
      }
    },
    onSavePrompt: (msg) => {
      const yes = confirm(`玩家「${msg.requestedBy}」要保存並離開。是否存檔?`);
      conn.saveVote(msg.requestId, yes);
    },
    // 死亡換角投票(docs/09 §10)
    onVotePrompt: (msg) => { conn.vote(msg.requestId, confirm("🗳️ " + msg.reason)); },
    onSaveSnapshot: (msg) => { board?.hud.log(`💾 已存檔:第 ${msg.round} 輪(${msg.scenario})。`); },
    // 存檔複製到本機(docs/09 §7):寫入本機存檔庫,供主選單「加載續玩」
    onCampaignSnapshot: (msg) => {
      storeSave(msg.save);
      const line = `💾 已存檔到本機:${msg.save.name}`;
      if (board) board.hud.log(line); else lobby.logEvent(line);
    },
    // 載入後的紀錄回放
    onLogHistory: (msg) => {
      const lines = msg.entries.map((e) => "↺ " + e.message);
      lines.push("—— 以上為存檔紀錄回放,續玩開始 ——");
      if (board) lines.forEach((l) => board!.hud.log(l));
      else pendingLog.push(...lines);
    },
    onError: (m) => { hideMask(); window.alert("⚠️ " + m); },   // 遮罩先收,避免錯誤時蓋死畫面
  });

  // ── 大廳 callbacks ──
  lobby.onIdentity = (name) => {
    profile = createProfile(name);
    lobby.setPlayer(profile.playerId);
    conn.hello(profile.playerId, profile.displayName);
    gotoMenu(profile.displayName);
  };
  lobby.onRename = (name) => {
    profile = renameProfile(name);
    lobby.setPlayer(profile.playerId);
    conn.hello(profile.playerId, profile.displayName);   // 重新自報(displayName 變)
    gotoMenu(profile.displayName);
  };
  lobby.onCreate = (name, campaignKey, difficulty) => conn.createCampaign(name, campaignKey, difficulty);
  lobby.onJoin = (campaignId) => conn.joinSession(campaignId);
  lobby.onLeave = () => { conn.leaveSession(); gotoMenu(profile?.displayName ?? ""); };
  // 牌組大廳
  lobby.onPick = (investigatorId) => conn.pickInvestigator(investigatorId);
  lobby.onReady = (ready) => conn.readyDeck(ready);
  lobby.onForceStart = () => conn.forceStart();
  // 加載存檔(docs/09 §7)
  lobby.onLoadSave = (save) => { showMask("載入存檔中…"); conn.offerSave(save); };
  lobby.onReadyLoad = (ready) => conn.readyLoad(ready);   // 等待進度看名冊「就緒 X/N」,不遮罩
  lobby.onRefreshSaves = () => lobby.renderSaves(listSaves());
  lobby.onDeleteSave = (campaignId) => { deleteSave(campaignId); lobby.renderSaves(listSaves()); };
  lobby.onSitOut = (sitOut) => conn.sitOut(sitOut);       // 本章中離/歸隊(docs/09 §9)
  lobby.onProposeNewChar = (playerId) => conn.proposeNewCharacter(playerId);   // 死亡換角(§10)

  // 完整選卡器(P2-2):開 iframe 構築;「完成牌組」→ 送回 PICK + SET_DECK + READY
  lobby.onOpenDeckbuilder = () => {
    const frame = document.getElementById("db-frame") as HTMLIFrameElement;
    const params = new URLSearchParams({ embed: "1" });
    const cur = lobby.currentPick();
    if (cur) { params.set("inv", cur); params.set("lockInv", "1"); }   // 已在大廳選定 → 鎖定角色
    showMask("載入選卡器…");
    frame.onload = () => hideMask();
    frame.src = `/deckbuilder.html?${params.toString()}`;
    lobby.show("deckbuilder");
  };
  document.getElementById("db-cancel")?.addEventListener("click", () => lobby.show("roster"));
  window.addEventListener("message", (ev) => {
    const d = ev.data as { type?: string; investigatorId?: string; deck?: string[]; xp?: number };
    if (d && d.type === "DECK_DONE" && d.investigatorId) {
      conn.pickInvestigator(d.investigatorId);
      conn.setDeck(Array.isArray(d.deck) ? d.deck : [], typeof d.xp === "number" ? d.xp : 0);
      conn.readyDeck(true);
      lobby.show("roster");   // 回名冊;伺服器隨即推送 roster/STATE(若屏障達成 → 進戰役板)
    }
  });

  // ── 啟動 ──
  try {
    showMask("連線伺服器中…");
    await conn.connect();
    if (profile) {
      lobby.setPlayer(profile.playerId);
      conn.hello(profile.playerId, profile.displayName);
      gotoMenu(profile.displayName);
    } else {
      lobby.showIdentity();
    }
    hideMask();
  } catch {
    hideMask();
    lobby.showIdentity();
    window.alert("⚠️ 無法連線伺服器。請先在 host 端啟動 start-server.bat(Windows)或 ./start-server.sh");
  }
}

main();
