package com.arkham.server.dto;

import java.util.List;

/**
 * A one-line entry in the {@code LOBBY} "active tables" list (mirrors
 * {@code SessionSummary} in protocol/messages.ts). Replaces the old room concept:
 * players pick the same table from this list instead of typing a room name.
 */
public record SessionSummary(
        String campaignId,
        String name,
        String campaignKey,
        String difficulty,
        String stage,
        int memberCount,
        List<MemberBrief> members) {

    /** Just enough to show who's at the table in the lobby list. */
    public record MemberBrief(String displayName, String investigatorId) {}
}
