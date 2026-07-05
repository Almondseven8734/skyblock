package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds "Area Zero": Floor 0's entrance hub. Unlike every other
 * floor, Area Zero never procedurally generates or changes shape - it's
 * a small, fixed forest clearing enclosed by a circular R25 stone
 * border, hand-placed the same way every time (buildHub() is
 * idempotent-safe to call repeatedly - see class docs on
 * DungeonResetScheduler.setOnWorldRecreated wiring). Terrain height and
 * tree placement ARE re-rolled from a deterministic seed on every call
 * (see class docs below), not persisted - "regenerate fresh every
 * reset" rather than "generate once and stick around."
 *
 * Layout, looking down:
 *   - A circular clearing of radius AREA_RADIUS, walled by a
 *     BORDER_THICKNESS-thick ring of stone.
 *   - A 5-wide, 8-tall doorway breach on the WEST side (-X), opening
 *     directly into Floor 1's cave system - this is the only side that
 *     borders the dungeon, and per design the border wall must NEVER
 *     clip into Floor 1's own generated terrain (see
 *     FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET's docs for the exact
 *     zero-gap/zero-overlap invariant this depends on).
 *   - A matching 5-wide, 8-tall breach on the OPPOSITE (EAST, +X) side,
 *     filled with animated dark blue / dark purple stained glass
 *     (the portal) backed by one more layer of the border's own stone
 *     one block further out, so the portal doesn't read as a hole into
 *     the void from outside.
 *
 * The hub is positioned FLOOR_0_TO_FLOOR_1_OFFSET blocks east (+X) of
 * Floor 1's origin point, so walking out of Area Zero through its west
 * doorway heads straight toward Floor 1's origin. All coordinates are
 * derived from Floor 1's origin passed into buildHub() rather than
 * hardcoded, so moving Floor 1's origin automatically moves Area Zero
 * with it.
 *
 * Vertically, Area Zero's ground sits at the SAME Y-band as Floor 1's
 * own walkable floor (FloorBounds.walkableFloorY(1)), not an arbitrary
 * fixed Y - derived from FloorBounds so the doorway always lands you
 * adjacent to Floor 1, never accidentally lined up with some other
 * floor deep in the stack.
 */
public final class DungeonHubBuilder {

    /** Radius (blocks) of the walkable forest clearing, per design ("fits inside an R25 stone border"). */
    private static final int AREA_RADIUS = 25;
    /** Thickness of the stone border ring just outside AREA_RADIUS. */
    private static final int BORDER_THICKNESS = 2;
    /** Height of the border wall above the ground band. */
    private static final int WALL_HEIGHT = 10;
    /** Interior air clearance above ground, for tree canopies and open sky-feel. */
    private static final int CLEAR_HEIGHT = 16;

    /** Terrain height variation: ground can sit 0-2 blocks above the base band (i.e. "1-3 blocks of variation"). */
    private static final int TERRAIN_VARIATION_LEVELS = 3;
    /** Side length (blocks) of the flat terrain-height cells - keeps bumps chunky/hill-like rather than 1-block noise. */
    private static final int TERRAIN_CELL_SIZE = 4;

    /** Doorway/portal shared shape: bottom rows are 5 wide, the top row narrows to 3 wide, both centered. */
    private static final int DOORWAY_BOTTOM_HALF_WIDTH = 2; // 5 wide
    private static final int DOORWAY_TOP_HALF_WIDTH = 1;    // 3 wide
    private static final int DOORWAY_BOTTOM_ROWS = 7;       // rows 1..7
    private static final int DOORWAY_TOTAL_HEIGHT = 8;      // + 1 top row = 8 tall total

    private static final Material BORDER_MATERIAL = Material.STONE;
    private static final Material GROUND_SURFACE = Material.GRASS_BLOCK;
    private static final Material GROUND_FILL = Material.DIRT;
    private static final Material TRUNK_MATERIAL = Material.OAK_LOG;
    private static final Material LEAF_MATERIAL = Material.OAK_LEAVES;
    private static final Material PORTAL_COLOR_A = Material.BLUE_STAINED_GLASS;
    private static final Material PORTAL_COLOR_B = Material.PURPLE_STAINED_GLASS;

