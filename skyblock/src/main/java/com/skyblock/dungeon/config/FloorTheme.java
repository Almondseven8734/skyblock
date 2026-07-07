package com.skyblock.dungeon.config;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * Static theming definition for one dungeon floor depth band.
 *
 * This is pure config data - no generation logic lives here. The
 * generation planner and mob spawner consult a FloorTheme to decide
 * what blocks/mobs/hazards to use when realizing a given floor.
 */
public final class FloorTheme {

    private final int floorNumber;
    private final String displayName;
    private final List<Material> primaryBlocks;
    private final List<Material> accentBlocks;
    private final List<EntityType> mobPool;
    private final List<EntityType> bossPool;
    private final List<String> hazardTags;
    private final boolean isMilestoneFloor;
    private final int bossCount;

    public FloorTheme(int floorNumber,
                       String displayName,
                       List<Material> primaryBlocks,
                       List<Material> accentBlocks,
                       List<EntityType> mobPool,
                       List<EntityType> bossPool,
                       List<String> hazardTags,
                       boolean isMilestoneFloor,
                       int bossCount) {
        if (bossCount < 1) {
            throw new IllegalArgumentException("Every floor must have at least one boss");
        }
        this.floorNumber = floorNumber;
        this.displayName = displayName;
        this.primaryBlocks = List.copyOf(primaryBlocks);
        this.accentBlocks = List.copyOf(accentBlocks);
        this.mobPool = List.copyOf(mobPool);
        this.bossPool = List.copyOf(bossPool);
        this.hazardTags = List.copyOf(hazardTags);
        this.isMilestoneFloor = isMilestoneFloor;
        this.bossCount = bossCount;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<Material> getPrimaryBlocks() {
        return primaryBlocks;
    }

    public List<Material> getAccentBlocks() {
        return accentBlocks;
    }

    public List<EntityType> getMobPool() {
        return mobPool;
    }

    /**
     * The pool a floor's boss room trigger rolls its boss's base
     * EntityType from - a curated, boss-worthy subset of the floor's
     * ambient mobPool (trash mobs like bats/silverfish are excluded).
     * Rolled fresh (randomly) every time a boss room triggers, so a
     * floor's boss varies across dungeon resets instead of always
     * being the exact same mob type every single week.
     */
    public List<EntityType> getBossPool() {
        return bossPool;
    }

    public List<String> getHazardTags() {
        return hazardTags;
    }

    /** Milestone floors (e.g. every 5th) get a full custom scripted boss instead of a buffed vanilla mob. */
    public boolean isMilestoneFloor() {
        return isMilestoneFloor;
    }

    /** Number of bosses that must die before this floor's staircases spawn. Always >= 1. */
    public int getBossCount() {
        return bossCount;
    }
}
