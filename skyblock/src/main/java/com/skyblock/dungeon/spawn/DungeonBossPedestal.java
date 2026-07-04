package com.skyblock.dungeon.spawn;

import org.bukkit.Material;
import org.bukkit.World;

/**
 * Builds the physical pedestal a floor boss spawns standing on: a
 * flat, 1-block-thick, 4-block-radius disc of smooth stone built as a
 * raised dais one block above the surrounding room floor, replacing
 * whatever themed floor block was there at that raised layer. Raised
 * (rather than flush with the floor) so it actually reads as a visible
 * "a boss stands here" platform instead of just re-texturing the floor
 * block under the boss's feet. The caller is responsible for spawning
 * the boss one block above standY (i.e. standing on top of the dais,
 * not embedded in it) - see DungeonBossRoomTrigger.
 */
public final class DungeonBossPedestal {

    /** Radius of the disc, in blocks, per design ("4 block radius"). */
    public static final int RADIUS = 4;

    private DungeonBossPedestal() {
    }

    /**
     * @param world   the dungeon world
     * @param standX  X of the room floor's stand column (dais center)
     * @param standY  Y of the room floor (the dais disc is built AT this layer, one block above the old
     *                floor-flush placement, so it's a visibly raised platform; the boss stands at standY + 1)
     * @param standZ  Z of the room floor's stand column (dais center)
     */
    public static void build(World world, int standX, int standY, int standZ) {
        int platformY = standY;
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
