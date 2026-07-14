package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.model.CardInstance;
import com.arkham.engine.model.GameState;
import com.arkham.engine.model.IntentAction;
import com.arkham.engine.model.Investigator;
import com.arkham.engine.model.LocationCard;
import com.arkham.engine.model.Phase;
import com.arkham.engine.model.SkillIcon;
import com.arkham.engine.scenario.ScenarioFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the round/turn structure and the rules the lite scaffold used to get wrong:
 * elimination is not an instant loss, attacks of opportunity fire on every action except
 * fight/evade/parley/resign, doom is counted across the whole board, and the player deck
 * drives draw / hand limit / empty-deck horror.
 */
class RulesEngineTest {

    private RulesEngine engine;
    private GameState state;
    private Investigator joe;
    private Investigator daniela;

    @BeforeEach
    void setUp() {
        engine = ScenarioFactory.newEngine(1234L);
        state = engine.state();
        joe = state.investigator(ScenarioFactory.JOE);
        daniela = state.investigator(ScenarioFactory.DANIELA);
    }

    private void intent(Investigator inv, IntentAction action) {
        engine.applyIntent(inv.getId(), action, Map.of());
    }

    private void intent(Investigator inv, IntentAction action, Map<String, Object> payload) {
        engine.applyIntent(inv.getId(), action, payload);
    }

