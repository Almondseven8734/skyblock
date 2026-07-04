package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.gen.DungeonRoom;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Random;

/**
 * Finds an actually-carved-open XZ(+Y) column within a DungeonRoom's
 * footprint. DungeonRoom's radiusX/radiusZ describe a bounding box used
 * for graph bookkeeping (staircase spacing, boss-trigger containment,
 * etc.), not the real carved shape - DungeonRoomPlanner carves rooms as
 * noise-driven caves that only open roughly 35-40% of that box to air.
 * Anything that blindly places an entity or block at a random offset
 * within the box (or dead-center) will very often land inside solid
 * stone. This class checks the actual world blocks before returning a
 * spot, so callers only ever get a real, standable location.
 *
 * IMPORTANT: this used to only ever check the single Y layer passed in
 * as `groundY` (the floor-adjacent cave layer). DungeonRoomPlanner's
 * floor/ceiling taper deliberately makes noise progressively LESS
 * likely to carve air the closer a column gets to the floor - which
 * means the exact layer this class used to check is the single least
 * likely layer in the whole cave band to actually be open, even in a
 * room that's mostly hollow one or two blocks higher up. That mismatch
 * is the root cause behind "mobs/chests aren't spawning anymore": both
 * the random attempts AND the deterministic fallback scan were
 * checking only that one unlucky layer, so a room could be entirely
 * walkable and still report zero valid spots. findOpenColumn now
 * additionally probes a few layers above groundY per (x,z) column and
 * returns the actual Y it found open, and every caller now spawns
 * at that real Y instead of assuming it's always exactly groundY.
 */
public final class DungeonSpawnLocator {

    /** How many Y layers above groundY to probe per column before giving up on that column. */
    private static final int VERTICAL_SEARCH_RANGE = 4;

    private DungeonSpawnLocator() {
    }

    /**
     * @param world      the dungeon world
     * @param room       the room to search within
     * @param groundY    the lowest Y an entity/block should stand ON - i.e.
     *                   FloorBounds.walkableFloorY(floorNumber), the first
     *                   carvable cave-band layer, NOT floorBottomY+1. The
     *                   search also checks a few layers above this in case
     *                   the taper left this exact layer solid.
     * @param random     RNG for the initial randomized attempts
     * @param maxAttempts number of random tries before falling back to a
     *                   full deterministic scan of the room's footprint
     * @return {x, y, z} of a verified open+standable column, or null if the
     *         room has no such column yet (e.g. noise happened to leave
     *         this particular footprint entirely solid)
     */
    public static int[] findOpenColumn(World world, DungeonRoom room, int groundY, Random random, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int dx = random.nextInt(room.radiusX() * 2 + 1) - room.radiusX();
            int dz = random.nextInt(room.radiusZ() * 2 + 1) - room.radiusZ();
            int x = room.centerX() + dx;
            int z = room.centerZ() + dz;
            int y = findStandableY(world, x, groundY, z);
            if (y != Integer.MIN_VALUE) {
                return new int[]{x, y, z};
            }
        }

        // Random attempts unlucky (or room mostly solid at these columns) -
        // fall back to an exhaustive scan of the footprint before giving up.
        for (int dx = -room.radiusX(); dx <= room.radiusX(); dx++) {
            for (int dz = -room.radiusZ(); dz <= room.radiusZ(); dz++) {
                int x = room.centerX() + dx;
                int z = room.centerZ() + dz;
                int y = findStandableY(world, x, groundY, z);
                if (y != Integer.MIN_VALUE) {
                    return new int[]{x, y, z};
                }
            }
        }

        return null;
    }

    /**
     * Scans upward from groundY (inclusive) through VERTICAL_SEARCH_RANGE
     * extra layers looking for the first standable+open spot at this XZ
     * column. Returns Integer.MIN_VALUE if none of the probed layers work.
     */
    private static int findStandableY(World world, int x, int groundY, int z) {
        for (int y = groundY; y <= groundY + VERTICAL_SEARCH_RANGE; y++) {
            if (isOpenStandableColumn(world, x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** True if y-1 is solid ground and y/y+1 are open air to stand/spawn in. */
    private static boolean isOpenStandableColumn(World world, int x, int y, int z) {
        Material below = world.getBlockAt(x, y - 1, z).getType();
        if (!below.isSolid()) {
            return false;
        }
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        return feet == Material.AIR && head == Material.AIR;
    }
}
