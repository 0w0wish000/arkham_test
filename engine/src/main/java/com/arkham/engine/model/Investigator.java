package com.arkham.engine.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative, mutable state for one investigator. The engine mutates this in
 * place; {@link com.arkham.engine.RulesEngine#viewFor} projects it to the filtered
 * {@code SelfView} / {@code OtherInvestigatorView} records for the wire.
 *
 * <p>{@code health}/{@code sanity} are the printed maxima; {@code damage}/{@code horror}
 * are the amounts currently marked. An investigator with damage ≥ health or horror ≥
 * sanity is <em>eliminated</em> (defeated); one who takes the Resign action is
 * eliminated too, but with {@link Elimination#RESIGNED}. Elimination removes them from
 * play — it does <b>not</b> end the game on its own; only every investigator being
 * eliminated does.
 */
public final class Investigator {

    /** The default maximum hand size, enforced in the upkeep phase. */
    public static final int HAND_LIMIT = 8;

    /** Cards drawn at setup, and the size the one-time mulligan redraws back up to. */
    public static final int OPENING_HAND_SIZE = 5;

    /** Why an investigator left the game — drives campaign-mode trauma. */
    public enum Elimination { DAMAGE, HORROR, RESIGNED }

    private final String id;
    private final String name;
    private final Skills skills;
    private final int health;
    private final int sanity;

    private int damage;
    private int horror;
    private int resources;
    private int cluesHeld;
    private int actionsRemaining;
    private String locationId;
    private Elimination elimination; // null while in play
    /** True once this investigator has ended their turn this round (the flipped-down token). */
    private boolean turnTaken;
    /** The opening-hand adjustment is available once per game. */
    private boolean mulliganed;

    private final List<CardInstance> deck = new ArrayList<>();
    private final List<CardInstance> hand = new ArrayList<>();
    private final List<CardInstance> inPlay = new ArrayList<>();
    private final List<CardInstance> discardPile = new ArrayList<>();
    private final List<String> engagedEnemyIds = new ArrayList<>();

    public Investigator(String id, String name, Skills skills, int health, int sanity,
                        int resources, String locationId) {
        this.id = id;
        this.name = name;
        this.skills = skills;
        this.health = health;
        this.sanity = sanity;
        this.resources = resources;
        this.locationId = locationId;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Skills getSkills() { return skills; }
    public int baseSkill(SkillType type) { return skills.of(type); }

    public int getHealth() { return health; }
    public int getSanity() { return sanity; }
    public int getDamage() { return damage; }
    public int getHorror() { return horror; }
    public int getResources() { return resources; }
    public int getCluesHeld() { return cluesHeld; }
    public int getActionsRemaining() { return actionsRemaining; }
    public String getLocationId() { return locationId; }
    public boolean isTurnTaken() { return turnTaken; }
    public boolean hasMulliganed() { return mulliganed; }
    public void setMulliganed(boolean mulliganed) { this.mulliganed = mulliganed; }

    /** Total extra Fight damage from weapons currently in play. */
    public int getWeaponBonus() {
        return inPlay.stream().mapToInt(CardInstance::weaponBonus).sum();
    }

    public List<CardInstance> getDeck() { return deck; }
    public List<CardInstance> getHand() { return hand; }
    public List<CardInstance> getInPlay() { return inPlay; }
    public List<CardInstance> getDiscardPile() { return discardPile; }
    public List<String> getEngagedEnemyIds() { return engagedEnemyIds; }

    public void setLocationId(String locationId) { this.locationId = locationId; }
    public void setActionsRemaining(int actions) { this.actionsRemaining = actions; }
    public void setTurnTaken(boolean turnTaken) { this.turnTaken = turnTaken; }

    // ------------------------------------------------------------------
    // Elimination
    // ------------------------------------------------------------------

    public Elimination getElimination() { return elimination; }
    public boolean isEliminated() { return elimination != null; }
    public boolean isInPlay() { return elimination == null; }

    /** True once damage/horror has reached the printed maximum (the defeat condition). */
    public boolean isDefeatedByTrauma() {
        return damage >= health || horror >= sanity;
    }

    /** Which trauma defeated them, or {@code null} if they are still standing. */
    public Elimination traumaCause() {
        if (damage >= health) return Elimination.DAMAGE;
        if (horror >= sanity) return Elimination.HORROR;
        return null;
    }

    public void eliminate(Elimination cause) {
        if (elimination == null) {
            elimination = cause;
            actionsRemaining = 0;
            turnTaken = true;
        }
    }

    // ------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------

    public void takeDamage(int d) { this.damage += Math.max(0, d); }
    public void takeHorror(int h) { this.horror += Math.max(0, h); }
    public void gainResources(int r) { this.resources += r; }
    public void spendResources(int r) { this.resources = Math.max(0, this.resources - r); }
    public void gainClue() { this.cluesHeld++; }
    public void spendClues(int n) { this.cluesHeld = Math.max(0, this.cluesHeld - n); }
    public void spendAction() { if (actionsRemaining > 0) actionsRemaining--; }

    public void engage(String enemyId) {
        if (!engagedEnemyIds.contains(enemyId)) engagedEnemyIds.add(enemyId);
    }

    public void disengage(String enemyId) {
        engagedEnemyIds.remove(enemyId);
    }

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    public CardInstance findHandCard(String cardId) {
        return hand.stream().filter(c -> c.cardId().equals(cardId)).findFirst().orElse(null);
    }

    /**
     * Take the top card of the deck into hand. The caller must have refilled an empty
     * deck first ({@link #reshuffleDiscardIntoDeck}) — this returns {@code null} when
     * there is nothing left to draw.
     */
    public CardInstance drawFromDeck() {
        if (deck.isEmpty()) {
            return null;
        }
        CardInstance card = deck.remove(0);
        hand.add(card);
        return card;
    }

    /** Empty deck: the discard pile becomes the new deck. The caller shuffles it. */
    public void reshuffleDiscardIntoDeck() {
        deck.addAll(discardPile);
        discardPile.clear();
    }

    /** Move a card from hand to the discard pile (committed to a test, or discarded down). */
    public void discard(CardInstance card) {
        if (hand.remove(card)) discardPile.add(card);
    }

    /** Move a card from hand into play (an asset). */
    public void putIntoPlay(CardInstance card) {
        if (hand.remove(card)) inPlay.add(card);
    }

    /** Discard a card that is currently in play. */
    public void discardFromPlay(CardInstance card) {
        if (inPlay.remove(card)) discardPile.add(card);
    }

    public boolean isOverHandLimit() {
        return hand.size() > HAND_LIMIT;
    }

    /** Wire projection including private hand contents. */
    public List<HandCard> handView() {
        return hand.stream().map(CardInstance::toHandCard).toList();
    }

    /** Wire projection of the assets in front of this investigator (public information). */
    public List<HandCard> inPlayView() {
        return inPlay.stream().map(CardInstance::toHandCard).toList();
    }
}
