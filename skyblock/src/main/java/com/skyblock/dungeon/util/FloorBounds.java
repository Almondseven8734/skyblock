package com.skyblock.dungeon.util;

/**
 * Pure math utility for dungeon floor geometry:
 *   - the horizontal generation radius leash (2000 blocks from a floor's origin)
 *   - vertical floor slot layout (20 blocks tall + 1 block border between floors)
 *
 * No Bukkit/Paper dependencies on purpose — this is unit-testable in isolation.
 */
public final class FloorBounds {

    /** Horizontal cap: terrain on a floor never generates beyond this distance from its origin. */
    public static final int GENERATION_RADIUS = 2000;

    /**
     * Thickness of the always-solid ring just inside GENERATION_RADIUS.
     * Noise-driven cave carving is never allowed to open air within this
     * band, so there's always a solid stone shell between the deepest
     * carvable cave space and the void beyond the leash - otherwise a
     * lucky cave-noise roll right at the edge could open straight into
     * the void with nothing to stop a player walking out of the floor.
     */
    public static final int WALL_BAND_THICKNESS = 8;

    /** Height of a single floor's playable vertical space. */
    public static final int FLOOR_HEIGHT = 20;

    /** Solid, impassable border separating each floor from its neighbors. */
    public static final int FLOOR_BORDER = 1;

    /** Combined vertical footprint of one floor slot (floor + its bottom border). */
    public static final int FLOOR_SLOT_HEIGHT = FLOOR_HEIGHT + FLOOR_BORDER;

    /**
     * Floor 0 (the "Area Zero" entrance hub) is offset this far
     * horizontally from Floor 1's origin.
     *
     * Chosen so Area Zero's outer wall (radius AREA_RADIUS + BORDER_THICKNESS
     * = 100 + 2 = 102, see DungeonHubBuilder) has its OUTER FACE sitting
     * at exactly x = GENERATION_RADIUS (2000) from Floor 1's origin - i.e.
     * right on Floor 1's generation-radius line, not out past it and not
     * inside it:
     *   offset - (AREA_RADIUS + BORDER_THICKNESS) == GENERATION_RADIUS
     *   2101 - 102 == 1999 (1 block short of 2000 by design/request)
     * This used to be tuned against GENERATION_RADIUS - WALL_BAND_THICKNESS
     * (1992) instead, which pulled the whole hub 8 blocks further from
     * Floor 1's origin than intended and let the wall sit outside Floor
     * 1's own safe-carve radius, with a solid stone band between the
     * two that read as "generating inside the dungeon" - Floor 1's cave
     * carving stops WALL_BAND_THICKNESS (8) short of GENERATION_RADIUS,
     * so anchoring the hub to that inner line meant its wall/doorway
     * were parked in the middle of Floor 1's own wall-band buffer
     * rather than flush against the actual edge of the dungeon.
     * If DungeonHubBuilder's AREA_RADIUS or BORDER_THICKNESS ever change,
     * this must be recomputed to preserve that invariant, or Area Zero's
     * wall either clips past x2000 into Floor 1's carve space (offset
     * too small) or leaves a gap short of x2000 (offset too large).
     */
    public static final int FLOOR_0_TO_FLOOR_1_OFFSET = 2101;

    /** Minimum separation required between two staircases generated on the same floor. */
    public static final int MIN_STAIRCASE_SEPARATION = 40;

    /**
     * Floor Y-layers kept solid as walkable ground (from floorBottomY
     * upward). Must match DungeonRoomPlanner's carving pass - kept here
     * as the single shared source of truth so anything placing an entity
     * or block "on the floor" (mob spawner, chest placer, boss trigger)
     * agrees with the carver on where solid ground actually ends and
     * carvable cave space actually begins.
     */
    public static final int SOLID_FLOOR_LAYERS = 2;

    /** Ceiling Y-layers kept solid (from floorTopY downward). */
    public static final int SOLID_CEIL_LAYERS = 1;

    private final int worldMinY;
    private final int worldMaxY;

    public FloorBounds(int worldMinY, int worldMaxY) {
        if (worldMaxY <= worldMinY) {
            throw new IllegalArgumentException("worldMaxY must be greater than worldMinY");
        }
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
    }

