package com.arkham.engine;

import com.arkham.engine.event.GameEvent;
import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.CardType;
import com.arkham.engine.model.EnemyCard;
import com.arkham.engine.model.EnemyDef;
import com.arkham.engine.model.EncounterCard;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.model.Keyword;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.model.Phase;
import com.arkham.engine.model.SkillType;
import com.arkham.engine.protocol.CommitCardsOptions;
import com.arkham.engine.rng.SeededRng;
import com.arkham.engine.view.ActView;
import com.arkham.engine.view.AgendaView;
import com.arkham.engine.view.EnemyView;
import com.arkham.engine.view.GameStateView;
import com.arkham.engine.view.LocationView;
import com.arkham.engine.view.OtherInvestigatorView;
import com.arkham.engine.view.SelfView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * The phase/turn state machine and intent handler (docs/05 §1, §4). Owns the
 * authoritative {@link GameState} and the single seeded {@link SeededRng} so every
 * outcome is reproducible.
 *
 * <p><b>Turn structure.</b> The investigation phase runs one investigator at a time, but
 * the order is the team's to choose (docs/05 §4.1): any investigator who has not yet
 * acted this round may take the turn, provided nobody else is part-way through theirs.
 * They take up to three actions and pass with {@link IntentAction#END_TURN}. Once
 * everyone has taken their turn the round closes — enemy phase, upkeep, then the next
 * round's mythos phase (skipped in round 1) — and a fresh investigation phase begins.
 *
 * <p><b>Elimination.</b> An investigator who is defeated (damage ≥ health or horror ≥
 * sanity) or who resigns leaves the game, but the scenario continues. Only when
 * <em>every</em> investigator is eliminated does the game end with no resolution.
 *
 * <p>Actions that trigger a skill test (INVESTIGATE / FIGHT / EVADE) don't resolve
 * immediately: {@link #applyIntent} opens a <em>commit barrier</em> (docs/05 §2.1) —
 * exposed via {@link #hasPendingCommit()} / {@link #pendingCommit()} /
 * {@link #commitOptionsFor(String)} — and the server drives it, collecting each
 * eligible committer's cards before calling {@link #resolveCommit(Map)} to draw the
 * token and apply the result. All other actions resolve synchronously.
 */
public final class RulesEngine {

    /**
     * Every action provokes an attack of opportunity from each engaged, ready enemy
     * except Fight, Evade, Parley and Resign.
     */
    public static final Set<IntentAction> PROVOKES_ATTACK_OF_OPPORTUNITY = EnumSet.of(
            IntentAction.DRAW,
            IntentAction.GAIN_RESOURCE,
            IntentAction.PLAY_CARD,
            IntentAction.ACTIVATE,
            IntentAction.MOVE,
            IntentAction.INVESTIGATE,
            IntentAction.ENGAGE);

    /** Actions per investigator turn. */
    private static final int ACTIONS_PER_TURN = 3;

    /**
     * How many cards an investigator may commit to <em>someone else's</em> test. The
     * performer may commit any number; each helper at their location may commit one
     * (docs/05 §2.1, rulebook p.15/33).
     */
    private static final int ALLY_COMMIT_LIMIT = 1;

    private final GameState state;
    private final SeededRng rng;
    private PendingCommit pendingCommit;

    public RulesEngine(GameState state, SeededRng rng) {
        this.state = state;
        this.rng = rng;
    }

    public GameState state() {
        return state;
    }

    // ------------------------------------------------------------------
    // Commit barrier (docs/05 §2.1)
    // ------------------------------------------------------------------

    /**
     * A pending skill-test commit barrier. The server sends a COMMIT_CARDS request to
     * every id in {@code eligibleInvestigatorIds}, then calls {@link #resolveCommit}.
     */
    public record PendingCommit(
            SkillType skill,
            int base,
            int difficulty,
            String performerId,
            IntentAction actionKind,
            String targetId,
            List<String> eligibleInvestigatorIds) {}

    public boolean hasPendingCommit() {
        return pendingCommit != null;
    }

    public PendingCommit pendingCommit() {
        return pendingCommit;
    }

    /**
     * Build the COMMIT_CARDS options for one eligible committer: their matching/wild hand
     * cards and their commit limit. The performer may commit any number of cards; every
     * other investigator at their location may commit at most one (docs/05 §2.1).
     */
    public CommitCardsOptions commitOptionsFor(String investigatorId) {
        PendingCommit pc = requirePending();
        Investigator inv = requireInvestigator(investigatorId);
        List<com.arkham.engine.model.HandCard> eligible = inv.getHand().stream()
                .filter(c -> c.eligibleFor(pc.skill()))
                .map(CardInstance::toHandCard)
                .toList();
        int maxCommit = investigatorId.equals(pc.performerId()) ? eligible.size() : ALLY_COMMIT_LIMIT;
        return new CommitCardsOptions(pc.skill(), pc.base(), pc.difficulty(), eligible, maxCommit);
    }

    // ------------------------------------------------------------------
    // Intent dispatch
    // ------------------------------------------------------------------

    /**
     * Validate and apply a player intent. For a skill-test action this opens the
     * commit barrier and returns the pre-test events (e.g. attacks of opportunity);
     * the caller must then resolve the barrier via {@link #resolveCommit}.
     *
     * @throws IllegalStateException    if the game is over or a barrier is already open
     * @throws IllegalArgumentException if the intent is illegal in the current state
     */
    public List<GameEvent> applyIntent(String investigatorId, IntentAction action, Map<String, Object> payload) {
        if (state.isGameOver()) {
            throw new IllegalStateException("Game is over");
        }
        if (pendingCommit != null) {
            throw new IllegalStateException("A skill-test commit is pending; resolve it first");
        }
        Investigator inv = requireInvestigator(investigatorId);
        List<GameEvent> events = new ArrayList<>();
        Map<String, Object> p = payload == null ? Map.of() : payload;

        switch (action) {
            case DRAW -> drawAction(inv, events);
            case GAIN_RESOURCE -> gainResourceAction(inv, events);
            case PLAY_CARD -> playCard(inv, str(p, "cardId"), events);
            case ACTIVATE -> activate(inv, str(p, "cardId"), events);
            case MOVE -> move(inv, str(p, "toLocationId"), events);
            case INVESTIGATE -> investigate(inv, events);
            case ENGAGE -> engage(inv, str(p, "enemyId"), events);
            case FIGHT -> fight(inv, str(p, "enemyId"), events);
            case EVADE -> evade(inv, str(p, "enemyId"), events);
            case PARLEY -> parley(inv, str(p, "targetId"), events);
            case RESIGN -> resign(inv, events);
            case MULLIGAN -> events.addAll(mulligan(investigatorId, strList(p, "cardIds")));
            case END_TURN -> endTurn(inv, events);
            case ADVANCE_ACT -> advanceAct(inv, events);
        }
        return events;
    }

    // ------------------------------------------------------------------
    // Actions (three per turn)
    // ------------------------------------------------------------------

    private void drawAction(Investigator inv, List<GameEvent> events) {
        requireActionTurn(inv);
        if (provoked(inv, IntentAction.DRAW, "抽牌", events)) {
            return;
        }
        drawCard(inv, events);
        if (state.isGameOver() || inv.isEliminated()) {
            return;
        }
        inv.spendAction();
    }

    private void gainResourceAction(Investigator inv, List<GameEvent> events) {
        requireActionTurn(inv);
        if (provoked(inv, IntentAction.GAIN_RESOURCE, "獲得資源", events)) {
            return;
        }
        inv.gainResources(1);
        events.add(GameEvent.of("RESOURCE", inv.getName() + " 獲得 1 資源(共 " + inv.getResources() + ")。"));
        inv.spendAction();
    }

    private void playCard(Investigator inv, String cardId, List<GameEvent> events) {
        requireActionTurn(inv);
        CardInstance card = inv.findHandCard(cardId);
        if (card == null) {
            throw new IllegalArgumentException("That card is not in your hand");
        }
        if (card.isWeakness()) {
            throw new IllegalArgumentException("A weakness cannot be played");
        }
        if (card.cardType() == CardType.SKILL) {
            throw new IllegalArgumentException("A skill card is committed to a test, not played as an action");
        }
        if (inv.getResources() < card.cost()) {
            throw new IllegalArgumentException(
                    "Not enough resources: need " + card.cost() + ", have " + inv.getResources());
        }
        if (provoked(inv, IntentAction.PLAY_CARD, "打出卡牌", events)) {
            return;
        }
        inv.spendResources(card.cost());
        if (card.cardType() == CardType.ASSET) {
            inv.putIntoPlay(card); // an asset stays in play
            events.add(GameEvent.of("PLAY_CARD",
                    inv.getName() + " 打出支援「" + card.name() + "」(花費 " + card.cost() + " 資源)。"));
        } else {
            inv.discard(card); // an event resolves once, then is discarded
            events.add(GameEvent.of("PLAY_CARD",
                    inv.getName() + " 打出事件「" + card.name() + "」(花費 " + card.cost() + " 資源),結算後棄置。"));
        }
        inv.spendAction();
    }

    /**
     * Use an action ability (the ➡ arrow on a card). No card in the lite scenario prints
     * one, so this validates the target and reports that there is nothing to activate —
     * the action exists in the protocol, the card data does not yet.
     */
    private void activate(Investigator inv, String cardId, List<GameEvent> events) {
        requireActionTurn(inv);
        CardInstance card = cardId == null ? null : inv.getInPlay().stream()
                .filter(c -> c.cardId().equals(cardId))
                .findFirst().orElse(null);
        if (card == null) {
            throw new IllegalArgumentException("That card is not in play in front of you");
        }
        throw new IllegalArgumentException("「" + card.name() + "」沒有可啟動的行動能力");
    }

    private void move(Investigator inv, String toLocationId, List<GameEvent> events) {
        requireActionTurn(inv);
        LocationCard here = state.location(inv.getLocationId());
        if (toLocationId == null || !here.connectsTo(toLocationId)) {
            throw new IllegalArgumentException("Destination is not connected to your current location");
        }
        if (provoked(inv, IntentAction.MOVE, "移動", events)) {
            return;
        }
        inv.setLocationId(toLocationId);
        LocationCard dest = state.location(toLocationId);
        if (!dest.isRevealed()) {
            revealLocation(dest, events);
            if (dest.getSpawnDefKey() != null) {
                spawnEnemy(dest.getSpawnDefKey(), toLocationId, inv.getId(), events); // engaged on reveal
            }
        } else {
            events.add(GameEvent.of("MOVE", inv.getName() + " 移動到 " + dest.getName() + "。"));
        }
        // Moving into a ready, unengaged enemy engages it (rules: enemy engagement triggers).
        engageEnemiesAt(inv, events);
        inv.spendAction();
    }

    private void investigate(Investigator inv, List<GameEvent> events) {
        requireActionTurn(inv);
        LocationCard loc = state.location(inv.getLocationId());
        if (loc.getClues() <= 0) {
            throw new IllegalArgumentException("This location has no clues");
        }
        if (provoked(inv, IntentAction.INVESTIGATE, "調查", events)) {
            return;
        }
        openSkillTest(inv, SkillType.INTELLECT, loc.getShroud(), IntentAction.INVESTIGATE, loc.getId(), events);
    }

    private void engage(Investigator inv, String enemyId, List<GameEvent> events) {
        requireActionTurn(inv);
        EnemyCard e = enemyId != null
                ? state.enemy(enemyId)
                : state.enemiesAt(inv.getLocationId()).stream()
                        .filter(en -> !inv.getEngagedEnemyIds().contains(en.getId()))
                        .findFirst().orElse(null);
        if (e == null || !e.getLocationId().equals(inv.getLocationId())) {
            throw new IllegalArgumentException("No enemy to engage here");
        }
        // Engage is not on the exempt list, so it provokes (rules: only fight/evade/parley/resign are exempt).
        if (provoked(inv, IntentAction.ENGAGE, "交戰", events)) {
            return;
        }
        engageEnemy(e, inv, events); // takes it from whoever it was engaged with
        inv.spendAction();
    }

    private void fight(Investigator inv, String enemyId, List<GameEvent> events) {
        requireActionTurn(inv);
        EnemyCard e = requireAttackableEnemy(inv, enemyId, "攻擊");
        // Fight does not provoke an attack of opportunity.
        openSkillTest(inv, SkillType.COMBAT, e.getFight(), IntentAction.FIGHT, e.getId(), events);
    }

    private void evade(Investigator inv, String enemyId, List<GameEvent> events) {
        requireActionTurn(inv);
        EnemyCard e = requireAttackableEnemy(inv, enemyId, "躲避");
        // Evade does not provoke an attack of opportunity.
        openSkillTest(inv, SkillType.AGILITY, e.getEvade(), IntentAction.EVADE, e.getId(), events);
    }

    /**
     * Parley resolves whatever the target card's text says. Nothing in the lite scenario
     * prints a parley effect, so this validates the target and reports that — like
     * {@link #activate}, the action is wired but the card data is not there yet.
     */
    private void parley(Investigator inv, String targetId, List<GameEvent> events) {
        requireActionTurn(inv);
        EnemyCard e = targetId == null ? null : state.enemy(targetId);
        if (e == null || !e.getLocationId().equals(inv.getLocationId())) {
            throw new IllegalArgumentException("No parley target here");
        }
        // Parley does not provoke an attack of opportunity.
        throw new IllegalArgumentException("「" + e.getName() + "」沒有談判效果");
    }

    /** Resign: leave the game voluntarily. The scenario carries on without you. */
    private void resign(Investigator inv, List<GameEvent> events) {
        requireActionTurn(inv);
        // Resign does not provoke an attack of opportunity.
        events.add(GameEvent.of("RESIGN", inv.getName() + " 撤退,離開了這場冒險。"));
        eliminate(inv, Investigator.Elimination.RESIGNED, events);
    }

    // ------------------------------------------------------------------
    // Turn / act (free, not actions)
    // ------------------------------------------------------------------

    private void endTurn(Investigator inv, List<GameEvent> events) {
        claimTurn(inv); // you may also pass without having started, if nobody else is mid-turn
        inv.setActionsRemaining(0);
        inv.setTurnTaken(true); // the player token flips face down
        events.add(GameEvent.of("END_TURN", inv.getName() + " 結束回合。"));
        advanceTurn(events);
    }

    private void advanceAct(Investigator inv, List<GameEvent> events) {
        if (state.getPhase() != Phase.INVESTIGATION) {
            throw new IllegalArgumentException("Can only advance the act during the investigation phase");
        }
        int need = state.getAct().getThreshold();
        List<Investigator> contributors = state.investigatorsInPlay();
        int total = contributors.stream().mapToInt(Investigator::getCluesHeld).sum();
        if (total < need) {
            throw new IllegalArgumentException("Not enough clues to advance the act (" + total + "/" + need + ")");
        }
        int remaining = need;
        for (Investigator i : contributors) {
            int take = Math.min(remaining, i.getCluesHeld());
            i.spendClues(take);
            remaining -= take;
            if (remaining == 0) {
                break;
            }
        }
        state.getAct().spendClues(need);
        state.endGame(true, "你湊齊線索、推進了幕 —— 揭開了友人失蹤的真相!");
        events.add(GameEvent.of("ADVANCE_ACT", "推進幕:花費 " + need + " 線索。"));
        events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
    }

    /**
     * The one-time opening-hand adjustment: set aside up to five cards, redraw to five,
     * then shuffle the set-aside cards back in. Legal only on the first round, before
     * the investigator has spent an action.
     */
    public List<GameEvent> mulligan(String investigatorId, List<String> cardIds) {
        Investigator inv = requireInvestigator(investigatorId);
        if (state.getRound() != 1 || inv.getActionsRemaining() != ACTIONS_PER_TURN) {
            throw new IllegalArgumentException("The mulligan window has passed");
        }
        if (inv.hasMulliganed()) {
            throw new IllegalArgumentException("You have already adjusted your opening hand");
        }
        List<GameEvent> events = new ArrayList<>();
        List<String> ids = cardIds == null ? List.of() : cardIds;
        if (ids.size() > 5) {
            throw new IllegalArgumentException("You may set aside at most five cards");
        }

        List<CardInstance> setAside = new ArrayList<>();
        for (String cardId : ids) {
            CardInstance card = inv.findHandCard(cardId);
            if (card != null) {
                inv.getHand().remove(card);
                setAside.add(card);
            }
        }
        while (inv.getHand().size() < Investigator.OPENING_HAND_SIZE && !inv.getDeck().isEmpty()) {
            inv.drawFromDeck();
        }
        inv.getDeck().addAll(setAside);
        rng.shuffle(inv.getDeck());
        inv.setMulliganed(true);
        events.add(GameEvent.of("MULLIGAN",
                inv.getName() + " 調整起始手牌:放回 " + setAside.size() + " 張並重抽。"));
        return events;
    }

    /**
     * Offer the turn to the next investigator who has not yet acted. This is only a
     * default — until they spend an action, any other investigator who has not acted may
     * take the turn instead ({@link #claimTurn}). With nobody left to act, the round
     * closes (enemy phase → upkeep → next round's mythos).
     */
    private void advanceTurn(List<GameEvent> events) {
        if (state.isGameOver()) {
            return;
        }
        Investigator next = state.nextToAct(state.getActiveInvestigatorId());
        if (next != null) {
            state.setActiveInvestigatorId(next.getId());
            events.add(GameEvent.of("TURN", "輪到 " + next.getName() + " 行動(其他尚未行動的調查員亦可接手)。"));
            return;
        }
        endRound(events);
    }

    // ------------------------------------------------------------------
    // Skill-test barrier open / resolve
    // ------------------------------------------------------------------

    private void openSkillTest(Investigator performer, SkillType skill, int difficulty,
                               IntentAction actionKind, String targetId, List<GameEvent> events) {
        int base = performer.baseSkill(skill);
        List<String> eligible = new ArrayList<>();
        eligible.add(performer.getId());
        for (Investigator other : state.investigatorsInPlay()) {
            if (!other.getId().equals(performer.getId())
                    && other.getLocationId().equals(performer.getLocationId())) {
                eligible.add(other.getId()); // any investigator at this location may commit
            }
        }
        pendingCommit = new PendingCommit(skill, base, difficulty, performer.getId(), actionKind, targetId, eligible);
        events.add(GameEvent.of("SKILL_TEST",
                "技能檢定開始:" + skill + " 基礎 " + base + " / 難度 " + difficulty));
    }

    /**
     * Finish the pending skill test: sum committed matching/wild icons (enforcing the
     * one-card helper limit), draw the token via the seeded RNG, apply the result,
     * discard the committed cards and spend the performer's action.
     *
     * @param committedCardIdsByInvestigator investigator id → the card ids they committed
     */
    public List<GameEvent> resolveCommit(Map<String, List<String>> committedCardIdsByInvestigator) {
        PendingCommit pc = requirePending();
        Investigator performer = requireInvestigator(pc.performerId());
        List<GameEvent> events = new ArrayList<>();

        int committedIcons = 0;
        List<String> committedNames = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : committedCardIdsByInvestigator.entrySet()) {
            String invId = entry.getKey();
            if (!pc.eligibleInvestigatorIds().contains(invId)) {
                continue; // not an eligible committer
            }
            Investigator inv = state.investigator(invId);
            if (inv == null || !inv.isInPlay() || !inv.getLocationId().equals(performer.getLocationId())) {
                continue;
            }
            boolean isPerformer = invId.equals(pc.performerId());
            List<String> cardIds = entry.getValue();
            if (!isPerformer && cardIds.size() > ALLY_COMMIT_LIMIT) {
                cardIds = cardIds.subList(0, ALLY_COMMIT_LIMIT); // a helper commits at most one card
            }
            for (String cardId : cardIds) {
                CardInstance card = inv.findHandCard(cardId);
                if (card == null || !card.eligibleFor(pc.skill())) {
                    continue; // only matching/wild icons count (docs/05 §2.1)
                }
                int m = card.matchingIcons(pc.skill());
                committedIcons += m;
                inv.discard(card); // step 8: committed cards go to discard
                committedNames.add(card.name() + "(+" + m + ")"
                        + (isPerformer ? "" : " · " + inv.getName()));
            }
        }

        SkillTest.Result r = SkillTest.run(
                pc.skill(), pc.base(), pc.difficulty(), committedIcons, state.getChaosBag(), rng);

        if (!committedNames.isEmpty()) {
            events.add(GameEvent.of("SKILL_TEST",
                    "投入:" + String.join("、", committedNames) + " → 技能 " + r.effectiveSkill()));
        }
        events.add(GameEvent.of("SKILL_TEST", describeResult(r)));

        applySkillResult(pc, r, events);
        pendingCommit = null;
        if (!state.isGameOver() && performer.isInPlay()) {
            performer.spendAction();
        }
        return events;
    }

    private void applySkillResult(PendingCommit pc, SkillTest.Result r, List<GameEvent> events) {
        Investigator performer = requireInvestigator(pc.performerId());
        switch (pc.actionKind()) {
            case INVESTIGATE -> {
                if (r.success()) {
                    LocationCard loc = state.location(pc.targetId());
                    loc.removeClue();
                    performer.gainClue();
                    events.add(GameEvent.of("INVESTIGATE",
                            "調查成功,取得 1 線索(持有 " + performer.getCluesHeld() + ")。"));
                    if (r.elderSign()) {
                        performer.gainResources(1); // Joe Diamond elder-sign ability (prototype)
                        events.add(GameEvent.of("ELDER_SIGN", "⭐ 古老印記:獲得 1 資源。"));
                    }
                } else {
                    events.add(GameEvent.of("INVESTIGATE", "調查失敗,未取得線索。"));
                }
            }
            case FIGHT -> {
                EnemyCard e = state.enemy(pc.targetId());
                if (e == null) {
                    return;
                }
                if (r.success()) {
                    int dmg = 1 + performer.getWeaponBonus(); // combat deals 1 by default (docs/05 §4)
                    e.applyDamage(dmg);
                    events.add(GameEvent.of("FIGHT",
                            "戰鬥成功,對 " + e.getName() + " 造成 " + dmg + " 傷害("
                                    + e.getDamageOn() + "/" + e.getHealth() + ")。"));
                    if (e.isDefeated()) {
                        defeatEnemy(e, events);
                    }
                } else {
                    events.add(GameEvent.of("FIGHT", "戰鬥失敗。"));
                    if (e.hasKeyword(Keyword.RETALIATE) && e.isReady()) {
                        events.add(GameEvent.of("RETALIATE", "反擊 Retaliate!"));
                        enemyHit(performer, e, events); // retaliate does not exhaust (docs/05 §5)
                    }
                }
            }
            case EVADE -> {
                EnemyCard e = state.enemy(pc.targetId());
                if (e == null) {
                    return;
                }
                if (r.success()) {
                    e.setExhausted(true);
                    e.setEngagedWith(null);
                    performer.disengage(e.getId());
                    events.add(GameEvent.of("EVADE", "閃避成功," + e.getName() + " 耗竭並脫離交戰。"));
                } else {
                    events.add(GameEvent.of("EVADE", "閃避失敗。"));
                    if (e.hasKeyword(Keyword.ALERT) && e.isReady()) {
                        events.add(GameEvent.of("ALERT", "警覺 Alert!"));
                        enemyHit(performer, e, events); // alert enemy attacks the evader (docs/05 §5)
                    }
                }
            }
            default -> { /* no direct result */ }
        }
    }

    // ------------------------------------------------------------------
    // Round close: enemy phase → upkeep → mythos → next investigation
    // ------------------------------------------------------------------

    private void endRound(List<GameEvent> events) {
        // --- Enemy phase ---
        state.setPhase(Phase.ENEMY);
        events.add(GameEvent.of("PHASE", "—— 敵人階段 ——"));
        checkEngagements(events);          // 1. 敵軍交戰
        moveHunters(events);               // 2. 敵人移動
        if (state.isGameOver()) {
            return;
        }
        enemyAttacks(events);              // 3. 敵人攻擊
        if (state.isGameOver()) {
            return;
        }

        // --- Upkeep phase ---
        state.setPhase(Phase.UPKEEP);
        events.add(GameEvent.of("PHASE", "—— 補給階段 ——"));
        upkeep(events);
        if (state.isGameOver()) {
            return;
        }

        // --- Next round: Mythos (round ≥ 2) then Investigation ---
        state.incrementRound();
        events.add(GameEvent.of("ROUND", "—— 第 " + state.getRound() + " 輪 ——"));
        mythosPhase(events);
        if (state.isGameOver()) {
            return;
        }
        startInvestigationPhase(events);
    }

    /** Upkeep: ready everything, each investigator draws 1 and gains 1, then discards to 8. */
    private void upkeep(List<GameEvent> events) {
        for (EnemyCard e : state.getEnemies().values()) {
            e.setExhausted(false); // ready all cards, enemies included
        }
        // A readied enemy standing on an investigator engages immediately.
        checkEngagements(events);

        for (Investigator inv : state.investigatorsInPlay()) {
            inv.setTurnTaken(false); // the player token flips back face up
            drawCard(inv, events);
            if (state.isGameOver()) {
                return;
            }
            if (inv.isEliminated()) {
                continue; // an empty deck can defeat you on the horror it deals
            }
            inv.gainResources(1);
        }
        for (Investigator inv : state.investigatorsInPlay()) {
            discardDownToHandLimit(inv, events);
        }
    }

    /**
     * Enforce the eight-card hand limit. The rules let the player choose which cards to
     * discard; with no choice barrier here the engine discards from the end of the hand
     * and says so.
     */
    private void discardDownToHandLimit(Investigator inv, List<GameEvent> events) {
        if (!inv.isOverHandLimit()) {
            return;
        }
        int excess = inv.getHand().size() - Investigator.HAND_LIMIT;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < excess; i++) {
            CardInstance card = inv.getHand().get(inv.getHand().size() - 1);
            inv.discard(card);
            names.add(card.name());
        }
        events.add(GameEvent.of("DISCARD",
                inv.getName() + " 手牌超過 " + Investigator.HAND_LIMIT + " 張,棄掉 "
                        + String.join("、", names) + "。"));
    }

    private void startInvestigationPhase(List<GameEvent> events) {
        state.setPhase(Phase.INVESTIGATION);
        for (Investigator inv : state.investigatorsInPlay()) {
            inv.setTurnTaken(false);
            inv.setActionsRemaining(ACTIONS_PER_TURN);
        }
        Investigator lead = state.investigatorsInPlay().stream().findFirst().orElse(null);
        if (lead == null) {
            return; // everyone is out; the game has already ended
        }
        state.setActiveInvestigatorId(lead.getId());
        events.add(GameEvent.of("PHASE", "—— 調查階段 ——"));
        events.add(GameEvent.of("TURN", "輪到 " + lead.getName() + " 行動。"));
    }

    // ------------------------------------------------------------------
    // Enemy phase
    // ------------------------------------------------------------------

    /**
     * Engagement: a ready, unengaged enemy sharing a location with an investigator
     * engages them. Aloof enemies never engage on their own.
     */
    private void checkEngagements(List<GameEvent> events) {
        for (EnemyCard e : new ArrayList<>(state.getEnemies().values())) {
            if (e.isEngaged() || e.isExhausted() || e.hasKeyword(Keyword.ALOOF)) {
                continue;
            }
            Investigator here = investigatorAt(e.getLocationId());
            if (here != null) {
                engageEnemy(e, here, events);
            }
        }
    }

    private void moveHunters(List<GameEvent> events) {
        for (EnemyCard e : new ArrayList<>(state.getEnemies().values())) {
            if (e.isExhausted() || e.isEngaged() || !e.hasKeyword(Keyword.HUNTER)) {
                continue;
            }
            Investigator target = nearestInvestigator(e.getLocationId());
            if (target == null) {
                continue;
            }
            String step = nextStepToward(e.getLocationId(), target.getLocationId());
            if (step == null) {
                continue;
            }
            e.setLocationId(step);
            events.add(GameEvent.of("ENEMY_MOVE",
                    "🐾 " + e.getName() + "(獵人)移動到 " + state.location(step).getName() + "。"));
            Investigator here = investigatorAt(step);
            if (here != null && !e.hasKeyword(Keyword.ALOOF)) {
                engageEnemy(e, here, events); // moving onto an investigator engages them
            }
        }
    }

    /** Each ready engaged enemy attacks, then exhausts. Resolved in player order. */
    private void enemyAttacks(List<GameEvent> events) {
        for (Investigator inv : state.investigatorsInPlay()) {
            for (String enemyId : new ArrayList<>(inv.getEngagedEnemyIds())) {
                EnemyCard e = state.enemy(enemyId);
                if (e == null || !e.isReady()) {
                    continue;
                }
                enemyHit(inv, e, events);
                e.setExhausted(true); // an attacking enemy exhausts
                if (state.isGameOver()) {
                    return;
                }
                if (inv.isEliminated()) {
                    break; // no further attacks on someone who has already left
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Mythos phase (docs/05 §1.1)
    // ------------------------------------------------------------------

    private void mythosPhase(List<GameEvent> events) {
        state.setPhase(Phase.MYTHOS);
        events.add(GameEvent.of("PHASE", "—— 神話階段 ——"));

        Agenda agenda = state.getAgenda();
        agenda.addDoom(1);
        events.add(GameEvent.of("MYTHOS",
                "🕯️ 密謀累積毀滅:" + state.totalDoomInPlay() + "/" + agenda.getThreshold() + "。"));
        if (checkAgendaAdvance(events)) {
            return;
        }

        for (Investigator inv : state.investigatorsInPlay()) {
            EncounterCard card = state.drawEncounter();
            if (card != null) {
                resolveEncounter(inv, card, events);
            }
            if (state.isGameOver()) {
                return;
            }
        }
    }

    /**
     * The agenda advances when <em>all</em> doom in play — on the agenda, on locations and
     * on enemies — reaches its threshold. Advancing discards every doom token in play;
     * in this one-agenda scenario it then loses the game.
     */
    private boolean checkAgendaAdvance(List<GameEvent> events) {
        Agenda agenda = state.getAgenda();
        if (!agenda.reachedBy(state.totalDoomInPlay())) {
            return false;
        }
        state.clearAllDoomInPlay();
        state.endGame(false, "密謀推進 —— 黑暗吞噬了阿卡姆大學。");
        events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
        return true;
    }

    private void resolveEncounter(Investigator inv, EncounterCard card, List<GameEvent> events) {
        if (card.type() == EncounterCard.Type.ENEMY) {
            String remote = pickRemoteRevealed(inv);
            if (remote != null) {
                spawnEnemy(card.defKey(), remote, null, events); // spawn unengaged to show the hunter chase
                events.add(GameEvent.of("MYTHOS",
                        "🃏 遭遇(敵人):於 " + state.location(remote).getName() + " 現身、未交戰。"));
            } else {
                spawnEnemy(card.defKey(), inv.getLocationId(), inv.getId(), events);
            }
            return;
        }
        // Treachery
        events.add(GameEvent.of("MYTHOS", "🃏 遭遇(詭計):" + card.name() + "。"));
        switch (card.effect()) {
            case HORROR -> {
                inv.takeHorror(card.amount());
                events.add(GameEvent.of("TREACHERY", inv.getName() + " 受到 " + card.amount() + " 恐懼。"));
                checkElimination(inv, events);
            }
            case DOOM -> {
                state.getAgenda().addDoom(card.amount());
                events.add(GameEvent.of("TREACHERY",
                        "密謀 +" + card.amount() + " 毀滅(" + state.totalDoomInPlay()
                                + "/" + state.getAgenda().getThreshold() + ")。"));
                checkAgendaAdvance(events);
            }
            case NOTHING -> events.add(GameEvent.of("TREACHERY", "(無事發生。)"));
        }
    }

    private String pickRemoteRevealed(Investigator inv) {
        String pick = null;
        for (LocationCard loc : state.getLocations().values()) {
            if (loc.isRevealed() && !loc.getId().equals(inv.getLocationId())) {
                pick = loc.getId(); // prototype takes the last such candidate
            }
        }
        return pick;
    }

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    /**
     * Draw one card. An empty deck costs 1 horror and the discard pile is shuffled back
     * to form a new deck — which can itself defeat the investigator.
     */
    private void drawCard(Investigator inv, List<GameEvent> events) {
        if (inv.getDeck().isEmpty()) {
            if (inv.getDiscardPile().isEmpty()) {
                events.add(GameEvent.of("DRAW", inv.getName() + " 沒有牌可抽。"));
                return;
            }
            inv.takeHorror(1);
            inv.reshuffleDiscardIntoDeck();
            rng.shuffle(inv.getDeck());
            events.add(GameEvent.of("DRAW", inv.getName() + " 牌庫抽盡:受到 1 恐懼,棄牌洗回牌庫。"));
            checkElimination(inv, events);
            if (state.isGameOver() || inv.isEliminated()) {
                return;
            }
        }
        CardInstance card = inv.drawFromDeck();
        if (card != null) {
            events.add(GameEvent.of("DRAW", inv.getName() + " 抽了一張牌。"));
        }
    }

    // ------------------------------------------------------------------
    // Enemy helpers
    // ------------------------------------------------------------------

    private void revealLocation(LocationCard dest, List<GameEvent> events) {
        dest.setRevealed(true);
        // Clue value scales with the setup player count, which resigning does not reduce.
        dest.setClues(dest.getClueValue() * state.getPlayerCount());
        events.add(GameEvent.of("MOVE",
                "進入並揭示 " + dest.getName() + ",放上 " + dest.getClues() + " 線索。"));
    }

    private String spawnEnemy(String defKey, String locationId, String engageInvId, List<GameEvent> events) {
        EnemyDef def = state.getEnemyDefs().get(defKey);
        if (def == null) {
            throw new IllegalArgumentException("Unknown enemy definition: " + defKey);
        }
        String id = state.nextEnemyId();
        EnemyCard e = new EnemyCard(id, def.defKey(), def.name(), def.fight(), def.health(), def.evade(),
                def.damage(), def.horror(), def.keywords(), locationId);
        state.getEnemies().put(id, e);
        events.add(GameEvent.of("SPAWN",
                "⚠️ " + def.name() + " 出現在 " + state.location(locationId).getName() + "!"));
        // Spawning at a location counts as entering it, so an enemy that engages does so now.
        if (engageInvId != null && !e.hasKeyword(Keyword.ALOOF)) {
            engageEnemy(e, requireInvestigator(engageInvId), events);
        }
        return id;
    }

    private void engageEnemy(EnemyCard e, Investigator inv, List<GameEvent> events) {
        String previous = e.getEngagedWith();
        if (inv.getId().equals(previous)) {
            return;
        }
        if (previous != null) {
            Investigator was = state.investigator(previous);
            if (was != null) {
                was.disengage(e.getId());
            }
        }
        e.setEngagedWith(inv.getId());
        inv.engage(e.getId());
        events.add(GameEvent.of("ENGAGE", e.getName() + " 與 " + inv.getName() + " 交戰。"));
    }

    /** Any unengaged, non-aloof enemy at this investigator's location engages them. */
    private void engageEnemiesAt(Investigator inv, List<GameEvent> events) {
        for (EnemyCard e : state.enemiesAt(inv.getLocationId())) {
            if (!e.isEngaged() && !e.isExhausted() && !e.hasKeyword(Keyword.ALOOF)) {
                engageEnemy(e, inv, events);
            }
        }
    }

    /**
     * Run the attack of opportunity for an action that provokes one.
     *
     * @return true if the action must be abandoned (the investigator was eliminated, or
     *         the game ended)
     */
    private boolean provoked(Investigator inv, IntentAction action, String reason, List<GameEvent> events) {
        if (!PROVOKES_ATTACK_OF_OPPORTUNITY.contains(action)) {
            return false;
        }
        List<EnemyCard> attackers = inv.getEngagedEnemyIds().stream()
                .map(state::enemy)
                .filter(e -> e != null && e.isReady())
                .toList();
        if (attackers.isEmpty()) {
            return false;
        }
        events.add(GameEvent.of("AOO", "↩︎ 趁隙攻擊(" + reason + "):"));
        for (EnemyCard e : attackers) {
            enemyHit(inv, e, events); // an attack of opportunity does not exhaust the enemy
            if (state.isGameOver() || inv.isEliminated()) {
                return true;
            }
        }
        return false;
    }

    private void enemyHit(Investigator inv, EnemyCard e, List<GameEvent> events) {
        inv.takeDamage(e.getDamage());
        inv.takeHorror(e.getHorror());
        events.add(GameEvent.of("ENEMY_ATTACK",
                "💥 " + e.getName() + " 攻擊:" + e.getDamage() + " 傷害、" + e.getHorror() + " 恐懼。"));
        checkElimination(inv, events);
    }

    /** Defeat by damage or horror removes the investigator — it does not end the game. */
    private void checkElimination(Investigator inv, List<GameEvent> events) {
        if (state.isGameOver() || inv.isEliminated() || !inv.isDefeatedByTrauma()) {
            return;
        }
        Investigator.Elimination cause = inv.traumaCause();
        events.add(GameEvent.of("DEFEATED", cause == Investigator.Elimination.DAMAGE
                ? inv.getName() + " 傷重被擊敗(生命歸零),退出遊戲。"
                : inv.getName() + " 精神崩潰(理智歸零),退出遊戲。"));
        eliminate(inv, cause, events);
    }

    /**
     * Remove an investigator from play (defeated or resigned). Their enemies are released
     * and their clues are lost. The game only ends once every investigator is out.
     */
    private void eliminate(Investigator inv, Investigator.Elimination cause, List<GameEvent> events) {
        if (inv.isEliminated()) {
            return;
        }
        boolean wasActive = inv.getId().equals(state.getActiveInvestigatorId());
        inv.eliminate(cause);
        inv.spendClues(inv.getCluesHeld());
        for (String enemyId : new ArrayList<>(inv.getEngagedEnemyIds())) {
            EnemyCard e = state.enemy(enemyId);
            if (e != null) {
                e.setEngagedWith(null); // the enemy stays at the location, unengaged
            }
            inv.disengage(enemyId);
        }

        if (state.allEliminated()) {
            state.endGame(false, "所有調查員都已退場 —— 未達成任何結局。");
            events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
            return;
        }
        // Losing the active investigator mid-turn passes play on.
        if (wasActive && state.getPhase() == Phase.INVESTIGATION) {
            advanceTurn(events);
        }
    }

    private void defeatEnemy(EnemyCard e, List<GameEvent> events) {
        events.add(GameEvent.of("DEFEAT", "☠️ " + e.getName() + " 被擊敗!"));
        for (Investigator inv : state.orderedInvestigators()) {
            inv.disengage(e.getId());
        }
        state.getEnemies().remove(e.getId());
    }

    /**
     * The enemy a Fight/Evade targets: it must be at your location, and an aloof enemy
     * that is not engaged with you cannot be attacked or evaded at all.
     */
    private EnemyCard requireAttackableEnemy(Investigator inv, String enemyId, String verb) {
        EnemyCard e = null;
        if (enemyId != null) {
            e = state.enemy(enemyId);
        } else {
            for (String id : inv.getEngagedEnemyIds()) {
                EnemyCard candidate = state.enemy(id);
                if (candidate != null) {
                    e = candidate;
                    break;
                }
            }
            if (e == null) {
                e = state.enemiesAt(inv.getLocationId()).stream().findFirst().orElse(null);
            }
        }
        if (e == null || !e.getLocationId().equals(inv.getLocationId())) {
            throw new IllegalArgumentException("No enemy to " + verb + " here");
        }
        if (e.hasKeyword(Keyword.ALOOF) && !inv.getId().equals(e.getEngagedWith())) {
            throw new IllegalArgumentException("冷漠敵人未與你交戰時不能" + verb);
        }
        return e;
    }

    // ------------------------------------------------------------------
    // Pathfinding over revealed locations (docs/05 §5 Hunter)
    // ------------------------------------------------------------------

    /** BFS first step from {@code from} toward {@code to} over revealed locations. */
    private String nextStepToward(String from, String to) {
        if (from.equals(to)) {
            return null;
        }
        Queue<List<String>> queue = new ArrayDeque<>();
        queue.add(List.of(from));
        Set<String> seen = new HashSet<>();
        seen.add(from);
        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String last = path.get(path.size() - 1);
            for (String n : state.location(last).getConnections()) {
                if (seen.contains(n)) {
                    continue;
                }
                LocationCard nl = state.location(n);
                if (nl == null || !nl.isRevealed()) {
                    continue;
                }
                List<String> next = new ArrayList<>(path);
                next.add(n);
                if (n.equals(to)) {
                    return next.get(1);
                }
                seen.add(n);
                queue.add(next);
            }
        }
        return null;
    }

    private int bfsDistance(String from, String to) {
        if (from.equals(to)) {
            return 0;
        }
        Queue<String> queue = new ArrayDeque<>();
        Map<String, Integer> dist = new java.util.HashMap<>();
        queue.add(from);
        dist.put(from, 0);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String n : state.location(cur).getConnections()) {
                LocationCard nl = state.location(n);
                if (nl == null || !nl.isRevealed() || dist.containsKey(n)) {
                    continue;
                }
                dist.put(n, dist.get(cur) + 1);
                if (n.equals(to)) {
                    return dist.get(n);
                }
                queue.add(n);
            }
        }
        return -1;
    }

    private Investigator nearestInvestigator(String fromLocationId) {
        Investigator best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Investigator inv : state.investigatorsInPlay()) {
            int d = bfsDistance(fromLocationId, inv.getLocationId());
            if (d >= 0 && d < bestDist) { // ties resolved by player order (lead)
                bestDist = d;
                best = inv;
            }
        }
        return best;
    }

    private Investigator investigatorAt(String locationId) {
        return state.investigatorsInPlay().stream()
                .filter(inv -> inv.getLocationId().equals(locationId))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------
    // Per-client filtered view (protocol.md)
    // ------------------------------------------------------------------

    /**
     * Build the {@code GameStateView} for one investigator: their own full hand, only
     * hand counts for others, all public board state, and the chaos-bag summary. The
     * encounter-deck order and the player decks' order are never included.
     */
    public GameStateView viewFor(String investigatorId) {
        Investigator me = requireInvestigator(investigatorId);

        SelfView you = new SelfView(
                me.getId(), me.getSkills(), me.getHealth(), me.getDamage(), me.getSanity(), me.getHorror(),
                me.getResources(), me.getCluesHeld(), me.getActionsRemaining(), me.getLocationId(),
                me.handView(), me.inPlayView(), me.getDeck().size(), me.getDiscardPile().size(),
                List.copyOf(me.getEngagedEnemyIds()), me.isTurnTaken(), nameOf(me.getElimination()));

        List<OtherInvestigatorView> others = new ArrayList<>();
        for (Investigator inv : state.orderedInvestigators()) {
            if (!inv.getId().equals(investigatorId)) {
                others.add(new OtherInvestigatorView(
                        inv.getId(), inv.getLocationId(), inv.getDamage(), inv.getHorror(),
                        inv.getHand().size(), inv.inPlayView(), inv.isTurnTaken(),
                        nameOf(inv.getElimination())));
            }
        }

        List<LocationView> locations = new ArrayList<>();
        for (LocationCard loc : state.getLocations().values()) {
            locations.add(new LocationView(
                    loc.getId(), loc.getName(), loc.isRevealed(), loc.getShroud(), loc.getClues(),
                    loc.getConnections(), state.enemyIdsAt(loc.getId()), loc.isVictory()));
        }

        List<EnemyView> enemies = new ArrayList<>();
        for (EnemyCard e : state.getEnemies().values()) {
            enemies.add(new EnemyView(
                    e.getId(), e.getName(), e.getFight(), e.getHealth(), e.getDamageOn(), e.getEvade(),
                    e.getKeywords(), e.getEngagedWith(), e.isExhausted(), e.getLocationId()));
        }

        Act act = state.getAct();
        Agenda agenda = state.getAgenda();
        return new GameStateView(
                state.getRound(),
                state.getPhase().name(),
                state.getActiveInvestigatorId(),
                you,
                others,
                locations,
                enemies,
                new ActView(act.getName(), act.getCluesSpent(), act.getThreshold()),
                new AgendaView(agenda.getName(), state.totalDoomInPlay(), agenda.getThreshold()),
                state.getChaosBag().summary());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String nameOf(Investigator.Elimination elimination) {
        return elimination == null ? null : elimination.name();
    }

    private String describeResult(SkillTest.Result r) {
        String tokenDesc;
        if (r.autofail()) {
            tokenDesc = "⊗ 自動失敗(技能值歸 0)";
        } else if (r.elderSign()) {
            tokenDesc = "⭐ 古老印記(+1)";
        } else if (r.token() instanceof ChaosToken.Symbol s) {
            tokenDesc = s.symbol() + "(" + (r.tokenModifier() >= 0 ? "+" : "") + r.tokenModifier() + ")";
        } else {
            tokenDesc = (r.tokenModifier() >= 0 ? "+" : "") + r.tokenModifier();
        }
        return "抽到 " + tokenDesc + ";技能 " + r.modifiedSkillValue()
                + (r.success() ? " ≥ " : " < ") + r.difficulty()
                + (r.success() ? " → 成功" : " → 失敗");
    }

    /** An action requires: the investigation phase, the turn, and an action left. */
    private void requireActionTurn(Investigator inv) {
        claimTurn(inv);
        if (inv.getActionsRemaining() <= 0) {
            throw new IllegalArgumentException("No actions remaining");
        }
    }

    /**
     * Turn order is the team's to choose (docs/05 §4.1): the engine tracks only who has
     * not yet acted this round, and any of them may take the turn — as long as nobody
     * else is part-way through theirs. Once an investigator has spent an action they hold
     * the turn until they end it.
     */
    private void claimTurn(Investigator inv) {
        if (state.getPhase() != Phase.INVESTIGATION) {
            throw new IllegalArgumentException("Not the investigation phase");
        }
        if (inv.isEliminated()) {
            throw new IllegalArgumentException("You are out of the game");
        }
        if (inv.isTurnTaken()) {
            throw new IllegalArgumentException("You have already taken your turn this round");
        }
        if (inv.getId().equals(state.getActiveInvestigatorId())) {
            return;
        }
        Investigator active = state.investigator(state.getActiveInvestigatorId());
        boolean someoneIsMidTurn = active != null
                && active.isInPlay()
                && !active.isTurnTaken()
                && active.getActionsRemaining() < ACTIONS_PER_TURN;
        if (someoneIsMidTurn) {
            throw new IllegalArgumentException(
                    active.getName() + " is part-way through their turn — wait for them to finish");
        }
        state.setActiveInvestigatorId(inv.getId()); // volunteer to take the next turn
    }

    private Investigator requireInvestigator(String id) {
        Investigator inv = state.investigator(id);
        if (inv == null) {
            throw new IllegalArgumentException("Unknown investigator: " + id);
        }
        return inv;
    }

    private PendingCommit requirePending() {
        if (pendingCommit == null) {
            throw new IllegalStateException("No pending skill-test commit");
        }
        return pendingCommit;
    }

    private static String str(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    private static List<String> strList(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        if (!(v instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }
}