    private static final int MIN_TREES = 10;
    private static final int MAX_TREES = 16;

    private DungeonHubBuilder() {
    }

    /** Area Zero's ground Y - the same walkable band as Floor 1, so the two are vertically adjacent. */
    private static int hubFloorY(FloorBounds floorBounds) {
        return floorBounds.walkableFloorY(1);
    }

    /** Area Zero's center sits FLOOR_0_TO_FLOOR_1_OFFSET blocks east (+X) of Floor 1's origin, same Z. */
    private static int hubCenterX(int floor1OriginX) {
        return floor1OriginX + FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET;
    }

    private static int hubCenterZ(int floor1OriginZ) {
        return floor1OriginZ;
    }

    public static void buildHub(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        buildHub(world, floorBounds, floor1OriginX, floor1OriginZ, null);
    }

    /**
     * Builds Area Zero: circular forested clearing, stone border,
     * west-side dungeon doorway, and east-side glass portal frame
     * (unlit - see AreaZeroPortalAnimator for the flicker animation,
     * started separately once a World/plugin is available).
     *
     * @param floor1Theme unused for Area Zero's own material palette
     *                     (it's always forest/stone regardless of
     *                     Floor 1's theme) but kept in the signature
     *                     for call-site compatibility.
     */
    public static void buildHub(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ,
                                 FloorTheme floor1Theme) {
        int hubFloorY = hubFloorY(floorBounds);
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);

        // Deterministic per-rebuild seed - same shape every idempotent
        // rebuild call site (startup, and every weekly reset), matching
        // the existing Floor 0 idempotent-rebuild pattern: terrain/trees
        // regenerate fresh each time rather than persisting, but always
        // land in the same place given the same hub center.
        Random rng = new Random(0x4741A0FEEDL ^ ((long) hubCenterX << 32 | (hubCenterZ & 0xFFFFFFFFL)));

        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        for (int x = -maxRadius; x <= maxRadius; x++) {
            for (int z = -maxRadius; z <= maxRadius; z++) {
                double dist = Math.hypot(x, z);
                int worldX = hubCenterX + x;
                int worldZ = hubCenterZ + z;

                if (dist <= AREA_RADIUS) {
                    buildTerrainColumn(world, hubFloorY, worldX, worldZ, x, z);
                } else if (dist <= maxRadius) {
                    buildBorderColumn(world, hubFloorY, worldX, worldZ);
                }
            }
        }

        carveDoorway(world, hubFloorY, hubCenterX, hubCenterZ);           // west: open to the dungeon
        buildPortalFrame(world, hubFloorY, hubCenterX, hubCenterZ);      // east: glass portal