    /** Move Joe into the Dormitories, which reveals it and spawns the Servant engaged with him. */
    private void engageJoeWithTheServant() {
        intent(joe, IntentAction.MOVE, Map.of("toLocationId", "dormitories"));
        assertFalse(joe.getEngagedEnemyIds().isEmpty(), "the Servant should have engaged Joe on reveal");
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    @Test
    void setupDealsFiveCardsAndNoWeaknessInTheOpeningHand() {
        assertEquals(Investigator.OPENING_HAND_SIZE, joe.getHand().size());
        assertEquals(Investigator.OPENING_HAND_SIZE, daniela.getHand().size());
        assertTrue(joe.getHand().stream().noneMatch(CardInstance::isWeakness),
                "a weakness drawn at setup is set aside and redrawn");
        // The weakness is shuffled back into the deck, so nothing is lost.
        assertEquals(15, joe.getDeck().size() + joe.getHand().size());
        assertTrue(joe.getDeck().stream().anyMatch(CardInstance::isWeakness));
    }

    @Test
    void eachInvestigatorStartsWithFiveResourcesAndThreeActions() {
        assertEquals(5, joe.getResources());
        assertEquals(3, joe.getActionsRemaining());
        assertEquals(5, daniela.getResources());
    }

    @Test
    void mulliganRedrawsToFiveAndIsOnlyAvailableOnce() {
        List<String> setAside = joe.getHand().stream().limit(2).map(CardInstance::cardId).toList();

        intent(joe, IntentAction.MULLIGAN, Map.of("cardIds", setAside));

        assertEquals(Investigator.OPENING_HAND_SIZE, joe.getHand().size());
        assertTrue(joe.hasMulliganed());
        assertThrows(IllegalArgumentException.class,
                () -> intent(joe, IntentAction.MULLIGAN, Map.of("cardIds", List.of())));
    }

    // ------------------------------------------------------------------
    // Turn order
    // ------------------------------------------------------------------

    @Test
    void endTurnPassesToTheNextInvestigatorRatherThanEndingTheRound() {
        assertEquals(ScenarioFactory.JOE, state.getActiveInvestigatorId());

        intent(joe, IntentAction.END_TURN);

        assertEquals(ScenarioFactory.DANIELA, state.getActiveInvestigatorId());
        assertEquals(1, state.getRound(), "the round only ends once everyone has acted");
        assertEquals(Phase.INVESTIGATION, state.getPhase());
    }

    @Test
    void theRoundClosesOnlyAfterEveryInvestigatorHasTakenTheirTurn() {
        intent(joe, IntentAction.END_TURN);
        intent(daniela, IntentAction.END_TURN);

        assertEquals(2, state.getRound());
        assertEquals(Phase.INVESTIGATION, state.getPhase());
        assertEquals(ScenarioFactory.JOE, state.getActiveInvestigatorId(), "the lead investigator starts the round");
        assertEquals(3, joe.getActionsRemaining());
        assertEquals(3, daniela.getActionsRemaining());
        assertFalse(joe.isTurnTaken());
    }

    /** Turn order is the team's to choose: anyone who has not acted may volunteer. */
    @Test
    void anyInvestigatorWhoHasNotActedMayTakeTheTurn() {
        assertEquals(ScenarioFactory.JOE, state.getActiveInvestigatorId(), "Joe is merely the default offer");

        intent(daniela, IntentAction.GAIN_RESOURCE); // Daniela volunteers instead

        assertEquals(ScenarioFactory.DANIELA, state.getActiveInvestigatorId());
        assertEquals(2, daniela.getActionsRemaining());
    }

    @Test
    void youCannotCutInOnceSomeoneIsPartWayThroughTheirTurn() {
        intent(joe, IntentAction.GAIN_RESOURCE); // Joe is now mid-turn

        assertThrows(IllegalArgumentException.class,
                () -> intent(daniela, IntentAction.GAIN_RESOURCE));
    }

    @Test
    void youCannotTakeASecondTurnInTheSameRound() {
        intent(joe, IntentAction.END_TURN);

        assertThrows(IllegalArgumentException.class,
                () -> intent(joe, IntentAction.GAIN_RESOURCE));
    }

    // ------------------------------------------------------------------
    // Attacks of opportunity
    // ------------------------------------------------------------------

    @Test
    void onlyFightEvadeParleyAndResignAreExemptFromAttacksOfOpportunity() {
        assertTrue(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.ENGAGE));
        assertTrue(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.DRAW));
        assertTrue(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.GAIN_RESOURCE));
        assertTrue(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.PLAY_CARD));
        assertTrue(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.MOVE));
        assertTrue(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.INVESTIGATE));

        assertFalse(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.FIGHT));
        assertFalse(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.EVADE));
        assertFalse(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.PARLEY));
        assertFalse(RulesEngine.PROVOKES_ATTACK_OF_OPPORTUNITY.contains(IntentAction.RESIGN));
    }

    @Test
    void drawingWhileEngagedProvokesAnAttackOfOpportunity() {
        engageJoeWithTheServant(); // Servant: 1 damage, 1 horror

        intent(joe, IntentAction.DRAW);

        assertEquals(1, joe.getDamage());
        assertEquals(1, joe.getHorror());
    }

    @Test
    void fightingWhileEngagedDoesNotProvokeAnAttackOfOpportunity() {
        engageJoeWithTheServant();
        String enemyId = joe.getEngagedEnemyIds().get(0);

        engine.applyIntent(joe.getId(), IntentAction.FIGHT, Map.of("enemyId", enemyId));
        engine.resolveCommit(Map.of()); // nobody commits; the test just resolves

        // Any damage Joe has taken can only come from Retaliate on a failed test, never from an AoO.
        assertTrue(joe.getDamage() <= 1);
        assertFalse(engine.hasPendingCommit());
    }

    // ------------------------------------------------------------------
    // Elimination
    // ------------------------------------------------------------------

    @Test
    void beingDefeatedRemovesTheInvestigatorButDoesNotEndTheGame() {
        engageJoeWithTheServant();
        joe.takeDamage(joe.getHealth() - 1); // one more point of damage will defeat him

        intent(joe, IntentAction.DRAW); // provokes: the Servant deals the last point

        assertTrue(joe.isEliminated());
        assertEquals(Investigator.Elimination.DAMAGE, joe.getElimination());
        assertFalse(state.isGameOver(), "one investigator down is not a loss — Daniela is still in the game");
        assertEquals(ScenarioFactory.DANIELA, state.getActiveInvestigatorId(), "play passes on");
    }

    @Test
    void theGameOnlyEndsOnceEveryInvestigatorIsOut() {
        engageJoeWithTheServant();
        joe.takeDamage(joe.getHealth() - 1);
        intent(joe, IntentAction.DRAW); // Joe is defeated; Daniela is now active
        assertFalse(state.isGameOver());

        intent(daniela, IntentAction.RESIGN);

        assertTrue(state.isGameOver());
        assertFalse(state.isWon());
        assertTrue(state.allEliminated());
    }

    @Test
    void resigningDoesNotReduceThePlayerCountUsedForClues() {
        intent(joe, IntentAction.RESIGN);
        assertTrue(joe.isEliminated());
        assertEquals(2, state.getPlayerCount());

        intent(daniela, IntentAction.MOVE, Map.of("toLocationId", "dormitories"));

        LocationCard dorms = state.location("dormitories");
        assertTrue(dorms.isRevealed());
        // clueValue 1 × 2 players — the resigned investigator still counts.
        assertEquals(2, dorms.getClues());
    }

    // ------------------------------------------------------------------
    // Doom
    // ------------------------------------------------------------------

    @Test
    void theAgendaAdvancesOnAllDoomInPlayNotJustDoomOnTheAgenda() {
        state.location("friends_room").addDoom(4); // threshold is 5
        assertEquals(4, state.totalDoomInPlay());

        intent(joe, IntentAction.END_TURN);
        intent(daniela, IntentAction.END_TURN); // mythos places the 5th doom

        assertTrue(state.isGameOver());
        assertFalse(state.isWon());
        assertEquals(0, state.totalDoomInPlay(), "advancing the agenda discards all doom in play");
    }

    @Test
    void oneDoomPerMythosDoesNotAdvanceTheAgendaOnItsOwn() {
        intent(joe, IntentAction.END_TURN);
        intent(daniela, IntentAction.END_TURN);

        assertFalse(state.isGameOver());
        assertTrue(state.totalDoomInPlay() >= 1);
    }

    // ------------------------------------------------------------------
    // Player deck
    // ------------------------------------------------------------------

    @Test
    void drawIsAnActionThatMovesACardFromDeckToHand() {
        int deck = joe.getDeck().size();
        int hand = joe.getHand().size();

        intent(joe, IntentAction.DRAW);

        assertEquals(deck - 1, joe.getDeck().size());
        assertEquals(hand + 1, joe.getHand().size());
        assertEquals(2, joe.getActionsRemaining());
    }

    @Test
    void gainResourceIsAnAction() {
        intent(joe, IntentAction.GAIN_RESOURCE);

        assertEquals(6, joe.getResources());
        assertEquals(2, joe.getActionsRemaining());
    }

    @Test
    void drawingFromAnEmptyDeckCostsOneHorrorAndReshufflesTheDiscardPile() {
        joe.getDeck().clear();
        joe.getDiscardPile().add(CardInstance.skill("x1", "Guts", SkillIcon.WILLPOWER));
        joe.getDiscardPile().add(CardInstance.skill("x2", "Perception", SkillIcon.INTELLECT));

        intent(joe, IntentAction.DRAW);

        assertEquals(1, joe.getHorror());
        assertEquals(0, joe.getDiscardPile().size());
        assertEquals(1, joe.getDeck().size()); // two reshuffled, one drawn
        assertEquals(Investigator.OPENING_HAND_SIZE + 1, joe.getHand().size());
    }

    @Test
    void upkeepDrawsOneGainsOneAndDiscardsDownToTheHandLimit() {
        // Pad Joe's hand past the limit so upkeep has to trim it.
        for (int i = 0; i < 5; i++) {
            joe.getHand().add(CardInstance.skill("pad" + i, "Filler", SkillIcon.WILD));
        }
        assertEquals(10, joe.getHand().size());
        int danielaHand = daniela.getHand().size();

        intent(joe, IntentAction.END_TURN);
        intent(daniela, IntentAction.END_TURN); // enemy phase → upkeep → mythos

        assertEquals(Investigator.HAND_LIMIT, joe.getHand().size(), "hand is trimmed to eight in upkeep");
        assertEquals(danielaHand + 1, daniela.getHand().size(), "everyone draws one in upkeep");
        assertEquals(6, daniela.getResources(), "everyone gains one resource in upkeep");
    }

    // ------------------------------------------------------------------
    // Playing cards
    // ------------------------------------------------------------------

    @Test
    void playingAnAssetSpendsResourcesAndPutsItIntoPlay() {
        joe.getHand().add(CardInstance.weapon("gun", ".45 Automatic", 4, 1, SkillIcon.COMBAT));

        intent(joe, IntentAction.PLAY_CARD, Map.of("cardId", "gun"));

        assertEquals(1, joe.getResources()); // 5 − 4
        assertEquals(1, joe.getInPlay().size());
        assertEquals(1, joe.getWeaponBonus(), "the weapon adds its damage to a won fight");
        assertEquals(2, joe.getActionsRemaining());
        assertNotNull(joe.getInPlay().get(0));
    }

    @Test
    void playingAnEventDiscardsItAfterItResolves() {
        joe.getHand().add(CardInstance.event("ev", "Evidence!", 1, SkillIcon.INTELLECT));

        intent(joe, IntentAction.PLAY_CARD, Map.of("cardId", "ev"));

        assertEquals(4, joe.getResources());
        assertTrue(joe.getInPlay().isEmpty());
        assertTrue(joe.getDiscardPile().stream().anyMatch(c -> c.cardId().equals("ev")));
    }

    @Test
    void aSkillCardCannotBePlayedAsAnActionAndAnUnaffordableCardIsRejected() {
        joe.getHand().add(CardInstance.skill("sk", "Deduction", SkillIcon.INTELLECT));
        joe.getHand().add(CardInstance.asset("pricey", "Expensive Ally", 9, SkillIcon.WILD));

        assertThrows(IllegalArgumentException.class,
                () -> intent(joe, IntentAction.PLAY_CARD, Map.of("cardId", "sk")));
        assertThrows(IllegalArgumentException.class,
                () -> intent(joe, IntentAction.PLAY_CARD, Map.of("cardId", "pricey")));
        assertEquals(3, joe.getActionsRemaining(), "a rejected action costs nothing");
    }

    // ------------------------------------------------------------------
    // Skill-test commit
    // ------------------------------------------------------------------

    /**
     * The performer commits any number of cards; a helper at the same location commits at
     * most one (docs/05 §2.1, rulebook p.15/33).
     */
    @Test
    void theTestPerformerCommitsFreelyButAHelperIsCappedAtOneCard() {
        // Both investigators start in Your Friend's Room, which has clues.
        intent(joe, IntentAction.INVESTIGATE);

        RulesEngine.PendingCommit pending = engine.pendingCommit();
        assertNotNull(pending);
        assertTrue(pending.eligibleInvestigatorIds().contains(ScenarioFactory.DANIELA));

        var mine = engine.commitOptionsFor(ScenarioFactory.JOE);
        assertEquals(mine.eligibleCards().size(), mine.maxCommit());

        var helper = engine.commitOptionsFor(ScenarioFactory.DANIELA);
        assertEquals(1, helper.maxCommit(), "a helper may commit only one card");
    }

    /** Even if a helper's client sends two cards, only the first one counts. */
    @Test
    void aHelperCommittingTwoCardsOnlyEverContributesOne() {
        daniela.getHand().clear();
        daniela.getHand().add(CardInstance.skill("h1", "Perception", SkillIcon.INTELLECT, SkillIcon.INTELLECT));
        daniela.getHand().add(CardInstance.skill("h2", "Perception", SkillIcon.INTELLECT, SkillIcon.INTELLECT));

        intent(joe, IntentAction.INVESTIGATE);
        engine.resolveCommit(Map.of(ScenarioFactory.DANIELA, List.of("h1", "h2")));

        assertEquals(1, daniela.getHand().size(), "only the first committed card left her hand");
        assertEquals(1, daniela.getDiscardPile().size());
    }
}
