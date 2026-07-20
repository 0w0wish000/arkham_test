package com.arkham.server.session;

import com.arkham.engine.RulesEngine;
import com.arkham.engine.event.GameEvent;
import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.scenario.ScenarioFactory;
import com.arkham.server.dto.CampaignSave;
import com.arkham.server.dto.ClientMessage;
import com.arkham.server.dto.RosterMember;
import com.arkham.server.dto.ServerMessage;
import com.arkham.server.dto.SessionSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One campaign "table" (docs/09) — replaces the old room. Holds the {@code roster}
 * (playerId → member) and the connected clients. P1 keeps a session in the
 * {@code DECKBUILDING} stage and just tracks who is at the table, broadcasting
 * {@code SESSION_ROSTER} (and narration {@code EVENT}s) on every change. Barriers,
 * deck submission and START_SCENARIO arrive in later phases.
 *
 * <p>All mutators are {@code synchronized}: a table is single-threaded, which also
 * serialises writes to the (non-thread-safe) WebSocket sessions.
 */
public final class CampaignSession {

    private final String campaignId;
    private final String name;
    private final String campaignKey;
    private final String difficulty;
    private volatile String stage = "DECKBUILDING";
    private final ObjectMapper mapper;
    private final long seed;
    private GameSession game;   // 非 null 表示已 START_SCENARIO,對局進行中

    private final Map<String, Member> roster = new LinkedHashMap<>();      // playerId → member (join order)
    private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

    private Object pendingSnapshot;                       // 載入中(LOADING)待重建的引擎狀態
    private List<GameEvent> pendingEventLog = List.of();  // 載入時要回放的紀錄
    private SaveVote saveVote;                            // 存檔投票進行中
    private final java.util.Set<String> deadInvestigators = new HashSet<>();  // 永久封鎖角色(docs/09 §10)
    private int maxXp = 50;                               // XP 上限(lite 版先給常數;正式版依戰役路線)
    private int currentChapter = 1;                       // 跨章推進(docs/09 §9;campaigns.json 章節序)
    private CharVote charVote;                            // 換角投票進行中
    private ClaimVote claimVote;                          // 席位認領投票進行中(docs/09 P6)
    private final List<CampaignSave.LogEntry> campaignLog = new ArrayList<>();   // 戰役日誌(D6;跨章保留)

    public CampaignSession(String campaignId, String name, String campaignKey,
                           String difficulty, ObjectMapper mapper) {
        this.campaignId = campaignId;
        this.name = name;
        this.campaignKey = campaignKey;
        this.difficulty = difficulty;
        this.mapper = mapper;
        this.seed = campaignId.hashCode();   // 每桌固定種子 → 可重現 / 存檔續玩
    }

    /** The in-scenario game once START_SCENARIO has fired (null while in the lobby). */
    public GameSession game() { return game; }

    /** The investigator a player picked in this campaign (null if none / unknown player). */
    public synchronized String investigatorFor(String playerId) {
        Member m = roster.get(playerId);
        return m == null ? null : m.investigatorId;
    }

    public String campaignId() { return campaignId; }

    public synchronized boolean isEmpty() { return clients.isEmpty(); }

    public synchronized boolean inScenario() { return game != null; }

    /** A player joins / rejoins this table. 戰役中 = 接手(限原玩家);大廳 = 一般加入/亂入。 */
    public synchronized void join(String playerId, String displayName, WebSocketSession ws) throws IOException {
        boolean isNew = !roster.containsKey(playerId);
        Member m = roster.computeIfAbsent(playerId, id -> new Member(id, displayName));
        m.displayName = displayName;
        clients.put(playerId, ws);

        if (game != null) {
            // 戰役進行中:只有「原玩家(名冊中已有其角色)」能重連接手;不接受亂入
            if (m.investigatorId == null) {
                if (isNew) roster.remove(playerId);
                clients.remove(playerId);
                send(ws, new ServerMessage.Error("戰役進行中,只有原玩家能重連接手其調查員。"));
                return;
            }
            broadcast(new ServerMessage.Event("takeover", displayName + " 重新連線,接回了調查。"));
            game.reattach(m.investigatorId, ws);   // 送 STATE + 補發待決(重打掉線那一動)
            return;
        }

        if (isNew) broadcast(new ServerMessage.Event("roster", displayName + " 加入了調查。"));
        broadcastRoster();
        if (!campaignLog.isEmpty()) {
            send(ws, new ServerMessage.CampaignLog(List.copyOf(campaignLog)));   // 入桌同步日誌(D6)
        }
    }

