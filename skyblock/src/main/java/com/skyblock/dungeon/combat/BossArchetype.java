package com.skyblock.dungeon.combat;

import org.bukkit.entity.EntityType;

/**
 * Static definition of a themed boss identity for a vanilla base mob
 * type - a proper name (e.g. "The Undead Lord") plus which ability
 * routine GenericBossBehavior should run for it, instead of every
 * floor's boss just being a tier-colored, leveled-up copy of an
 * ordinary mob with the same generic "[Tier] Zombie [Lv. 12]" name and
 * no attacks beyond vanilla melee.
 *
 * Multiple archetypes can share one abilityId if their attack pattern
 * is meant to feel the same (e.g. both skeleton-family bosses use the
 * "arrow_volley" ability) while still getting distinct names/flavor.
 * DungeonBossRoomTrigger picks one archetype matching the boss's base
 * EntityType at spawn time via BossArchetypeRegistry.
 */
public final class BossArchetype {

    private final EntityType baseType;
    private final String bossName;
    private final String abilityId;

    public BossArchetype(EntityType baseType, String bossName, String abilityId) {
        this.baseType = baseType;
        this.bossName = bossName;
        this.abilityId = abilityId;
    }

    public EntityType getBaseType() {
        return baseType;
    }

    /** Full colored display name, e.g. "§4§lThe Undead Lord". */
    public String getBossName() {
        return bossName;
    }

    /** Id GenericBossBehavior dispatches on to pick which attack pattern to run. */
    public String getAbilityId() {
        return abilityId;
    }
}
