package com.arkham.server.session;

import com.arkham.engine.RulesEngine;
import com.arkham.engine.event.GameEvent;
import com.arkham.engine.model.ChoiceKind;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.scenario.ScenarioFactory;
import com.arkham.server.dto.ServerMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One game room: an authoritative {@link RulesEngine} plus the connected clients
 * (investigatorId → socket). Turns intents into engine calls and pushes each client
 * its own filtered {@code STATE} (protocol.md).
 *
 * <p>It also drives the COMMIT_CARDS <b>barrier</b> (docs/05 §2.1): when an intent opens
 * a skill test, the session sends a {@code CHOICE_REQUEST} to every eligible committer
 * and waits for all connected ones to respond before calling
 * {@link RulesEngine#resolveCommit} — the multi-client synchronisation point.
 *
 * <p>All public methods are {@code synchronized}: a room is single-threaded, which also
 * serialises writes to the (non-thread-safe) WebSocket sessions.
 */
public final class GameSession {

    private final String sessionId;
    private final ObjectMapper mapper;
    private RulesEngine engine;                 // 續玩時會重建 → 非 final
    private final long seed;
    private boolean autoPersist = true;         // campaign 內嵌對局關閉(改由 CampaignSession 落地)
    private static final ObjectMapper SAVE_MAPPER = saveMapper();
    private final Map<String, WebSocketSession> clients = new ConcurrentHashMap<>();

    private Barrier barrier; // non-null while a commit barrier is open
    private SaveVote saveVote; // non-null while a save-vote is open
    private String optionRequestId; // 反應能力 CHOOSE_OPTION 的請求 id(等擁有者回答)
    private final List<GameEvent> eventLog = new java.util.ArrayList<>(); // 累積出牌/事件(供存檔;有上限)
    private int lastCheckpointRound = 1;

    /** eventLog 上限:記憶體與存檔/廣播體積都只留最近 N 條(長局不無界成長)。 */
    private static final int MAX_EVENT_LOG = 500;

    public GameSession(String sessionId, ObjectMapper mapper) {
        this.sessionId = sessionId;
        this.mapper = mapper;
        // Deterministic per-room seed so a session is reproducible/replayable.
        this.seed = sessionId.hashCode();
        this.engine = ScenarioFactory.newEngine(seed);
    }

    /**
     * Build a session around a pre-constructed engine — used by a campaign's
     * START_SCENARIO (docs/09) to launch the scenario from the chosen roster.
     */
    public GameSession(String sessionId, ObjectMapper mapper, RulesEngine engine, long seed) {
        this.sessionId = sessionId;
        this.mapper = mapper;
        this.seed = seed;
        this.engine = engine;
        this.autoPersist = false;   // campaign 內嵌:存檔由 CampaignSession 負責
    }

    public String sessionId() {
        return sessionId;
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    /** Attach a client as {@code investigatorId} and send it its initial state. */
    public synchronized void join(String investigatorId, WebSocketSession ws) throws IOException {
        if (engine.state().investigator(investigatorId) == null) {
            send(ws, new ServerMessage.Error("Unknown investigator: " + investigatorId));
            return;
        }
        clients.put(investigatorId, ws);
        send(ws, new ServerMessage.State(engine.viewFor(investigatorId)));
    }

    /** 舊 room 路徑的離線:立即視為「不投入」並清投票(無接手概念)。 */
    public synchronized void leave(String investigatorId) {
        clients.remove(investigatorId);
        try {
            skipCommitter(investigatorId, investigatorId + " 已離線,本次檢定視為不投入。");
            if (saveVote != null && saveVote.outstanding.remove(investigatorId)) {
                resolveSaveVoteIfComplete();   // 掉線者不再擋存檔投票
            }
        } catch (IOException ignored) { /* 離線清理 best-effort */ }
    }

    /** 是否已無任何連線(供空房回收)。 */
    public synchronized boolean isEmpty() {
        return clients.isEmpty();
    }

    /** campaign 掉線:只移除連線,保留屏障等待接手(docs/09 §9 重打掉線那一動)。 */
    public synchronized void detach(String investigatorId) {
        clients.remove(investigatorId);
    }

    /**
     * 接手逃生口:掉線者逾時仍未歸隊 → 視為不投入,解開卡住的檢定屏障
     * (由 CampaignSession 的計時器呼叫;若已重連則不動作)。
     */
    public synchronized void autoSkipIfAbsent(String investigatorId) {
        if (clients.containsKey(investigatorId)) return;   // 已接手 → 保留「重打那一動」
        try {
            skipCommitter(investigatorId, "⏱ " + investigatorId + " 逾時未歸隊,本次檢定視為不投入。");
            // 卡在他的反應能力詢問 → 視為略過,解開對局
            if (engine.hasPendingOption()
                    && engine.pendingOptionInfo().investigatorId().equals(investigatorId)) {
                optionRequestId = null;
                List<GameEvent> ev2 = engine.resolveOption(false);
                if (!engine.hasPendingOption() && engine.hasPendingReveal()) {
                    ev2.addAll(engine.resolveReveal());
                }
                broadcastEvents(ev2);
                broadcastState();
                maybeOpenOption();
            }
        } catch (IOException ignored) { /* 計時器清理 best-effort */ }
    }

    /** 把某位尚未回應的投入者記為「不投入」;若屏障因此收齊 → 結算。 */
    private void skipCommitter(String investigatorId, String message) throws IOException {
        if (barrier == null || !barrier.outstanding.contains(investigatorId)) return;
        barrier.responses.put(investigatorId, List.of());
        barrier.outstanding.remove(investigatorId);
        broadcast(new ServerMessage.Event("barrier", message));
        if (barrier.outstanding.isEmpty()) {
            resolveBarrier();
        }
    }

    /**
     * 接手(docs/09 §9):把重連的 socket 接回其調查員,送當前 STATE;若對局正卡在
     * 該調查員尚未回應的投入屏障,補發<b>同一個</b> CHOICE_REQUEST —— 等同「重打掉線的那一動」。
     */
    public synchronized void reattach(String investigatorId, WebSocketSession ws) throws IOException {
        if (engine.state().investigator(investigatorId) == null) return;
        clients.put(investigatorId, ws);
        send(ws, new ServerMessage.State(engine.viewFor(investigatorId)));
        if (barrier != null && barrier.outstanding.contains(investigatorId)) {
            String requestId = barrier.requestIdByInv.get(investigatorId);
            if (requestId != null) {
                send(ws, new ServerMessage.ChoiceRequest(requestId, ChoiceKind.COMMIT_CARDS,
                        engine.commitOptionsFor(investigatorId)));
            }
        }
        // 卡在自己反應能力的詢問 → 補發同一個決策(重打掉線那一動)
        if (engine.hasPendingOption() && investigatorId.equals(engine.pendingOptionInfo().investigatorId())) {
            if (optionRequestId == null) {
                optionRequestId = UUID.randomUUID().toString();
            }
            send(ws, new ServerMessage.ChoiceRequest(optionRequestId, ChoiceKind.CHOOSE_OPTION,
                    engine.optionOptionsFor()));
        }
    }

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    public synchronized void handleIntent(String investigatorId, IntentAction action,
                                          Map<String, Object> payload) throws IOException {
        List<GameEvent> events;
        try {
            events = engine.applyIntent(investigatorId, action, payload);
        } catch (RuntimeException ex) {
            sendTo(investigatorId, new ServerMessage.Error(ex.getMessage()));
            return;
        }
        broadcastEvents(events);
        if (engine.hasPendingCommit()) {
            openBarrier();
        } else {
            broadcastState();
            maybeOpenOption();   // 反應能力(如 Joe 成功調查後抽牌)→ 問擁有者
        }
        // 每回合結算:回合數增加時,伺服器端自動存檔(docs/08 §6.5)
        int r = engine.state().getRound();
        if (r > lastCheckpointRound) {
            lastCheckpointRound = r;
            persistSnapshot(buildSnapshot());
        }
    }

    // ------------------------------------------------------------------
    // Commit barrier (docs/05 §2.1)
    // ------------------------------------------------------------------

    private void openBarrier() throws IOException {
        RulesEngine.PendingCommit pc = engine.pendingCommit();
        barrier = new Barrier();
        for (String invId : pc.eligibleInvestigatorIds()) {
            WebSocketSession ws = clients.get(invId);
            if (ws == null) {
                barrier.responses.put(invId, List.of()); // absent committer ⇒ auto-skip
                continue;
            }
            String requestId = UUID.randomUUID().toString();
            barrier.requestIdByInv.put(invId, requestId);
            barrier.outstanding.add(invId);
            send(ws, new ServerMessage.ChoiceRequest(requestId, ChoiceKind.COMMIT_CARDS,
                    engine.commitOptionsFor(invId)));
        }
        if (barrier.outstanding.isEmpty()) {
            resolveBarrier(); // nobody left to ask (all eligible committers disconnected)
        }
    }

    /** Record one committer's response; resolve the test once everyone has answered. */
    public synchronized void submitCommit(String requestId, List<String> committedCardIds) throws IOException {
        if (barrier == null) {
            return; // stale / unexpected response
        }
        String invId = barrier.investigatorForRequest(requestId);
        if (invId == null || !barrier.outstanding.contains(invId)) {
            return;
        }
        barrier.responses.put(invId, committedCardIds);
        barrier.outstanding.remove(invId);
        if (barrier.outstanding.isEmpty()) {
            resolveBarrier();
        }
    }

    private void resolveBarrier() throws IOException {
        Map<String, List<String>> responses = barrier.responses;
        barrier = null;
        List<GameEvent> events;
        try {
            events = engine.resolveCommit(responses);
        } catch (RuntimeException ex) {
            broadcast(new ServerMessage.Error(ex.getMessage()));
            return;
        }
        broadcastEvents(events);
        broadcastState();
        maybeOpenOption();   // 檢定結算可能觸發反應能力(能力視窗)
    }

    // ------------------------------------------------------------------
    // 反應能力詢問(docs/11 §B3 原型):引擎暫停 → 對擁有者發 CHOOSE_OPTION
    // ------------------------------------------------------------------

    private void maybeOpenOption() throws IOException {
        if (!engine.hasPendingOption() || optionRequestId != null) {
            return;
        }
        RulesEngine.PendingOption po = engine.pendingOptionInfo();
        WebSocketSession ws = clients.get(po.investigatorId());
        if (ws == null) {
            return;   // 擁有者離線:接手時補發(reattach),或逾時視為略過(autoSkipIfAbsent)
        }
        optionRequestId = UUID.randomUUID().toString();
        send(ws, new ServerMessage.ChoiceRequest(optionRequestId, ChoiceKind.CHOOSE_OPTION,
                engine.optionOptionsFor()));
    }

    /** 擁有者回答反應能力(optionId = "use" / "skip")。 */
    public synchronized void submitOption(String requestId, String optionId) throws IOException {
        if (optionRequestId == null || !optionRequestId.equals(requestId)) {
            return;   // 過期/非預期
        }
        optionRequestId = null;
        List<GameEvent> events = engine.resolveOption("use".equals(optionId));
        if (!engine.hasPendingOption() && engine.hasPendingReveal()) {
            events.addAll(engine.resolveReveal());   // B7:窗口反應答完 → 續抽混沌標記結算
        }
        broadcastEvents(events);
        broadcastState();
        maybeOpenOption();   // 佇列中還有下一個反應就接著問
    }

    // ------------------------------------------------------------------
    // 場內存檔(docs/08 §6.5):SAVE_REQUEST → 全員 SAVE_PROMPT → SAVE_VOTE → 快照複製各本機
    // ------------------------------------------------------------------

    /** 玩家發起保存 → 對全員發彈窗 SAVE_PROMPT,收集各自 SAVE_VOTE。 */
    public synchronized void handleSaveRequest(String requesterId) throws IOException {
        String reqId = UUID.randomUUID().toString();
        saveVote = new SaveVote(reqId, requesterId, new HashSet<>(clients.keySet()));
        broadcast(new ServerMessage.SavePrompt(reqId, requesterId));
        if (saveVote.outstanding.isEmpty()) commitSave();   // 無人在線 → 直接存
    }

    /** 記一票;全員回覆後,只要有人同意就存檔(合作信任)。 */
    public synchronized void submitSaveVote(String requestId, String investigatorId, boolean vote) throws IOException {
        if (saveVote == null || !saveVote.requestId.equals(requestId)) return;
        if (!saveVote.outstanding.remove(investigatorId)) return;
        if (vote) saveVote.yes++;
        resolveSaveVoteIfComplete();
    }

    /** 待回覆者清空(投完或掉線)即結案:任一同意 → 存;否則取消。 */
    private void resolveSaveVoteIfComplete() throws IOException {
        if (saveVote == null || !saveVote.outstanding.isEmpty()) return;
        if (saveVote.yes >= 1) {
            commitSave();
        } else {
            broadcast(new ServerMessage.Event("save", "存檔已取消(全員未同意)"));
            saveVote = null;
        }
    }

    private void commitSave() throws IOException {
        ServerMessage.SaveSnapshot snap = buildSnapshot();
        persistSnapshot(snap);                       // server 端落地(saves/<sessionId>.json)
        broadcast(snap);                             // 複製到每位玩家本機(離線備份)
        broadcast(new ServerMessage.Event("save", "已存檔:第 " + snap.round() + " 輪,已複製到各玩家本機。"));
        saveVote = null;
    }

    private ServerMessage.SaveSnapshot buildSnapshot() {
        return new ServerMessage.SaveSnapshot("Spreading Flames", engine.state().getRound(),
                SAVE_MAPPER.valueToTree(engine.state()), List.copyOf(eventLog));
    }

    /** 給 CampaignSession 打包戰役存檔用:目前引擎狀態的序列化樹。 */
    public synchronized Object snapshotStateNode() { return SAVE_MAPPER.valueToTree(engine.state()); }

    /** 累積的出牌/事件紀錄(給存檔的 log 回放)。 */
    public synchronized List<GameEvent> eventLogCopy() { return List.copyOf(eventLog); }

    /** 對局是否已結束(勝負已定)。 */
    public synchronized boolean isOver() { return engine.state().isGameOver(); }
    public synchronized boolean isWon() { return engine.state().isWon(); }

    /** lite XP 結算基礎:已揭示且清空線索的勝利地點數(勝利點展示區待 §C)。 */
    public synchronized int victoryPoints() {
        int v = 0;
        for (com.arkham.engine.model.LocationCard loc : engine.state().getLocations().values()) {
            if (loc.isVictory() && loc.isRevealed() && loc.getClues() == 0) v++;
        }
        return v;
    }

    /** 目前回合數。 */
    public synchronized int round() { return engine.state().getRound(); }

    /**
     * 載檔重建用的亂數種子:原 seed 混入存檔回合。
     * 直接沿用原 seed 會讓 RNG「從頭重播」同一序列 —— 記得前幾抽的玩家能預測混沌標記,
     * 存檔→重載形同重骰。混入回合後:同一份存檔重載仍可重現(除錯/重播),但不重複原局序列。
     */
    private static long reloadSeed(long seed, int round) {
        return seed ^ ((round + 1L) * 0x9E3779B97F4A7C15L);
    }

    /** 從快照狀態重建一個對局(docs/09 P3 載入用)。 */
    public static GameSession fromSnapshot(String sessionId, ObjectMapper mapper, Object stateNode, long seed) {
        com.arkham.engine.model.GameState st =
                SAVE_MAPPER.convertValue(stateNode, com.arkham.engine.model.GameState.class);
        RulesEngine engine = new RulesEngine(st,
                new com.arkham.engine.rng.SeededRng(reloadSeed(seed, st.getRound())));
        return new GameSession(sessionId, mapper, engine, seed);
    }

    /** 續玩:host 送回快照的 state → 反序列化重建引擎 → 廣播狀態(docs/08)。 */
    public synchronized void resume(Object stateNode) throws IOException {
        try {
            com.arkham.engine.model.GameState st =
                    SAVE_MAPPER.convertValue(stateNode, com.arkham.engine.model.GameState.class);
            engine = new RulesEngine(st,
                    new com.arkham.engine.rng.SeededRng(reloadSeed(seed, st.getRound())));
            eventLog.clear();
            lastCheckpointRound = st.getRound();
            broadcast(new ServerMessage.Event("resume", "已載入存檔:第 " + st.getRound() + " 輪,對局重建。"));
            broadcastState();
        } catch (RuntimeException ex) {
            broadcast(new ServerMessage.Error("續玩失敗:" + ex.getMessage()));
        }
    }

    /** 存檔用 mapper:以欄位序列化/反序列化整包 GameState(免逐類別加 setter)。 */
    private static ObjectMapper saveMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule(
                com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES));
        m.setVisibility(m.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY));
        m.disable(com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return m;
    }

    private void persistSnapshot(ServerMessage.SaveSnapshot snap) {
        if (!autoPersist) return;   // campaign 內嵌對局:存檔由 CampaignSession 落地,避免覆蓋
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("saves");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(sessionId + ".json"), mapper.writeValueAsString(snap));
        } catch (Exception ignored) { /* best-effort 存檔;不阻斷遊戲 */ }
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Push every connected client its own filtered view. */
    public synchronized void broadcastState() throws IOException {
        for (Map.Entry<String, WebSocketSession> e : clients.entrySet()) {
            send(e.getValue(), new ServerMessage.State(engine.viewFor(e.getKey())));
        }
    }

    private void broadcastEvents(List<GameEvent> events) throws IOException {
        eventLog.addAll(events);                       // 累積出牌/事件紀錄(供存檔)
        if (eventLog.size() > MAX_EVENT_LOG) {
            eventLog.subList(0, eventLog.size() - MAX_EVENT_LOG).clear();   // 只留最近 N 條
        }
        for (GameEvent ev : events) {
            broadcast(new ServerMessage.Event(ev.event(), ev.message()));
        }
    }

    private void broadcast(ServerMessage msg) throws IOException {
        for (WebSocketSession ws : clients.values()) {
            send(ws, msg);
        }
    }

    private void sendTo(String investigatorId, ServerMessage msg) throws IOException {
        WebSocketSession ws = clients.get(investigatorId);
        if (ws != null) {
            send(ws, msg);
        }
    }

    private void send(WebSocketSession ws, ServerMessage msg) throws IOException {
        if (!ws.isOpen()) return;
        try {
            ws.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
        } catch (IllegalStateException | IOException closed) {
            // 客戶端在送出瞬間斷線的競態:忽略
        }
    }

    /** Tracks one open commit barrier. */
    private static final class Barrier {
        final Map<String, String> requestIdByInv = new HashMap<>();
        final Set<String> outstanding = new HashSet<>();
        final Map<String, List<String>> responses = new HashMap<>();

        String investigatorForRequest(String requestId) {
            return requestIdByInv.entrySet().stream()
                    .filter(e -> e.getValue().equals(requestId))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Tracks one open save-vote. */
    private static final class SaveVote {
        final String requestId;
        final String requestedBy;
        final Set<String> outstanding;
        int yes = 0;
        SaveVote(String requestId, String requestedBy, Set<String> outstanding) {
            this.requestId = requestId;
            this.requestedBy = requestedBy;
            this.outstanding = outstanding;
        }
    }
}