    /**
     * D2/D5 跨章推進:對局結束 → 結算 XP(勝利點+勝負基礎)→ 回牌組大廳、章數+1、
     * 名冊 ready 重置 → 自動存檔(新版本複製各本機)。由 handler 在每則對局訊息後輪詢。
     */
    public synchronized void settleChapterIfOver() throws IOException {
        if (game == null || !game.isOver()) return;
        boolean won = game.isWon();
        int earned = (won ? 2 : 1) + game.victoryPoints();   // lite 公式:參與1/勝利2 + 勝利地點
        Map<String, String> causes = game.eliminationCauses();       // D4:淘汰原因 → 創傷
        Map<String, int[]> vitals = game.investigatorVitals();       // [生命, 理智] 上限
        game = null;
        stage = "DECKBUILDING";
        currentChapter++;
        maxXp += earned;                                      // docs/09 §11:路線可得經驗累加
        for (Member m : roster.values()) {
            m.ready = false;
            if ("ACTIVE".equals(m.status) && m.investigatorId != null) m.xp += earned;
        }
        settleTrauma(causes, vitals);                         // D4:被擊敗 → 創傷;達上限 → 退役
        broadcast(new ServerMessage.Event("chapter",
                (won ? "🎉 章節完成!" : "🕯️ 章節失利…") + "每位參戰調查員獲得 " + earned
                + " 經驗;回到牌組大廳,準備第 " + currentChapter + " 章。"));
        broadcastRoster();
        commitSave();   // 新存檔版本(跨章 checkpoint)複製到各本機
    }

    /**
     * D4 創傷結算(官方 p20-22):傷害被擊敗 → +1 肉體創傷;恐懼被擊敗 → +1 精神創傷
     * (撤退 RESIGNED 無創傷)。創傷達生命上限 → 陣亡、達理智上限 → 精神失常 ——
     * 角色永久退役(deadInvestigators),該玩家下章需改帶新角色(創傷歸零;XP 依 P5 慣例保留)。
     */
    private void settleTrauma(Map<String, String> causes, Map<String, int[]> vitals) throws IOException {
        for (Member m : roster.values()) {
            if (m.investigatorId == null) continue;
            String cause = causes.get(m.investigatorId);
            if ("DAMAGE".equals(cause)) {
                m.physicalTrauma++;
                broadcast(new ServerMessage.Event("trauma", "🩸 " + m.displayName + " 的「" + m.investigatorId
                        + "」傷重被擊敗 → 肉體創傷 +1(共 " + m.physicalTrauma + ";下章開局帶等量傷害)。"));
            } else if ("HORROR".equals(cause)) {
                m.mentalTrauma++;
                broadcast(new ServerMessage.Event("trauma", "🧠 " + m.displayName + " 的「" + m.investigatorId
                        + "」精神崩潰 → 精神創傷 +1(共 " + m.mentalTrauma + ";下章開局帶等量恐懼)。"));
            }
            int[] v = vitals.get(m.investigatorId);
            if (v == null) continue;
            boolean killed = m.physicalTrauma >= v[0];
            boolean insane = m.mentalTrauma >= v[1];
            if (killed || insane) {
                deadInvestigators.add(m.investigatorId);
                broadcast(new ServerMessage.Event("trauma", "☠️ 「" + m.investigatorId + "」創傷達"
                        + (killed ? "生命上限,永久陣亡" : "理智上限,永久精神失常")
                        + " —— 此存檔封鎖該角色;" + m.displayName + " 需改帶新角色。"));
                m.investigatorId = null;
                m.physicalTrauma = 0;
                m.mentalTrauma = 0;
            }
        }
    }

