package com.skyblock.guild;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A player guild: a name, an owner, and a member roster (owner
 * included). Plain data holder - GuildStorage handles persistence,
 * GuildCommand handles the actual create/invite/join/leave/disband
 * flows that mutate this.
 */
public final class Guild {

    private final UUID id;
    private String name;
    private UUID ownerId;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final long createdAt;

    public Guild(UUID id, String name, UUID ownerId, long createdAt) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.members.add(ownerId);
    }

    public boolean isMember(UUID playerId) {
        return members.contains(playerId);
    }

    public boolean isOwner(UUID playerId) {
        return ownerId.equals(playerId);
    }

    public void addMember(UUID playerId) {
        members.add(playerId);
    }

    /** Removes a member. If the owner leaves, ownership passes to the longest-standing remaining member (insertion order), or the guild is left ownerless if that was the last member - GuildCommand disbands it in that case. */
    public UUID removeMember(UUID playerId) {
        members.remove(playerId);
        if (ownerId.equals(playerId) && !members.isEmpty()) {
            ownerId = members.iterator().next();
        }
        return members.isEmpty() ? null : ownerId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public UUID getOwnerId() { return ownerId; }
    public Set<UUID> getMembers() { return members; }
    public long getCreatedAt() { return createdAt; }

    void setName(String name) { this.name = name; }
}
