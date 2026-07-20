package com.arkham.server.dto;

/**
 * One member of a campaign session's roster (mirrors {@code RosterMember} in
 * protocol/messages.ts). {@code playerId} = the person; {@code investigatorId} =
 * their chosen character in this save (null until picked). {@code status} is one of
 * ACTIVE / SITTING_OUT / DEAD; {@code ready} drives the barrier UI.
 */
public record RosterMember(
        String playerId,
        String displayName,
        String investigatorId,
        boolean ready,
        String status,
        boolean connected,
        int physicalTrauma,
        int mentalTrauma) {
}
