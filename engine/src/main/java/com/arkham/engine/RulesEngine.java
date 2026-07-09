package com.arkham.engine;

import com.arkham.engine.event.GameEvent;
import com.arkham.engine.model.Act;
import com.arkham.engine.model.Agenda;
import com.arkham.engine.model.CardInstance;
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
 * <p>Actions that trigger a skill test (INVESTIGATE / FIGHT / EVADE) don't resolve
 * immediately: {@link #applyIntent} opens a <em>commit barrier</em> (docs/05 §2.1) —
 * exposed via {@link #hasPendingCommit()} / {@link #pendingCommit()} /
 * {@link #commitOptionsFor(String)} — and the server drives it, collecting each
 * eligible committer's cards before calling {@link #resolveCommit(Map)} to draw the
 * token and apply the result. All other actions resolve synchronously.
 */
public final class RulesEngine {

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
     * Build the COMMIT_CARDS options for one eligible committer: their matching/wild
     * hand cards and their commit limit (performer: effectively unlimited; a
     * same-location ally: 1 — docs/05 §2.1).
     */
    public CommitCardsOptions commitOptionsFor(String investigatorId) {
        PendingCommit pc = requirePending();
        Investigator inv = requireInvestigator(investigatorId);
        boolean isPerformer = investigatorId.equals(pc.performerId());
        List<com.arkham.engine.model.HandCard> eligible = inv.getHand().stream()
                .filter(c -> c.eligibleFor(pc.skill()))
                .map(CardInstance::toHandCard)
                .toList();
        int maxCommit = isPerformer ? 99 : 1;
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
            case MOVE -> move(inv, str(p, "toLocationId"), events);
            case INVESTIGATE -> investigate(inv, events);
            case FIGHT -> fight(inv, str(p, "enemyId"), events);
            case EVADE -> evade(inv, str(p, "enemyId"), events);
            case ENGAGE -> engage(inv, str(p, "enemyId"), events);
            case END_TURN -> endTurn(events);
            case ADVANCE_ACT -> advanceAct(inv, events);
            case PLAY_CARD, ACTIVATE ->
                    throw new IllegalArgumentException(action + " is not implemented in this scaffold");
        }
        return events;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void move(Investigator inv, String toLocationId, List<GameEvent> events) {
        requireInvestigationTurn(inv);
        LocationCard here = state.location(inv.getLocationId());
        if (toLocationId == null || !here.connectsTo(toLocationId)) {
            throw new IllegalArgumentException("Destination is not connected to your current location");
        }
        // Moving provokes attacks of opportunity from engaged ready enemies (docs/05 §4).
        attackOfOpportunity(inv, "移動", events);
        if (state.isGameOver()) {
            return;
        }
        inv.setLocationId(toLocationId);
        LocationCard dest = state.location(toLocationId);
        if (!dest.isRevealed()) {
            dest.setRevealed(true);
            dest.setClues(dest.getClueValue() * state.getInvestigators().size()); // clueValue × players
            events.add(GameEvent.of("MOVE",
                    "進入並揭示 " + dest.getName() + ",放上 " + dest.getClues() + " 線索。"));
            if (dest.getSpawnDefKey() != null) {
                spawnEnemy(dest.getSpawnDefKey(), toLocationId, inv.getId(), events); // engaged on reveal
            }
        } else {
            events.add(GameEvent.of("MOVE", inv.getName() + " 移動到 " + dest.getName() + "。"));
        }
        // Same-location unengaged enemies engage the mover.
        for (EnemyCard e : state.enemiesAt(toLocationId)) {
            if (!e.isEngaged()) {
                e.setEngagedWith(inv.getId());
                inv.engage(e.getId());
                events.add(GameEvent.of("ENGAGE", e.getName() + " 與 " + inv.getName() + " 交戰。"));
            }
        }
        inv.spendAction();
    }

    private void investigate(Investigator inv, List<GameEvent> events) {
        requireInvestigationTurn(inv);
        LocationCard loc = state.location(inv.getLocationId());
        if (loc.getClues() <= 0) {
            throw new IllegalArgumentException("This location has no clues");
        }
        attackOfOpportunity(inv, "調查", events); // Investigate is not exempt (docs/05 §4)
        if (state.isGameOver()) {
            return;
        }
        openSkillTest(inv, SkillType.INTELLECT, loc.getShroud(), IntentAction.INVESTIGATE, loc.getId(), events);
    }

    private void fight(Investigator inv, String enemyId, List<GameEvent> events) {
        requireInvestigationTurn(inv);
        EnemyCard e = resolveEnemyTarget(inv, enemyId);
        if (e == null) {
            throw new IllegalArgumentException("No enemy to fight here");
        }
        // Fight does NOT provoke attacks of opportunity (docs/05 §4).
        openSkillTest(inv, SkillType.COMBAT, e.getFight(), IntentAction.FIGHT, e.getId(), events);
    }

    private void evade(Investigator inv, String enemyId, List<GameEvent> events) {
        requireInvestigationTurn(inv);
        EnemyCard e = resolveEnemyTarget(inv, enemyId);
        if (e == null) {
            throw new IllegalArgumentException("No enemy to evade");
        }
        // Evade does NOT provoke attacks of opportunity (docs/05 §4).
        openSkillTest(inv, SkillType.AGILITY, e.getEvade(), IntentAction.EVADE, e.getId(), events);
    }

    private void engage(Investigator inv, String enemyId, List<GameEvent> events) {
        requireInvestigationTurn(inv);
        EnemyCard e;
        if (enemyId != null) {
            e = state.enemy(enemyId);
        } else {
            e = state.enemiesAt(inv.getLocationId()).stream()
                    .filter(en -> !inv.getEngagedEnemyIds().contains(en.getId()))
                    .findFirst().orElse(null);
        }
        if (e == null || !e.getLocationId().equals(inv.getLocationId())) {
            throw new IllegalArgumentException("No enemy to engage here");
        }
        e.setEngagedWith(inv.getId());
        inv.engage(e.getId());
        events.add(GameEvent.of("ENGAGE", inv.getName() + " 與 " + e.getName() + " 交戰。"));
        inv.spendAction();
    }

    private void advanceAct(Investigator inv, List<GameEvent> events) {
        if (state.getPhase() != Phase.INVESTIGATION) {
            throw new IllegalArgumentException("Can only advance the act during the investigation phase");
        }
        int need = state.getAct().getThreshold();
        int total = state.orderedInvestigators().stream().mapToInt(Investigator::getCluesHeld).sum();
        if (total < need) {
            throw new IllegalArgumentException("Not enough clues to advance the act (" + total + "/" + need + ")");
        }
        int remaining = need;
        for (Investigator i : state.orderedInvestigators()) {
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

    // ------------------------------------------------------------------
    // Skill-test barrier open / resolve
    // ------------------------------------------------------------------

    private void openSkillTest(Investigator performer, SkillType skill, int difficulty,
                               IntentAction actionKind, String targetId, List<GameEvent> events) {
        int base = performer.baseSkill(skill);
        List<String> eligible = new ArrayList<>();
        eligible.add(performer.getId());
        for (Investigator other : state.orderedInvestigators()) {
            if (!other.getId().equals(performer.getId())
                    && other.getLocationId().equals(performer.getLocationId())) {
                eligible.add(other.getId()); // same-location allies may commit ≤1 (docs/05 §2.1)
            }
        }
        pendingCommit = new PendingCommit(skill, base, difficulty, performer.getId(), actionKind, targetId, eligible);
        events.add(GameEvent.of("SKILL_TEST",
                "技能檢定開始:" + skill + " 基礎 " + base + " / 難度 " + difficulty));
    }

    /**
     * Finish the pending skill test: sum committed matching/wild icons (enforcing the
     * ≤1 ally rule), draw the token via the seeded RNG, apply the result, discard the
     * committed cards and spend the performer's action.
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
            if (inv == null || !inv.getLocationId().equals(performer.getLocationId())) {
                continue;
            }
            boolean isPerformer = invId.equals(pc.performerId());
            List<String> cardIds = entry.getValue();
            if (!isPerformer && cardIds.size() > 1) {
                cardIds = cardIds.subList(0, 1); // ally limit: at most 1 card
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
        performer.spendAction();
        pendingCommit = null;
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
    // End turn: enemy phase → upkeep → mythos → next investigation
    // ------------------------------------------------------------------

    private void endTurn(List<GameEvent> events) {
        if (state.getPhase() != Phase.INVESTIGATION) {
            throw new IllegalArgumentException("Can only end the turn during the investigation phase");
        }

        // --- Enemy phase ---
        state.setPhase(Phase.ENEMY);
        events.add(GameEvent.of("PHASE", "—— 敵人階段 ——"));
        moveHunters(events);
        enemyAttacks(events);
        if (state.isGameOver()) {
            return;
        }

        // --- Upkeep phase ---
        state.setPhase(Phase.UPKEEP);
        events.add(GameEvent.of("PHASE", "—— 整備階段 ——"));
        for (EnemyCard e : state.getEnemies().values()) {
            e.setExhausted(false); // ready all
        }
        for (Investigator inv : state.orderedInvestigators()) {
            inv.gainResources(1); // gain 1 resource (drawing a card is omitted in this scaffold)
        }
        // Same-location unengaged enemies engage (docs/05 §1.4c).
        for (EnemyCard e : state.getEnemies().values()) {
            if (!e.isEngaged()) {
                Investigator here = investigatorAt(e.getLocationId());
                if (here != null) {
                    e.setEngagedWith(here.getId());
                    here.engage(e.getId());
                }
            }
        }

        // --- Next round: Mythos (round ≥ 2) then Investigation ---
        state.incrementRound();
        events.add(GameEvent.of("ROUND", "—— 第 " + state.getRound() + " 輪 ——"));
        if (state.getRound() >= 2) {
            mythosPhase(events);
            if (state.isGameOver()) {
                return;
            }
        }
        state.setPhase(Phase.INVESTIGATION);
        for (Investigator inv : state.orderedInvestigators()) {
            inv.setActionsRemaining(3);
        }
        events.add(GameEvent.of("PHASE", "—— 調查階段 ——"));
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
            if (target.getLocationId().equals(e.getLocationId())) {
                e.setEngagedWith(target.getId()); // already sharing a location: engage
                target.engage(e.getId());
                events.add(GameEvent.of("ENGAGE", e.getName() + " 與 " + target.getName() + " 交戰。"));
                continue;
            }
            String step = nextStepToward(e.getLocationId(), target.getLocationId());
            if (step != null) {
                e.setLocationId(step);
                events.add(GameEvent.of("ENEMY_MOVE",
                        "🐾 " + e.getName() + "(獵人)移動到 " + state.location(step).getName() + "。"));
                Investigator here = investigatorAt(step);
                if (here != null) {
                    e.setEngagedWith(here.getId());
                    here.engage(e.getId());
                    events.add(GameEvent.of("ENGAGE", e.getName() + " 與 " + here.getName() + " 交戰。"));
                }
            }
        }
    }

    private void enemyAttacks(List<GameEvent> events) {
        for (EnemyCard e : new ArrayList<>(state.getEnemies().values())) {
            if (e.isReady() && e.isEngaged()) {
                Investigator victim = state.investigator(e.getEngagedWith());
                if (victim != null) {
                    enemyHit(victim, e, events);
                    e.setExhausted(true);
                }
                if (state.isGameOver()) {
                    return;
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
                "🕯️ 密謀累積毀滅:" + agenda.getDoom() + "/" + agenda.getThreshold() + "。"));
        if (agenda.atThreshold()) {
            state.endGame(false, "密謀推進 —— 黑暗吞噬了阿卡姆大學。");
            events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
            return;
        }

        for (Investigator inv : state.orderedInvestigators()) {
            EncounterCard card = state.drawEncounter();
            if (card != null) {
                resolveEncounter(inv, card, events);
            }
            if (state.isGameOver()) {
                return;
            }
        }
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
                checkDefeat(inv, events);
            }
            case DOOM -> {
                state.getAgenda().addDoom(card.amount());
                events.add(GameEvent.of("TREACHERY",
                        "密謀 +" + card.amount() + " 毀滅(" + state.getAgenda().getDoom()
                                + "/" + state.getAgenda().getThreshold() + ")。"));
                if (state.getAgenda().atThreshold()) {
                    state.endGame(false, "密謀推進 —— 黑暗吞噬了阿卡姆大學。");
                    events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
                }
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
    // Enemy helpers
    // ------------------------------------------------------------------

    private String spawnEnemy(String defKey, String locationId, String engageInvId, List<GameEvent> events) {
        EnemyDef def = state.getEnemyDefs().get(defKey);
        if (def == null) {
            throw new IllegalArgumentException("Unknown enemy definition: " + defKey);
        }
        String id = state.nextEnemyId();
        EnemyCard e = new EnemyCard(id, def.defKey(), def.name(), def.fight(), def.health(), def.evade(),
                def.damage(), def.horror(), def.keywords(), locationId);
        if (engageInvId != null) {
            e.setEngagedWith(engageInvId);
            state.investigator(engageInvId).engage(id);
        }
        state.getEnemies().put(id, e);
        events.add(GameEvent.of("SPAWN",
                "⚠️ " + def.name() + " 出現在 " + state.location(locationId).getName()
                        + (engageInvId != null ? ",並與你交戰!" : "!")));
        return id;
    }

    /** Attacks of opportunity: each engaged ready enemy attacks once (docs/05 §4). */
    private void attackOfOpportunity(Investigator inv, String reason, List<GameEvent> events) {
        List<EnemyCard> attackers = inv.getEngagedEnemyIds().stream()
                .map(state::enemy)
                .filter(e -> e != null && e.isReady())
                .toList();
        if (!attackers.isEmpty()) {
            events.add(GameEvent.of("AOO", "↩︎ 趁隙攻擊(" + reason + "):"));
            for (EnemyCard e : attackers) {
                enemyHit(inv, e, events); // AoO does not exhaust the enemy
                if (state.isGameOver()) {
                    return;
                }
            }
        }
    }

    private void enemyHit(Investigator inv, EnemyCard e, List<GameEvent> events) {
        inv.takeDamage(e.getDamage());
        inv.takeHorror(e.getHorror());
        events.add(GameEvent.of("ENEMY_ATTACK",
                "💥 " + e.getName() + " 攻擊:" + e.getDamage() + " 傷害、" + e.getHorror() + " 恐懼。"));
        checkDefeat(inv, events);
    }

    private void checkDefeat(Investigator inv, List<GameEvent> events) {
        if (state.isGameOver()) {
            return;
        }
        if (inv.getDamage() >= inv.getHealth()) {
            state.endGame(false, inv.getName() + " 傷重被擊敗(生命歸零)。");
            events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
        } else if (inv.getHorror() >= inv.getSanity()) {
            state.endGame(false, inv.getName() + " 精神崩潰(理智歸零)。");
            events.add(GameEvent.of("GAME_OVER", state.getOutcomeMessage()));
        }
    }

    private void defeatEnemy(EnemyCard e, List<GameEvent> events) {
        events.add(GameEvent.of("DEFEAT", "☠️ " + e.getName() + " 被擊敗!"));
        for (Investigator inv : state.orderedInvestigators()) {
            inv.disengage(e.getId());
        }
        state.getEnemies().remove(e.getId());
    }

    private EnemyCard resolveEnemyTarget(Investigator inv, String enemyId) {
        if (enemyId != null) {
            EnemyCard e = state.enemy(enemyId);
            if (e != null) {
                return e;
            }
        }
        for (String id : inv.getEngagedEnemyIds()) {
            EnemyCard e = state.enemy(id);
            if (e != null) {
                return e;
            }
        }
        return state.enemiesAt(inv.getLocationId()).stream().findFirst().orElse(null);
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
        for (Investigator inv : state.orderedInvestigators()) {
            int d = bfsDistance(fromLocationId, inv.getLocationId());
            if (d >= 0 && d < bestDist) { // ties resolved by player order (lead)
                bestDist = d;
                best = inv;
            }
        }
        return best;
    }

    private Investigator investigatorAt(String locationId) {
        return state.orderedInvestigators().stream()
                .filter(inv -> inv.getLocationId().equals(locationId))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------
    // Per-client filtered view (protocol.md)
    // ------------------------------------------------------------------

    /**
     * Build the {@code GameStateView} for one investigator: their own full hand, only
     * hand counts for others, all public board state, and the chaos-bag summary. The
     * encounter-deck order is never included.
     */
    public GameStateView viewFor(String investigatorId) {
        Investigator me = requireInvestigator(investigatorId);

        SelfView you = new SelfView(
                me.getId(), me.getSkills(), me.getHealth(), me.getDamage(), me.getSanity(), me.getHorror(),
                me.getResources(), me.getCluesHeld(), me.getActionsRemaining(), me.getLocationId(),
                me.handView(), List.copyOf(me.getEngagedEnemyIds()));

        List<OtherInvestigatorView> others = new ArrayList<>();
        for (Investigator inv : state.orderedInvestigators()) {
            if (!inv.getId().equals(investigatorId)) {
                others.add(new OtherInvestigatorView(
                        inv.getId(), inv.getLocationId(), inv.getDamage(), inv.getHorror(), inv.getHand().size()));
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
                new AgendaView(agenda.getName(), agenda.getDoom(), agenda.getThreshold()),
                state.getChaosBag().summary());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    private void requireInvestigationTurn(Investigator inv) {
        if (state.getPhase() != Phase.INVESTIGATION) {
            throw new IllegalArgumentException("Not the investigation phase");
        }
        if (inv.getActionsRemaining() <= 0) {
            throw new IllegalArgumentException("No actions remaining");
        }
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
}
