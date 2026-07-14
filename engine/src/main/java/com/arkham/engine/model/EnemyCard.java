package com.arkham.engine.model;

import java.util.List;

/**
 * Authoritative, mutable enemy state (a spawned instance). Projects to {@code EnemyView}.
 * {@code fight}/{@code evade} are the test difficulties; {@code damage}/{@code horror} are
 * the amounts this enemy deals when it attacks (not exposed on the wire);
 * {@code damageOn} is damage currently marked toward its {@code health}.
 */
public final class EnemyCard {

    private final String id;
    private final String defKey;
    private final String name;
    private final int fight;
    private final int health;
    private final int evade;
    private final int damage;
    private final int horror;
    private final List<Keyword> keywords;

    private int damageOn;
    private boolean exhausted;
    private String locationId;
    /** Investigator id this enemy is engaged with, or {@code null} if unengaged. */
    private String engagedWith;

    @com.fasterxml.jackson.annotation.JsonCreator
    public EnemyCard(@com.fasterxml.jackson.annotation.JsonProperty("id") String id,
                     @com.fasterxml.jackson.annotation.JsonProperty("defKey") String defKey,
                     @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                     @com.fasterxml.jackson.annotation.JsonProperty("fight") int fight,
                     @com.fasterxml.jackson.annotation.JsonProperty("health") int health,
                     @com.fasterxml.jackson.annotation.JsonProperty("evade") int evade,
                     @com.fasterxml.jackson.annotation.JsonProperty("damage") int damage,
                     @com.fasterxml.jackson.annotation.JsonProperty("horror") int horror,
                     @com.fasterxml.jackson.annotation.JsonProperty("keywords") List<Keyword> keywords,
                     @com.fasterxml.jackson.annotation.JsonProperty("locationId") String locationId) {
        this.id = id;
        this.defKey = defKey;
        this.name = name;
        this.fight = fight;
        this.health = health;
        this.evade = evade;
        this.damage = damage;
        this.horror = horror;
        this.keywords = List.copyOf(keywords);
        this.locationId = locationId;
    }

    public String getId() { return id; }
    public String getDefKey() { return defKey; }
    public String getName() { return name; }
    public int getFight() { return fight; }
    public int getHealth() { return health; }
    public int getEvade() { return evade; }
    public int getDamage() { return damage; }
    public int getHorror() { return horror; }
    public List<Keyword> getKeywords() { return keywords; }

    public int getDamageOn() { return damageOn; }
    public boolean isExhausted() { return exhausted; }
    public String getLocationId() { return locationId; }
    public String getEngagedWith() { return engagedWith; }

    public void setExhausted(boolean exhausted) { this.exhausted = exhausted; }
    public void setLocationId(String locationId) { this.locationId = locationId; }
    public void setEngagedWith(String engagedWith) { this.engagedWith = engagedWith; }

    public boolean hasKeyword(Keyword k) { return keywords.contains(k); }
    public boolean isEngaged() { return engagedWith != null; }
    public boolean isReady() { return !exhausted; }

    public void applyDamage(int d) { this.damageOn += Math.max(0, d); }
    public boolean isDefeated() { return damageOn >= health; }
}
