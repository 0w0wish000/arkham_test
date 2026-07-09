package com.arkham.engine.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative, mutable state for one investigator. The engine mutates this in
 * place; {@link com.arkham.engine.RulesEngine#viewFor} projects it to the filtered
 * {@code SelfView} / {@code OtherInvestigatorView} records for the wire.
 *
 * <p>{@code health}/{@code sanity} are the printed maxima; {@code damage}/{@code horror}
 * are the amounts currently marked (matching the protocol's SelfView fields).
 */
public final class Investigator {

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
    private int weaponBonus;

    private final List<CardInstance> hand = new ArrayList<>();
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
    public int getWeaponBonus() { return weaponBonus; }

    public List<CardInstance> getHand() { return hand; }
    public List<CardInstance> getDiscardPile() { return discardPile; }
    public List<String> getEngagedEnemyIds() { return engagedEnemyIds; }

    public void setLocationId(String locationId) { this.locationId = locationId; }
    public void setActionsRemaining(int actions) { this.actionsRemaining = actions; }
    public void setWeaponBonus(int weaponBonus) { this.weaponBonus = weaponBonus; }

    public boolean isDefeated() {
        return damage >= health || horror >= sanity;
    }

    public void takeDamage(int d) { this.damage += Math.max(0, d); }
    public void takeHorror(int h) { this.horror += Math.max(0, h); }
    public void gainResources(int r) { this.resources += r; }
    public void gainClue() { this.cluesHeld++; }
    public void spendClues(int n) { this.cluesHeld = Math.max(0, this.cluesHeld - n); }
    public void spendAction() { if (actionsRemaining > 0) actionsRemaining--; }

    public void engage(String enemyId) {
        if (!engagedEnemyIds.contains(enemyId)) engagedEnemyIds.add(enemyId);
    }

    public void disengage(String enemyId) {
        engagedEnemyIds.remove(enemyId);
    }

    public CardInstance findHandCard(String cardId) {
        return hand.stream().filter(c -> c.cardId().equals(cardId)).findFirst().orElse(null);
    }

    /** Move a card from hand to the discard pile (step 8 of a skill test). */
    public void discard(CardInstance card) {
        if (hand.remove(card)) discardPile.add(card);
    }

    /** Wire projection including private hand contents. */
    public List<HandCard> handView() {
        return hand.stream().map(CardInstance::toHandCard).toList();
    }
}
