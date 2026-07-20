package com.arkham.server.dto;

import com.arkham.engine.event.GameEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A full campaign save (docs/09 §5) — the unit that gets copied to every player's
 * local storage and offered back to reload. Holds campaign metadata + the roster
 * (each member's investigator / deck / xp / status) and, when saved mid-scenario,
 * the engine {@code snapshot} + {@code eventLog} for the log replay.
 *
 * <p>{@code snapshot} is an opaque JSON tree of the engine {@code GameState}
 * (same shape as RESUME's {@code state}); null when the save was taken during the
 * deckbuilding stage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CampaignSave(
        String campaignId,
        String name,
        String campaignKey,
        String difficulty,
        String stage,               // DECKBUILDING | IN_SCENARIO
        List<SavedMember> roster,
        List<String> deadInvestigators,   // 此存檔永久封鎖的角色(死過,docs/09 §10)
        int maxXp,                  // 此戰役路線至今可取得的最大經驗(XP 上限)
        Object snapshot,            // 引擎狀態樹(IN_SCENARIO 才有,否則 null)
        List<GameEvent> eventLog,
        int round,
        int currentChapter) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SavedMember(
            String playerId,
            String displayName,
            String investigatorId,
            List<String> deck,
            int xp,
            String status,
            int physicalTrauma,     // 創傷跨章保留(docs/09 §9;官方 p20);舊存檔缺欄位 → 0
            int mentalTrauma) {
    }
}