    /** 接手寬限計時器:掉線者逾時未歸隊 → 檢定視為不投入(解開卡住的屏障)。daemon,不擋 JVM 結束。 */
    private static final java.util.concurrent.ScheduledExecutorService GRACE_TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "arkham-takeover-grace");
                t.setDaemon(true);
                return t;
            });
    private static final long TAKEOVER_GRACE_MS = graceMs();   // 預設 60s;e2e 可用環境變數縮短

    private static long graceMs() {
        try {
            String v = System.getenv("ARKHAM_TAKEOVER_GRACE_MS");
            if (v != null) return Long.parseLong(v.trim());
        } catch (Exception ignored) { /* 用預設 */ }
        return 60_000;
    }

    /** 連線中斷 / 離桌。戰役中保留名冊 + session(可重連接手);大廳/載入階段則離桌。 */
    public synchronized void disconnect(String playerId) throws IOException {
        clients.remove(playerId);
        Member m = roster.get(playerId);

        // 掉線者不再擋任何進行中的投票(否則存檔/換角投票會永久懸置)
        if (saveVote != null && saveVote.outstanding.remove(playerId)) {
            resolveSaveVoteIfComplete();
        }
        if (charVote != null && charVote.outstanding.remove(playerId) && charVote.outstanding.isEmpty()) {
            resolveCharVote();
        }
        if (claimVote != null && claimVote.outstanding.remove(playerId) && claimVote.outstanding.isEmpty()) {
            resolveClaimVote();
        }

        if (game != null) {
            if (m != null && m.investigatorId != null) {
                game.detach(m.investigatorId);   // 屏障保留,等原玩家接手(重打那一動)
                final String inv = m.investigatorId;
                final GameSession g = game;
                GRACE_TIMER.schedule(() -> g.autoSkipIfAbsent(inv),
                        TAKEOVER_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            if (m != null) broadcast(new ServerMessage.Event("disconnect",
                    m.displayName + " 掉線了(可重連接手;逾時未歸隊則該次檢定視為不投入)。"));
            return;   // 保留名冊 + session
        }
        roster.remove(playerId);
        if (m != null) broadcast(new ServerMessage.Event("roster", m.displayName + " 暫時脫離了調查隊伍。"));
        broadcastRoster();
    }

    /** 本章中離(true)/ 歸隊(false)—— 只在牌組大廳可用(docs/09 §9);中離者不參戰、不擋屏障。 */
    public synchronized void sitOut(String playerId, boolean sitOut) throws IOException {
        Member m = roster.get(playerId);
        if (m == null || !"DECKBUILDING".equals(stage)) return;
        m.status = sitOut ? "SITTING_OUT" : "ACTIVE";
        if (sitOut) m.ready = false;
        broadcast(new ServerMessage.Event("roster",
                m.displayName + (sitOut ? " 暫時脫離了本章調查。" : " 回到了調查隊伍。")));
        broadcastRoster();
        checkBarrier();   // 中離後剩餘 ACTIVE 若全就緒 → 開打(難度隨人數縮放)
    }

    // ------------------------------------------------------------------
    // 牌組大廳 → 屏障 B → START_SCENARIO(docs/09 §8.2)
    // ------------------------------------------------------------------

    /** 選角:限已知調查員、且未被同桌他人選走;重選會取消 ready。 */
    public synchronized void pickInvestigator(String playerId, String investigatorId) throws IOException {
        Member m = roster.get(playerId);
        if (m == null) return;
        if (!ScenarioFactory.isKnownInvestigator(investigatorId)) {
            sendTo(playerId, new ServerMessage.Error("未知的調查員:" + investigatorId));
            return;
        }
        if (deadInvestigators.contains(investigatorId)) {
            sendTo(playerId, new ServerMessage.Error("該調查員已在此存檔陣亡,無法再度使用。"));
            return;
        }
        for (Member other : roster.values()) {
            if (other != m && investigatorId.equals(other.investigatorId)) {
                sendTo(playerId, new ServerMessage.Error("該調查員已被隊友選走"));
                return;
            }
        }
        m.investigatorId = investigatorId;
        m.ready = false;   // 換角需重新 ready
        broadcastRoster();
    }

    /** 提交牌組;XP 不得超過上限(docs/09 §11);F1-lite 構築驗證(同名 ≤2、張數上限)。 */
    public synchronized void setDeck(String playerId, List<String> deck, int xp) throws IOException {
        Member m = roster.get(playerId);
        if (m == null) return;
        if (xp > maxXp) {
            sendTo(playerId, new ServerMessage.Error("經驗超過此戰役可取得上限 " + maxXp + "。"));
            return;
        }
        String violation = com.arkham.engine.scenario.DeckRules.validate(deck);
        if (violation != null) {
            sendTo(playerId, new ServerMessage.Error("牌組不合法:" + violation));
            return;
        }
        List<String> unknown = com.arkham.engine.scenario.DeckRules.unknownCards(deck);
        if (!unknown.isEmpty()) {
            // 不擋(內容管線未跑的環境仍可玩;未知卡入場為 0 費無效果),但明講
            sendTo(playerId, new ServerMessage.Event("deck",
                    "⚠️ 牌組含目錄查無的卡(將以無效果卡入場):" + String.join("、",
                            unknown.subList(0, Math.min(5, unknown.size())))
                            + (unknown.size() > 5 ? " …等 " + unknown.size() + " 種" : "")));
        }
        m.deck = deck == null ? new ArrayList<>() : new ArrayList<>(deck);
        m.xp = Math.max(0, xp);
    }

    /** 牌組完成/反悔(屏障 B 訊號)。全員 ACTIVE 就緒即開打。 */
    public synchronized void readyDeck(String playerId, boolean ready) throws IOException {
        Member m = roster.get(playerId);
        if (m == null) return;
        if (ready && m.investigatorId == null) {
            sendTo(playerId, new ServerMessage.Error("請先選擇調查員,再準備完成"));
            return;
        }
        m.ready = ready;
        if (ready) broadcast(new ServerMessage.Event("roster", m.displayName + " 已整裝待發。"));
        broadcastRoster();
        checkBarrier();
    }

    /** 主機強制越過屏障:牌組階段 → 開打;載入階段 → 直接重建對局。 */
    public synchronized void forceStart() throws IOException {
        if ("LOADING".equals(stage)) startFromSnapshot();
        else startScenario();
    }

    /** 屏障:所有 ACTIVE 成員都已選角且 ready → 開打。 */
    private void checkBarrier() throws IOException {
        if (game != null) return;
        List<Member> active = activePlayable();
        if (active.isEmpty()) return;
        for (Member m : active) {
            if (m.investigatorId == null || !m.ready) return;   // 尚有人未就緒
        }
        startScenario();
    }

    /** 從名冊建立場景並廣播初始 STATE(把大家從大廳切到戰役板)。 */
    private void startScenario() throws IOException {
        if (game != null) return;
        List<Member> playing = new ArrayList<>();
        for (Member m : activePlayable()) {
            if (m.investigatorId != null) playing.add(m);
        }
        if (playing.isEmpty()) {
            broadcast(new ServerMessage.Error("沒有已選角的玩家,無法開打"));
            return;
        }
        List<String> ids = new ArrayList<>();
        Map<String, List<String>> decks = new LinkedHashMap<>();   // 各人牌組(卡名;未提交 → 引擎用預設牌組)
        for (Member m : playing) {
            ids.add(m.investigatorId);
            if (!m.deck.isEmpty()) decks.put(m.investigatorId, List.copyOf(m.deck));
        }

        // "sandbox" → 測試沙盒;難度 → 混沌袋組成;牌組 → 洗牌 + 開局抽 5(C-lite)
        RulesEngine engine = ScenarioFactory.newEngine(seed, ids, campaignKey, difficulty, decks);
        game = new GameSession(campaignId, mapper, engine, seed);
        stage = "IN_SCENARIO";
        broadcast(new ServerMessage.Event("scenario",
                "隊伍準備完畢 —— 踏入戰役,共 " + ids.size() + " 位調查員。"));
        for (Member m : playing) {
            if (m.physicalTrauma > 0 || m.mentalTrauma > 0) {   // D4:每點創傷 = 開局 1 傷害/恐懼(官方 p20)
                game.applyStartingTrauma(m.investigatorId, m.physicalTrauma, m.mentalTrauma);
                broadcast(new ServerMessage.Event("trauma", "🩹 " + m.displayName + " 的「" + m.investigatorId
                        + "」帶著創傷上陣:開局 " + m.physicalTrauma + " 傷害 / " + m.mentalTrauma + " 恐懼。"));
            }
        }
        for (Member m : playing) {
            WebSocketSession ws = clients.get(m.playerId);
            if (ws != null) game.join(m.investigatorId, ws);   // 送初始 STATE → client 切到戰役板
        }
    }

    private List<Member> activePlayable() {
        List<Member> out = new ArrayList<>();
        for (Member m : roster.values()) {
            if ("ACTIVE".equals(m.status)) out.add(m);
        }
        return out;
    }

    private void sendTo(String playerId, ServerMessage msg) throws IOException {
        WebSocketSession ws = clients.get(playerId);
        if (ws != null) send(ws, msg);
    }

    // ------------------------------------------------------------------
    // 死亡換角投票(docs/09 §10)
    // ------------------------------------------------------------------

    /** 對某玩家(其角色陣亡)發起換角投票 —— 只在牌組大廳。 */
    public synchronized void proposeNewCharacter(String proposerId, String subjectPlayerId) throws IOException {
        if (!"DECKBUILDING".equals(stage) || charVote != null) return;
        Member subject = roster.get(subjectPlayerId);
        if (subject == null || subject.investigatorId == null) {
            sendTo(proposerId, new ServerMessage.Error("該玩家尚無角色可換。"));
            return;
        }
        String reqId = UUID.randomUUID().toString();
        charVote = new CharVote(reqId, subjectPlayerId, subject.investigatorId, new HashSet<>(clients.keySet()));
        String reason = subject.displayName + " 的「" + subject.investigatorId
                + "」陣亡 —— 是否允許改帶新角色?(通過後該角色在此存檔永久封鎖)";
        broadcast(new ServerMessage.VotePrompt(reqId, subjectPlayerId, reason));
        if (charVote.outstanding.isEmpty()) resolveCharVote();
    }

    public synchronized void vote(String requestId, String voterId, boolean yes) throws IOException {
        if (charVote != null && charVote.requestId.equals(requestId)) {
            if (!charVote.outstanding.remove(voterId)) return;
            charVote.total++;
            if (yes) charVote.yes++;
            if (charVote.outstanding.isEmpty()) resolveCharVote();
            return;
        }
        if (claimVote != null && claimVote.requestId.equals(requestId)) {
            if (!claimVote.outstanding.remove(voterId)) return;
            claimVote.total++;
            if (yes) claimVote.yes++;
            if (claimVote.outstanding.isEmpty()) resolveClaimVote();
        }
    }

    private void resolveCharVote() throws IOException {
        CharVote v = charVote;
        charVote = null;
        Member subject = roster.get(v.subjectPlayerId);
        boolean pass = v.yes * 2 > v.total;   // 過半通過
        if (pass && subject != null) {
            deadInvestigators.add(v.deadInvestigatorId);   // 永久封鎖,不可再用
            subject.investigatorId = null;                 // 該玩家可改選新角色
            subject.ready = false;
            subject.status = "ACTIVE";
            broadcast(new ServerMessage.Event("death", "投票通過:「" + v.deadInvestigatorId
                    + "」永久陣亡封鎖;" + subject.displayName + " 可改帶新角色。"));
        } else {
            broadcast(new ServerMessage.Event("death", "換角投票未過半,維持現狀。"));
        }
        broadcastRoster();
    }

    // ------------------------------------------------------------------
    // 席位認領(docs/09 P6):換裝置(新 playerId)認回離線席位
    // ------------------------------------------------------------------

    /**
     * 認領離線席位:認領者(已入桌的新身分)繼承目標席位的角色/牌組/XP/狀態,
     * 目標席位移除。需其餘「在線」成員表決(無異議通過;無人在線 → 直接通過)。
     * 戰役進行中不可認領(接手限原玩家 —— 使用者定案);請於章節之間處理。
     */
    public synchronized void claimSeat(String claimerId, String targetPlayerId) throws IOException {
        if (game != null) {
            sendTo(claimerId, new ServerMessage.Error("戰役進行中無法認領席位;請於章節之間(牌組大廳)認領。"));
            return;
        }
        if (claimVote != null) {
            sendTo(claimerId, new ServerMessage.Error("已有一場席位認領投票進行中,請稍候。"));
            return;
        }
        Member claimer = roster.get(claimerId);
        Member target = roster.get(targetPlayerId);
        if (claimer == null || target == null || claimerId.equals(targetPlayerId)) {
            sendTo(claimerId, new ServerMessage.Error("找不到可認領的席位。"));
            return;
        }
        if (clients.containsKey(targetPlayerId)) {
            sendTo(claimerId, new ServerMessage.Error("該席位的玩家仍在線上,無法認領。"));
            return;
        }
        java.util.Set<String> voters = new HashSet<>(clients.keySet());
        voters.remove(claimerId);   // 認領者不投自己的案
        String reqId = UUID.randomUUID().toString();
        claimVote = new ClaimVote(reqId, claimerId, targetPlayerId, voters);
        broadcast(new ServerMessage.Event("claim", claimer.displayName + " 想認領 "
                + target.displayName + " 的席位(換了裝置的隊友回歸)。"));
        if (voters.isEmpty()) {          // 桌上只剩認領者 → 無人異議,直接通過
            resolveClaimVote();
            return;
        }
        String reason = claimer.displayName + " 想認領 " + target.displayName + " 的席位("
                + (target.investigatorId == null ? "尚未選角" : target.investigatorId)
                + ",XP " + target.xp + ")—— 他換了裝置,同意讓他繼承這個席位嗎?";
        for (String pid : voters) {
            sendTo(pid, new ServerMessage.VotePrompt(reqId, targetPlayerId, reason));
        }
    }

    /** 結案:無人投反對過半即通過(0 票 = 通過);目標若已重新上線則取消。 */
    private void resolveClaimVote() throws IOException {
        ClaimVote v = claimVote;
        claimVote = null;
        Member claimer = roster.get(v.claimerId);
        Member target = roster.get(v.targetPlayerId);
        boolean pass = v.yes * 2 >= v.total;   // 同意 ≥ 半數(含平手;無人投票=通過)
        if (!pass || claimer == null || target == null || clients.containsKey(v.targetPlayerId)) {
            broadcast(new ServerMessage.Event("claim", "席位認領未成立,維持現狀。"));
            broadcastRoster();
            return;
        }
        claimer.investigatorId = target.investigatorId;
        claimer.deck = new ArrayList<>(target.deck);
        claimer.xp = target.xp;
        claimer.status = target.status;
        claimer.physicalTrauma = target.physicalTrauma;   // 創傷跟著席位走
        claimer.mentalTrauma = target.mentalTrauma;
        claimer.ready = false;
        roster.remove(v.targetPlayerId);
        broadcast(new ServerMessage.Event("claim", "✅ " + claimer.displayName + " 認領了 "
                + target.displayName + " 的席位,繼承其調查員"
                + (claimer.investigatorId != null ? "「" + claimer.investigatorId + "」" : "")
                + "、牌組與 " + claimer.xp + " XP。"));
        broadcastRoster();
        if ("DECKBUILDING".equals(stage)) {
            commitSave();   // 席位易主 → 立即出新存檔版本(LOADING 階段不動存檔,避免蓋掉戰役快照)
        }
    }

    // ------------------------------------------------------------------
    // 戰役日誌 + 套用劇本指示(docs/09 §11.5 混合制;D6/D7)
    // ------------------------------------------------------------------

    /**
     * 套用劇本指示:人(語音共識)決定、系統記帳+同步。限章節之間(牌組大廳);
     * 每次套用都寫入戰役日誌、廣播、立即出新存檔版本。
     * ADD_CARD / REMOVE_CARD 不做構築驗證 —— 劇本給的卡(如故事資產/弱點)本就可超出構築規則。
     */
    public synchronized void applyLog(String byPlayerId, ClientMessage.ApplyLog req) throws IOException {
        Member by = roster.get(byPlayerId);
        if (by == null) return;
        if (!"DECKBUILDING".equals(stage)) {
            sendTo(byPlayerId, new ServerMessage.Error("劇本指示請於章節之間(牌組大廳)套用。"));
            return;
        }
        String action = req.action() == null ? "" : req.action();
        Member target = req.targetPlayerId() == null ? null : roster.get(req.targetPlayerId());
        String text;
        switch (action) {
            case "ADD_CARD" -> {
                if (target == null || blank(req.cardName())) { sendTo(byPlayerId, new ServerMessage.Error("請指定對象與卡名。")); return; }
                target.deck.add(req.cardName().trim());
                text = target.displayName + " 的牌組加入「" + req.cardName().trim() + "」";
            }
            case "REMOVE_CARD" -> {
                if (target == null || blank(req.cardName())) { sendTo(byPlayerId, new ServerMessage.Error("請指定對象與卡名。")); return; }
                if (!target.deck.remove(req.cardName().trim())) {
                    sendTo(byPlayerId, new ServerMessage.Error("「" + req.cardName().trim() + "」不在 " + target.displayName + " 的牌組。"));
                    return;
                }
                text = target.displayName + " 的牌組移除「" + req.cardName().trim() + "」";
            }
            case "ADJUST_TRAUMA" -> {
                if (target == null) { sendTo(byPlayerId, new ServerMessage.Error("請指定對象。")); return; }
                int dp = req.physicalDelta() == null ? 0 : req.physicalDelta();
                int dm = req.mentalDelta() == null ? 0 : req.mentalDelta();
                if (dp == 0 && dm == 0) return;
                target.physicalTrauma = Math.max(0, target.physicalTrauma + dp);
                target.mentalTrauma = Math.max(0, target.mentalTrauma + dm);
                text = target.displayName + " 創傷調整為 🩸" + target.physicalTrauma + " / 🧠" + target.mentalTrauma;
                maybeRetireByTrauma(target);
            }
            case "RECORD" -> {
                if (blank(req.text())) { sendTo(byPlayerId, new ServerMessage.Error("記事內容不可為空。")); return; }
                text = req.text().trim();
            }
            default -> { sendTo(byPlayerId, new ServerMessage.Error("未知的指示類型:" + action)); return; }
        }
        campaignLog.add(new CampaignSave.LogEntry(currentChapter, by.displayName, action, text));
        broadcast(new ServerMessage.Event("log", "📜 " + by.displayName + " 套用劇本指示:" + text + "。"));
        broadcast(new ServerMessage.CampaignLog(List.copyOf(campaignLog)));
        broadcastRoster();
        commitSave();   // 指示套用 = 戰役狀態變更 → 立即出新存檔版本
    }

    private static boolean blank(String v) { return v == null || v.isBlank(); }

    /** 創傷達登記表上限 → 永久退役(與章節結算同規則;APPLY_LOG 調創傷後檢查)。 */
    private void maybeRetireByTrauma(Member m) throws IOException {
        if (m.investigatorId == null) return;
        com.arkham.engine.model.Investigator reg =
                com.arkham.engine.scenario.ScenarioFactory.buildInvestigator(m.investigatorId);
        boolean killed = m.physicalTrauma >= reg.getHealth();
        boolean insane = m.mentalTrauma >= reg.getSanity();
        if (killed || insane) {
            deadInvestigators.add(m.investigatorId);
            broadcast(new ServerMessage.Event("trauma", "☠️ 「" + m.investigatorId + "」創傷達"
                    + (killed ? "生命上限,永久陣亡" : "理智上限,永久精神失常")
                    + " —— 此存檔封鎖該角色;" + m.displayName + " 需改帶新角色。"));
            m.investigatorId = null;
            m.physicalTrauma = 0;
            m.mentalTrauma = 0;
            m.ready = false;
        }
    }

    // ------------------------------------------------------------------
    // 存檔(戰役級)+ 載入(docs/09 §7)
    // ------------------------------------------------------------------

    /** 從一份戰役存檔還原一桌:回名冊牌組;若存檔在戰役中 → 進 LOADING 等屏障 A。 */
    public static CampaignSession restore(CampaignSave save, ObjectMapper mapper) {
        CampaignSession cs = new CampaignSession(save.campaignId(), save.name(),
                save.campaignKey(), save.difficulty(), mapper);
        if (save.roster() != null) {
            for (CampaignSave.SavedMember sm : save.roster()) {
                Member m = new Member(sm.playerId(), sm.displayName());
                m.investigatorId = sm.investigatorId();
                m.deck = sm.deck() == null ? new ArrayList<>() : new ArrayList<>(sm.deck());
                m.xp = sm.xp();
                m.status = sm.status() == null ? "ACTIVE" : sm.status();
                m.physicalTrauma = Math.max(0, sm.physicalTrauma());
                m.mentalTrauma = Math.max(0, sm.mentalTrauma());
                m.ready = false;                       // 重載後需重新 ready
                cs.roster.put(sm.playerId(), m);
            }
        }
        if (save.deadInvestigators() != null) cs.deadInvestigators.addAll(save.deadInvestigators());
        if (save.campaignLog() != null) cs.campaignLog.addAll(save.campaignLog());
        if (save.maxXp() > 0) cs.maxXp = save.maxXp();
        if (save.currentChapter() > 0) cs.currentChapter = save.currentChapter();
        if ("IN_SCENARIO".equals(save.stage())) {
            cs.stage = "LOADING";                      // 屏障 A:等大家載入
            cs.pendingSnapshot = save.snapshot();
            cs.pendingEventLog = save.eventLog() == null ? List.of() : save.eventLog();
        } else {
            cs.stage = "DECKBUILDING";                 // 回牌組大廳(屏障 B)
        }
        return cs;
    }

    /** 玩家發起保存 → 全員彈窗;任一同意即存(合作信任)。 */
    public synchronized void handleSaveRequest(String requesterId) throws IOException {
        String reqId = UUID.randomUUID().toString();
        saveVote = new SaveVote(reqId, requesterId, new HashSet<>(clients.keySet()));
        broadcast(new ServerMessage.SavePrompt(reqId, requesterId));
        if (saveVote.outstanding.isEmpty()) commitSave();
    }

    public synchronized void submitSaveVote(String requestId, String playerId, boolean vote) throws IOException {
        if (saveVote == null || !saveVote.requestId.equals(requestId)) return;
        if (!saveVote.outstanding.remove(playerId)) return;
        if (vote) saveVote.yes++;
        resolveSaveVoteIfComplete();
    }

    /** 待回覆者清空(投完或掉線)即結案:任一同意 → 存;否則取消。 */
    private void resolveSaveVoteIfComplete() throws IOException {
        if (saveVote == null || !saveVote.outstanding.isEmpty()) return;
        if (saveVote.yes >= 1) commitSave();
        else { broadcast(new ServerMessage.Event("save", "存檔已取消(全員未同意)")); saveVote = null; }
    }

    private void commitSave() throws IOException {
        CampaignSave save = buildCampaignSave();
        persistSave(save);
        broadcast(new ServerMessage.CampaignSnapshot(save));      // 複製到各玩家本機
        String where = "IN_SCENARIO".equals(save.stage()) ? "第 " + save.round() + " 輪" : "牌組編輯";
        broadcast(new ServerMessage.Event("save", "已存檔:" + name + "(" + where + "),已複製到各玩家本機。"));
        saveVote = null;
    }

    /** 打包目前這桌的完整戰役存檔(名冊 + 牌組 +（若在戰役中）引擎快照 + 事件紀錄)。 */
    public synchronized CampaignSave buildCampaignSave() {
        List<CampaignSave.SavedMember> sm = new ArrayList<>();
        for (Member m : roster.values()) {
            sm.add(new CampaignSave.SavedMember(m.playerId, m.displayName, m.investigatorId,
                    List.copyOf(m.deck), m.xp, m.status, m.physicalTrauma, m.mentalTrauma));
        }
        boolean inScenario = game != null;
        Object snapshot = inScenario ? game.snapshotStateNode() : null;
        List<GameEvent> log = inScenario ? game.eventLogCopy() : List.of();
        int round = inScenario ? game.round() : 0;
        String stg = inScenario ? "IN_SCENARIO" : "DECKBUILDING";
        return new CampaignSave(campaignId, name, campaignKey, difficulty, stg, sm,
                List.copyOf(deadInvestigators), maxXp, snapshot, log, round, currentChapter,
                List.copyOf(campaignLog));
    }

    private void persistSave(CampaignSave save) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("saves");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(campaignId + ".json"), mapper.writeValueAsString(save));
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** 屏障 A:我已載入戰役快照;所有已連線者就緒 → 重建對局。 */
    public synchronized void readyLoad(String playerId, boolean ready) throws IOException {
        if (!"LOADING".equals(stage)) return;
        Member m = roster.get(playerId);
        if (m == null) return;
        m.ready = ready;
        if (ready) broadcast(new ServerMessage.Event("load", m.displayName + " 已載入完畢。"));
        broadcastRoster();
        checkLoadBarrier();
    }

    private void checkLoadBarrier() throws IOException {
        if (!"LOADING".equals(stage) || clients.isEmpty()) return;
        for (Map.Entry<String, Member> e : roster.entrySet()) {
            if (!clients.containsKey(e.getKey())) continue;   // 未連線者不擋(缺席,可稍後接手)
            if (!e.getValue().ready) return;
        }
        startFromSnapshot();
    }

    /** 從快照重建對局 → 回放 log → 每位已連線玩家接回自己的調查員(收 STATE)。 */
    private void startFromSnapshot() throws IOException {
        if (game != null || pendingSnapshot == null) return;
        game = GameSession.fromSnapshot(campaignId, mapper, pendingSnapshot, seed);
        stage = "IN_SCENARIO";
        broadcast(new ServerMessage.Event("scenario", "已載入存檔 —— 對局重建,續玩開始。"));
        broadcast(new ServerMessage.LogHistory(pendingEventLog));
        for (Map.Entry<String, WebSocketSession> e : clients.entrySet()) {
            String inv = investigatorFor(e.getKey());
            if (inv != null) game.join(inv, e.getValue());   // 送初始 STATE → client 切到戰役板
        }
        pendingSnapshot = null;
        pendingEventLog = List.of();   // 回放完釋放
    }

    public synchronized SessionSummary summary() {
        List<SessionSummary.MemberBrief> briefs = new ArrayList<>();
        for (Member m : roster.values()) {
            briefs.add(new SessionSummary.MemberBrief(m.displayName, m.investigatorId));
        }
        return new SessionSummary(campaignId, name, campaignKey, difficulty, stage, roster.size(), briefs);
    }

    private ServerMessage.SessionRoster rosterMessage() {
        List<RosterMember> members = new ArrayList<>();
        for (Member m : roster.values()) {
            members.add(new RosterMember(m.playerId, m.displayName, m.investigatorId, m.ready, m.status,
                    clients.containsKey(m.playerId), m.physicalTrauma, m.mentalTrauma));
        }
        // 牌組/載入階段任何人都能強制越過屏障(熟人內網信任;docs/09 §8.3)。
        boolean canForce = "DECKBUILDING".equals(stage) || "LOADING".equals(stage);
        return new ServerMessage.SessionRoster(campaignId, name, campaignKey, stage, difficulty, members, canForce,
                List.copyOf(deadInvestigators), currentChapter);
    }

    private void broadcastRoster() throws IOException {
        broadcast(rosterMessage());
    }

    private void broadcast(ServerMessage msg) throws IOException {
        for (WebSocketSession ws : clients.values()) {
            send(ws, msg);
        }
    }

    private void send(WebSocketSession ws, ServerMessage msg) throws IOException {
        if (!ws.isOpen()) return;
        try {
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IllegalStateException | IOException closed) {
            // 客戶端在送出瞬間斷線的競態:忽略,不影響其他人
        }
    }

    /** Server-side roster member (mirrors dto/RosterMember). */
    private static final class Member {
        final String playerId;
        String displayName;
        String investigatorId = null;   // 尚未選角
        boolean ready = false;
        String status = "ACTIVE";
        List<String> deck = new ArrayList<>();   // 牌組(卡名清單;紀錄用)
        int xp = 0;
        int physicalTrauma = 0;   // 創傷跨章保留(docs/09 §9;官方 p20)
        int mentalTrauma = 0;
        Member(String playerId, String displayName) {
            this.playerId = playerId;
            this.displayName = displayName;
        }
    }

    /** Tracks one open new-character vote (docs/09 §10). */
    private static final class CharVote {
        final String requestId;
        final String subjectPlayerId;
        final String deadInvestigatorId;
        final java.util.Set<String> outstanding;
        int yes = 0;
        int total = 0;
        CharVote(String requestId, String subjectPlayerId, String deadInvestigatorId, java.util.Set<String> outstanding) {
            this.requestId = requestId;
            this.subjectPlayerId = subjectPlayerId;
            this.deadInvestigatorId = deadInvestigatorId;
            this.outstanding = outstanding;
        }
    }

    /** Tracks one open seat-claim vote (docs/09 P6). */
    private static final class ClaimVote {
        final String requestId;
        final String claimerId;
        final String targetPlayerId;
        final java.util.Set<String> outstanding;
        int yes = 0;
        int total = 0;
        ClaimVote(String requestId, String claimerId, String targetPlayerId, java.util.Set<String> outstanding) {
            this.requestId = requestId;
            this.claimerId = claimerId;
            this.targetPlayerId = targetPlayerId;
            this.outstanding = outstanding;
        }
    }

    /** Tracks one open save-vote (docs/09 §7). */
    private static final class SaveVote {
        final String requestId;
        final String requestedBy;
        final java.util.Set<String> outstanding;
        int yes = 0;
        SaveVote(String requestId, String requestedBy, java.util.Set<String> outstanding) {
            this.requestId = requestId;
            this.requestedBy = requestedBy;
            this.outstanding = outstanding;
        }
    }
}
