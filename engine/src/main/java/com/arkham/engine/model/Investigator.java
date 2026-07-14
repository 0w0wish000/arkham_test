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
    private final List<CardInstance> playArea = new ArrayList<>();   // 已打出的支援(檯面)
    private final List<String> engagedEnemyIds = new ArrayList<>();
    private int[] skillBonus = new int[4];                           // 支援卡給的技能加值(依 SkillType 序;存檔可還原)

    @com.fasterxml.jackson.annotation.JsonCreator
    public Investigator(@com.fasterxml.jackson.annotation.JsonProperty("id") String id,
                        @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                        @com.fasterxml.jackson.annotation.JsonProperty("skills") Skills skills,
                        @com.fasterxml.jackson.annotation.JsonProperty("health") int health,
                        @com.fasterxml.jackson.annotation.JsonProperty("sanity") int sanity,
                        @com.fasterxml.jackson.annotation.JsonProperty("resources") int resources,
                        @com.fasterxml.jackson.annotation.JsonProperty("locationId") String locationId) {
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
    public int baseSkill(SkillType type) { return skills.of(type) + skillBonus[type.ordinal()]; }

    /** 支援卡給的持久技能加值(例:放大鏡 +1 智力)。 */
    public void addSkillBonus(SkillType type, int n) { skillBonus[type.ordinal()] += n; }

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

    /** 打出支援:從手牌移到檯面。 */
    public void playToArea(CardInstance card) {
        if (hand.remove(card)) playArea.add(card);
    }

    public List<CardInstance> getPlayArea() { return playArea; }

    /** 治療傷害(下限 0)。 */
    public void heal(int n) { this.damage = Math.max(0, this.damage - n); }

    /** Wire projection including private hand contents. */
    public List<HandCard> handView() {
        return hand.stream().map(CardInstance::toHandCard).toList();
    }

    /** 檯面支援的 wire 投影。 */
    public List<HandCard> playAreaView() {
        return playArea.stream().map(CardInstance::toHandCard).toList();
    }
}