    /** Convenience constructor for a standard Paper 1.21.x world (-64 to 320). */
    public static FloorBounds standardWorld() {
        return new FloorBounds(-64, 320);
    }

    /**
     * Maximum number of floors that fit between worldMinY and worldMaxY,
     * given each floor needs FLOOR_SLOT_HEIGHT of vertical space.
     */
    public int maxFloorCount() {
        int totalHeight = worldMaxY - worldMinY;
        return totalHeight / FLOOR_SLOT_HEIGHT;
    }

    /**
     * The Y coordinate of the floor of floor N's walkable space (1-indexed floors).
     * Floor 1 starts at worldMaxY and floors stack downward.
     */
    public int floorBottomY(int floorNumber) {
        if (floorNumber < 1) {
            throw new IllegalArgumentException("floorNumber must be >= 1");
        }
        return worldMaxY - (floorNumber * FLOOR_SLOT_HEIGHT);
    }

    /** The Y coordinate of the top of floor N's walkable space. */
    public int floorTopY(int floorNumber) {
        return floorBottomY(floorNumber) + FLOOR_HEIGHT;
    }

    /**
     * The Y coordinate an entity/block should stand ON (i.e. the first
     * carvable, potentially-air cave-band layer) for floor N - the solid
     * floor itself occupies floorBottomY..floorBottomY+SOLID_FLOOR_LAYERS-1,
     * so anything meant to stand on top of the ground belongs here, not at
     * floorBottomY+1 (which is still inside the solid floor slab).
     */
    public int walkableFloorY(int floorNumber) {
        return floorBottomY(floorNumber) + SOLID_FLOOR_LAYERS;
    }

    /**
     * Whether a given world Y coordinate falls within floor N's walkable
     * vertical band (excluding its border).
     */
    public boolean isWithinFloor(int floorNumber, int y) {
        return y >= floorBottomY(floorNumber) && y < floorTopY(floorNumber);
    }

    /**
     * Resolves which floor number's walkable band a given world Y falls
     * into, or -1 if it falls in a border/outside every floor (e.g. in
     * Floor 0's hub, or in the gap between floors). Scans up to
     * maxFloorCount() - cheap enough to call per block event since this
     * runs on the rare occasions a chest is closed/broken, not per tick.
     */
    public int floorForY(int y) {
        int max = maxFloorCount();
        for (int floor = 1; floor <= max; floor++) {
            if (isWithinFloor(floor, y)) {
                return floor;
            }
        }
        return -1;
    }

    /**
     * Whether a horizontal point is within the 2000-block generation leash
     * of a floor's origin. Uses flat (X/Z) Chebyshev-free Euclidean distance.
     */
    public boolean isWithinGenerationRadius(double originX, double originZ, double x, double z) {
        double dx = x - originX;
        double dz = z - originZ;
        return (dx * dx + dz * dz) <= ((double) GENERATION_RADIUS * GENERATION_RADIUS);
    }

    /**
     * Whether a horizontal point is within the "safe carve" radius - the
     * generation radius minus WALL_BAND_THICKNESS. Cave carving must only
     * open air inside this smaller radius, guaranteeing at least
     * WALL_BAND_THICKNESS blocks of solid stone remain between any carved
     * cave space and the void outside GENERATION_RADIUS.
     */
    public boolean isWithinCarveRadius(double originX, double originZ, double x, double z) {
        double dx = x - originX;
        double dz = z - originZ;
        double safeRadius = GENERATION_RADIUS - WALL_BAND_THICKNESS;
        return (dx * dx + dz * dz) <= (safeRadius * safeRadius);
    }

    /**
     * Clamps a target point so it never exceeds the generation radius from
     * the floor's origin - useful when a generation frontier wants to expand
     * past the leash and needs to be pulled back to the boundary instead.
     */
    public double[] clampToRadius(double originX, double originZ, double x, double z) {
        double dx = x - originX;
        double dz = z - originZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= GENERATION_RADIUS) {
            return new double[]{x, z};
        }
        double scale = GENERATION_RADIUS / dist;
        return new double[]{originX + dx * scale, originZ + dz * scale};
    }

    public int worldMinY() {
        return worldMinY;
    }

    public int worldMaxY() {
        return worldMaxY;
    }
}
