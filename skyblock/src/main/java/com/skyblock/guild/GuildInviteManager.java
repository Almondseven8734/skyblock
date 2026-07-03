package com.skyblock.guild;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending guild invites. Deliberately in-memory only (not
 * persisted) - an invite that outlives a server restart isn't worth
 * the storage complexity, the inviter can just send it again.
 */
public final class GuildInviteManager {

    private static final long INVITE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private record PendingInvite(UUID guildId, long expiresAt) {}

    /** invitee playerId -> pending invite. One outstanding invite per player at a time (a newer invite overwrites an older one). */
    private final Map<UUID, PendingInvite> invites = new ConcurrentHashMap<>();

    public void invite(UUID inviteeId, UUID guildId) {
        invites.put(inviteeId, new PendingInvite(guildId, System.currentTimeMillis() + INVITE_TTL_MS));
    }

    /** Returns the guild id the invitee has a live invite to, or null if there's none/it expired. */
    public UUID peekInvite(UUID inviteeId) {
        PendingInvite invite = invites.get(inviteeId);
        if (invite == null) return null;
        if (System.currentTimeMillis() > invite.expiresAt()) {
            invites.remove(inviteeId);
            return null;
        }
        return invite.guildId();
    }

    public void clearInvite(UUID inviteeId) {
        invites.remove(inviteeId);
    }
}