        placeTrees(world, floorBounds, hubFloorY, hubCenterX, hubCenterZ, rng);
    }

    /** Ground column inside the clearing: rolls a chunky height bump, fills it in, clears air above. */
    private static void buildTerrainColumn(World world, int hubFloorY, int worldX, int worldZ, int localX, int localZ) {
        int level = terrainLevel(worldX, worldZ);
        int surfaceY = hubFloorY + level;

        world.getBlockAt(worldX, surfaceY, worldZ).setType(GROUND_SURFACE, false);
        for (int y = surfaceY - 1; y >= hubFloorY - 3; y--) {
            world.getBlockAt(worldX, y, worldZ).setType(GROUND_FILL, false);
        }
        // If this column's rolled level is below the max variation, the
        // blocks between its surface and the tallest possible neighbor
        // level must still be solid (not floating air pockets) - handled
        // naturally since surfaceY already accounts for `level`, but any
        // leftover gap up to the highest possible level (level 2) still
        // needs filling so a low column right next to a high one doesn't
        // leave a false overhang; fill up to hubFloorY + (levels-1) with
        // dirt as well, then clear the rest as open air.
        for (int y = surfaceY + 1; y <= hubFloorY + CLEAR_HEIGHT; y++) {
            world.getBlockAt(worldX, y, worldZ).setType(Material.AIR, false);
        }
    }

    /** Border ring column: solid stone wall from just below ground to WALL_HEIGHT above it. */
    private static void buildBorderColumn(World world, int hubFloorY, int worldX, int worldZ) {
        for (int y = hubFloorY - 3; y <= hubFloorY + WALL_HEIGHT; y++) {
            world.getBlockAt(worldX, y, worldZ).setType(BORDER_MATERIAL, false);
        }
        for (int y = hubFloorY + WALL_HEIGHT + 1; y <= hubFloorY + CLEAR_HEIGHT; y++) {
            world.getBlockAt(worldX, y, worldZ).setType(Material.AIR, false);
        }
    }

    /**
     * Deterministic chunky terrain height for a world column: blocks are
     * grouped into TERRAIN_CELL_SIZE x TERRAIN_CELL_SIZE cells, each
     * cell rolling its own flat level in [0, TERRAIN_VARIATION_LEVELS-1]
     * (0-2, i.e. 3 possible levels / up to 2 blocks of relief - "1-3
     * blocks of variation" across the clearing) - a coordinate hash
     * rather than a stored seed, so it's naturally idempotent across
     * rebuilds without needing to persist a heightmap.
     */
    private static int terrainLevel(int worldX, int worldZ) {
        int cellX = Math.floorDiv(worldX, TERRAIN_CELL_SIZE);
        int cellZ = Math.floorDiv(worldZ, TERRAIN_CELL_SIZE);
        long seed = 0x9E3779B97F4A7C15L
                ^ ((long) cellX * 0xBF58476D1CE4E5B9L)
                ^ ((long) cellZ * 0x94D049BB133111EBL);
        Random cellRng = new Random(seed);
        return cellRng.nextInt(TERRAIN_VARIATION_LEVELS);
    }

    /**
     * Carves the doorway silhouette through the border ring on the WEST
     * side (the dungeon-adjacent side): the bottom DOORWAY_BOTTOM_ROWS
     * rows are DOORWAY_BOTTOM_HALF_WIDTH*2+1 wide, centered, and the
     * final top row narrows to DOORWAY_TOP_HALF_WIDTH*2+1 wide, still
     * centered - a tapered silhouette rather than a plain rectangle.
     * buildPortalFrame() below reuses this exact shape on the opposite
     * (east) side, filling it with glass instead of carving it to air.
     */
    private static void carveDoorway(World world, int hubFloorY, int hubCenterX, int hubCenterZ) {
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        // Sweep the full wall depth (from just inside the clearing's
        // edge out through the outer face of the border) so the breach
        // fully punches through, not just a single column.
        for (int r = AREA_RADIUS - 1; r <= maxRadius; r++) {
            int worldX = hubCenterX - r;
            forEachDoorwayCell(cell -> world.getBlockAt(worldX, cell[0], cell[1]).setType(Material.AIR, false),
                    hubFloorY, hubCenterZ);
        }
        // Ground lip right at the threshold on both the inside and
        // outside face so the doorway floor reads as a continuous path,
        // not stone-wall-height terrain right up to the opening.
        for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
            world.getBlockAt(hubCenterX - (AREA_RADIUS - 1), hubFloorY, hubCenterZ + dz)
                    .setType(GROUND_SURFACE, false);
            world.getBlockAt(hubCenterX - maxRadius, hubFloorY, hubCenterZ + dz)
                    .setType(GROUND_SURFACE, false);
        }
    }

    /**
     * Builds the east-side portal: same 5-wide/8-tall tapered silhouette
     * as the west doorway, but filled with alternating dark blue / dark
     * purple stained glass instead of carved to air, and backed one
     * block further out by the border's own stone so it doesn't read as
     * a hole into the void when viewed from outside the wall.
     */
    private static void buildPortalFrame(World world, int hubFloorY, int hubCenterX, int hubCenterZ) {
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        int glassX = hubCenterX + maxRadius - 1; // sits within the wall band, one layer shy of the outer face
        int backerX = hubCenterX + maxRadius + 1; // one block beyond the wall's outer face

        Random glassRng = new Random(0xB012741L ^ ((long) hubCenterX << 20 | (hubCenterZ & 0xFFFFF)));
        for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
            boolean core = Math.abs(dz) <= DOORWAY_TOP_HALF_WIDTH;
            int rows = core ? DOORWAY_TOTAL_HEIGHT : DOORWAY_BOTTOM_ROWS;
            for (int row = 1; row <= rows; row++) {
                Material glass = glassRng.nextBoolean() ? PORTAL_COLOR_A : PORTAL_COLOR_B;
                world.getBlockAt(glassX, hubFloorY + row, hubCenterZ + dz).setType(glass, false);
            }
        }
        // Backer wall: solid border material capping the portal from
        // outside, and also patch the two side columns immediately
        // adjacent to the glass (already border stone from the main
        // ring pass, left untouched) so the frame reads as one
        // continuous wall around the glass rather than a raw cutout.
        for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
            for (int row = 1; row <= DOORWAY_TOTAL_HEIGHT; row++) {
                world.getBlockAt(backerX, hubFloorY + row, hubCenterZ + dz).setType(BORDER_MATERIAL, false);
            }
        }
        // Ground lip on the interior (walkable) side, matching the doorway's.
        for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
            world.getBlockAt(hubCenterX + AREA_RADIUS - 1, hubFloorY, hubCenterZ + dz).setType(GROUND_SURFACE, false);
        }
    }

    private interface CellSetter {
        void set(int[] yz);
    }

    /** Shared iteration over the tapered doorway silhouette's (y, z) cells, relative to hubCenterZ. */
    private static void forEachDoorwayCell(CellSetter setter, int hubFloorY, int hubCenterZ) {
        for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
            boolean core = Math.abs(dz) <= DOORWAY_TOP_HALF_WIDTH;
            int rows = core ? DOORWAY_TOTAL_HEIGHT : DOORWAY_BOTTOM_ROWS;
            for (int row = 1; row <= rows; row++) {
                setter.set(new int[]{hubFloorY + row, hubCenterZ + dz});
            }
        }
    }

    /**
     * Coordinates (world x/y/z) of every glass block placed by
     * buildPortalFrame(), for AreaZeroPortalAnimator to flicker. Pure
     * math, recomputed rather than stored, same pattern as
     * gatewayPoint()/portalCorner1()/portalCorner2() below.
     */
    public static List<int[]> portalGlassCoordinates(FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubFloorY = hubFloorY(floorBounds);
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        int glassX = hubCenterX + maxRadius - 1;

        List<int[]> coords = new ArrayList<>();
        for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
            boolean core = Math.abs(dz) <= DOORWAY_TOP_HALF_WIDTH;
            int rows = core ? DOORWAY_TOTAL_HEIGHT : DOORWAY_BOTTOM_ROWS;
            for (int row = 1; row <= rows; row++) {
                coords.add(new int[]{glassX, hubFloorY + row, hubCenterZ + dz});
            }
        }
        return coords;
    }

    /**
     * Places MIN_TREES..MAX_TREES custom multi-limb trees at random
     * valid clearing spots - avoiding the border ring, both doorway
     * mouths, and each other. Each tree is a trunk (4-6 tall) with 2-4
     * diagonal limbs branching partway up, each limb capped with a
     * small leaf cluster, plus a leaf cluster on the trunk's own top -
     * distinctly NOT a vanilla single-trunk-plus-canopy shape.
     */
    private static void placeTrees(World world, FloorBounds floorBounds, int hubFloorY,
                                    int hubCenterX, int hubCenterZ, Random rng) {
        int treeCount = MIN_TREES + rng.nextInt(MAX_TREES - MIN_TREES + 1);
        List<int[]> placedAt = new ArrayList<>();

        int attempts = 0;
        int maxAttempts = treeCount * 20;
        while (placedAt.size() < treeCount && attempts < maxAttempts) {
            attempts++;
            double angle = rng.nextDouble() * Math.PI * 2;
            // Keep trees well clear of the wall ring and the two doorway
            // approach lanes (a band around hubCenterZ on both the west
            // and east extremes), so nothing blocks either opening.
            double r = 4 + rng.nextDouble() * (AREA_RADIUS - 8);
            int x = (int) Math.round(Math.cos(angle) * r);
            int z = (int) Math.round(Math.sin(angle) * r);

            boolean nearDoorwayLane = Math.abs(z) <= DOORWAY_BOTTOM_HALF_WIDTH + 2
                    && Math.abs(x) >= AREA_RADIUS - 8;
            if (nearDoorwayLane) {
                continue;
            }

            boolean tooClose = false;
            for (int[] other : placedAt) {
                if (Math.hypot(x - other[0], z - other[1]) < 4) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) {
                continue;
            }

            placedAt.add(new int[]{x, z});
            int worldX = hubCenterX + x;
            int worldZ = hubCenterZ + z;
            int groundY = hubFloorY + terrainLevel(worldX, worldZ);
            buildTree(world, worldX, groundY + 1, worldZ, rng);
        }
    }

    /** Builds one custom multi-limb tree: a trunk plus several branching limbs, each capped with leaves. */
    private static void buildTree(World world, int baseX, int baseY, int baseZ, Random rng) {
        int trunkHeight = 4 + rng.nextInt(3); // 4-6
        for (int dy = 0; dy < trunkHeight; dy++) {
            world.getBlockAt(baseX, baseY + dy, baseZ).setType(TRUNK_MATERIAL, false);
        }
        leafCluster(world, baseX, baseY + trunkHeight, baseZ, 2);

        int limbCount = 2 + rng.nextInt(3); // 2-4
        for (int i = 0; i < limbCount; i++) {
            int startY = baseY + Math.max(1, (int) (trunkHeight * (0.4 + rng.nextDouble() * 0.5)));
            double angle = rng.nextDouble() * Math.PI * 2;
            int limbLength = 2 + rng.nextInt(3); // 2-4

            double x = baseX;
            double y = startY;
            double z = baseZ;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double dyStep = 0.6; // limbs angle upward as they extend

            for (int step = 0; step < limbLength; step++) {
                x += dx;
                y += dyStep;
                z += dz;
                world.getBlockAt((int) Math.round(x), (int) Math.round(y), (int) Math.round(z))
                        .setType(TRUNK_MATERIAL, false);
            }
            leafCluster(world, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z), 2);
        }
    }

    /** Rough small leaf sphere of the given radius around a point, skipping the exact center (already a log). */
    private static void leafCluster(World world, int centerX, int centerY, int centerZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > radius + 0.3) continue;
                    org.bukkit.block.Block block = world.getBlockAt(centerX + dx, centerY + dy, centerZ + dz);
                    if (block.getType() == Material.AIR) {
                        block.setType(LEAF_MATERIAL, false);
                    }
                }
            }
        }
    }

    /**
     * Spawns MIN_SLIMES..MAX_SLIMES level 1-2 slimes into Area Zero's
     * clearing. Called separately from buildHub() (it needs a
     * JavaPlugin + MobLevelApplicator that aren't available at the
     * point buildHub() first runs during plugin startup) but on the
     * exact same call sites/timing - once at startup, once per weekly
     * reset. Idempotent: clears any Area-Zero-tagged slimes already in
     * the clearing first, so repeated calls (e.g. a restart without an
     * intervening reset) don't stack up duplicates.
     */
    public static void spawnSlimes(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ,
                                    org.bukkit.plugin.java.JavaPlugin plugin,
                                    com.skyblock.dungeon.combat.MobLevelApplicator levelApplicator,
                                    Random random) {
        int hubFloorY = hubFloorY(floorBounds);
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);

        org.bukkit.NamespacedKey tagKey = new org.bukkit.NamespacedKey(plugin, "area_zero_slime");

        // Clear any previously spawned Area Zero slimes before spawning
        // fresh ones, so a restart (buildHub/spawnSlimes called again
        // without a world-deleting reset in between) doesn't pile up
        // duplicates over time.
        double clearRadius = AREA_RADIUS + BORDER_THICKNESS + 2;
        for (org.bukkit.entity.Entity entity : world.getNearbyEntities(
                new Location(world, hubCenterX, hubFloorY + 4, hubCenterZ), clearRadius, 20, clearRadius)) {
            if (entity instanceof org.bukkit.entity.Slime
                    && entity.getPersistentDataContainer().has(tagKey, org.bukkit.persistence.PersistentDataType.BYTE)) {
                entity.remove();
            }
        }

        int slimeCount = MIN_SLIMES + random.nextInt(MAX_SLIMES - MIN_SLIMES + 1);
        int attempts = 0;
        int maxAttempts = slimeCount * 20;
        int spawned = 0;
        while (spawned < slimeCount && attempts < maxAttempts) {
            attempts++;
            double angle = random.nextDouble() * Math.PI * 2;
            double r = 3 + random.nextDouble() * (AREA_RADIUS - 6);
            int x = (int) Math.round(Math.cos(angle) * r);
            int z = (int) Math.round(Math.sin(angle) * r);

            boolean nearDoorwayLane = Math.abs(z) <= DOORWAY_BOTTOM_HALF_WIDTH + 2
                    && Math.abs(x) >= AREA_RADIUS - 8;
            if (nearDoorwayLane) {
                continue;
            }

            int worldX = hubCenterX + x;
            int worldZ = hubCenterZ + z;
            int groundY = hubFloorY + terrainLevel(worldX, worldZ);
            Location spawnLoc = new Location(world, worldX + 0.5, groundY + 1, worldZ + 0.5);

            if (!(world.spawnEntity(spawnLoc, org.bukkit.entity.EntityType.SLIME) instanceof org.bukkit.entity.Slime slime)) {
                continue;
            }
            slime.setSize(1); // small - reads as "level 1-2", not a hulking dungeon slime
            slime.getPersistentDataContainer().set(tagKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);

            int level = 1 + random.nextInt(2); // level 1 or 2 only, per design
            levelApplicator.applyLevel(slime, level);
            spawned++;
        }
    }

    private static final int MIN_SLIMES = 4;
    private static final int MAX_SLIMES = 7;

    /** The location players should be teleported to on /dungeon - the center of Area Zero's clearing. */
    public static Location entranceLocation(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        int groundY = hubFloorY(floorBounds) + terrainLevel(hubCenterX, hubCenterZ);
        return new Location(world, hubCenterX + 0.5, groundY + 1, hubCenterZ + 0.5);
    }

    /** Corner 1 of the portal's trigger volume - a small pocket just inside the east glass wall. */
    public static Location portalCorner1(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        return new Location(world, hubCenterX + AREA_RADIUS - 4, hubFloorY(floorBounds) + 1,
                hubCenterZ - DOORWAY_TOP_HALF_WIDTH);
    }

    /** Corner 2 of the portal's trigger volume - a small pocket just inside the east glass wall. */
    public static Location portalCorner2(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        return new Location(world, hubCenterX + AREA_RADIUS - 1, hubFloorY(floorBounds) + 3,
                hubCenterZ + DOORWAY_TOP_HALF_WIDTH);
    }

    /**
     * The exact XZ of the west doorway breach - i.e. where Area Zero
     * actually opens into Floor 1's cave system. Floor 1's
     * DungeonRoomPlanner/DungeonGraphPlanner MUST treat this (not some
     * independently-guessed point) as the floor's entrance location, or
     * the planned graph's guaranteed ENTRANCE room ends up nowhere near
     * where players actually walk in from.
     *
     * Returned as a double[]{x, z} rather than a Location since callers
     * need this before the graph (and often before any World reference
     * they want to use here) exists - it's pure floor-plan math, no
     * Bukkit World needed.
     */
    public static double[] gatewayPoint(int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        // One block past the outer face of the west wall, in the cave
        // space the breach actually opens into, rather than sitting on
        // the wall itself.
        int gatewayX = hubCenterX - (AREA_RADIUS + BORDER_THICKNESS) - 1;
        return new double[]{gatewayX, hubCenterZ};
    }
}
