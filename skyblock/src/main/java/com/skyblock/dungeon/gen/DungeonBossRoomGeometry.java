package com.skyblock.dungeon.gen;

/**
 * Shape spec for a boss room: a 30-block-radius, 18-block-tall cylinder
 * (drum) with 4 rectangular doorways cut through the wall at the
 * cardinal compass points.
 *
 * Pure math, no Bukkit dependency, so both the carver
 * (DungeonRoomPlanner, which cuts the physical shape into the world)
 * and the gate controller (DungeonBossGateController, which seals and
 * reopens the doorways at runtime) work off the exact same geometry
 * and can never drift out of sync with each other.
 *
 * All coordinates taken by these methods are relative to the room's
 * center (dx = worldX - room.centerX(), dz = worldZ - room.centerZ()).
 */
public final class DungeonBossRoomGeometry {

    private DungeonBossRoomGeometry() {}

    /** Horizontal radius of the drum, in blocks. */
    public static final int RADIUS = 30;

    /** Total vertical span of the drum (floor cap + open interior + ceiling cap), in blocks. */
    public static final int HEIGHT = 18;

    /** Thickness of the solid ring that forms the drum's wall. */
    public static final int WALL_THICKNESS = 3;

    /**
     * Half-width of each gate doorway along the wall's arc, in blocks.
     * A gate is 2*GATE_HALF_WIDTH + 1 blocks wide - 7 blocks by
     * default, wide enough for a full party to move through together.
     */
    public static final int GATE_HALF_WIDTH = 3;

    private static final int INNER_RADIUS = RADIUS - WALL_THICKNESS;

    public enum Gate { NORTH, SOUTH, EAST, WEST }

    /** True if (dx, dz) falls anywhere inside the drum's circular footprint (interior + wall). */
    public static boolean isInsideFootprint(int dx, int dz) {
        long distSq = (long) dx * dx + (long) dz * dz;
        return distSq <= (long) RADIUS * RADIUS;
    }

    /** True if (dx, dz) falls specifically inside the solid wall ring (not the open interior). */
    public static boolean isInsideWallRing(int dx, int dz) {
        long distSq = (long) dx * dx + (long) dz * dz;
        return distSq <= (long) RADIUS * RADIUS && distSq > (long) INNER_RADIUS * INNER_RADIUS;
    }

    /**
     * Returns which of the 4 gates this wall-ring point belongs to, or
     * null if it's ordinary solid wall. Only meaningful for points
     * where isInsideWallRing(dx, dz) is true.
     *
     * Each gate is a straight radial cut through the wall ring,
     * GATE_HALF_WIDTH blocks either side of the compass axis, so the
     * doorway reads as a clean rectangular opening rather than a
     * curved notch.
     */
    public static Gate gateAt(int dx, int dz) {
        if (Math.abs(dx) <= GATE_HALF_WIDTH) {
            return dz < 0 ? Gate.NORTH : Gate.SOUTH;
        }
        if (Math.abs(dz) <= GATE_HALF_WIDTH) {
            return dx < 0 ? Gate.WEST : Gate.EAST;
        }
        return null;
    }
}
