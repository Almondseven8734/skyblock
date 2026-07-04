package com.skyblock.dungeon.spawn;

import org.bukkit.Material;
import org.bukkit.World;

/**
 * Builds the physical pedestal a floor boss spawns standing on: a
 * flat, 1-block-thick, 4-block-radius disc of smooth stone laid into
 * the floor layer directly beneath the boss's spawn column, replacing
 * whatever themed floor block was there. Purely cosmetic/positional -
 * it doesn't change collision height (the boss still stands at the
 * same verified-open Y DungeonSpawnLocator found), it just makes that
 * spot visibly read as "a boss stands here" rather than blending into
 * the surrounding cave floor.
 */
public final class DungeonBossPedestal {

    /** Radius of the disc, in blocks, per design ("4 block radius"). */
    public static final int RADIUS = 4;

    private DungeonBossPedestal() {
    }

    /**
     * @param world   the dungeon world
     * @param standX  X of the block the boss stands on top of
     * @param standY  Y the boss's feet occupy (the pedestal is carved one layer below this)
     * @param standZ  Z of the block the boss stands on top of
     */
    public static void build(World world, int standX, int standY, int standZ) {
        int platformY = standY - 1;
        int radiusSquared = RADIUS * RADIUS;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue; // keep the disc circular, not a square slab
                }
                world.getBlockAt(standX + dx, platformY, standZ + dz).setType(Material.SMOOTH_STONE, false);
            }
        }
    }
}
