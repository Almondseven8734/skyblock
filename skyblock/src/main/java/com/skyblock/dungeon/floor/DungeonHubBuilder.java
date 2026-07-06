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
 * a small, fixed forest clearing enclosed by a circular R100 stone
 * border, hand-placed the same way every time (buildHub() is
 * idempotent-safe to call repeatedly - see class docs on
 * DungeonResetScheduler.setOnWorldRecreated wiring). Terrain height and
 * tree placement ARE re-rolled from a deterministic seed on every call
 * (see class docs below), not persisted - "regenerate fresh every
 * reset" rather than "generate once and stick around."
 *
 * Layout, looking down:
 *   - A circular clearing of radius AREA_RADIUS, walled by a
 *     BORDER_THICKNESS-thick ring of stone. Ground height is a smooth
 *     (bilinear/smoothstep-interpolated) rolling terrain rather than
 *     flat stepped plateaus - see terrainHeight() below.
 *   - A 5-wide, 8-tall doorway breach on the WEST side (-X), opening
 *     directly into Floor 1's cave system - this is the only side that
 *     borders the dungeon, and per design the border wall must NEVER
 *     clip into Floor 1's own generated terrain (see
 *     FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET's docs for the exact
 *     zero-gap/zero-overlap invariant this depends on).
 *   - A matching 5-wide, 8-tall breach on the OPPOSITE (EAST, +X) side:
 *     the approach path through the border ring is carved to air (so
 *     it's actually walkable, not just a single accessible-from-nowhere
 *     glass column), stopping one column shy of the outer face, which
 *     is filled with animated dark blue / dark purple stained glass
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

    /** Radius (blocks) of the walkable forest clearing, per design ("fits inside an R100 stone border"). */
    private static final int AREA_RADIUS = 100;
    /** Thickness of the stone border ring just outside AREA_RADIUS. */
    private static final int BORDER_THICKNESS = 2;
    /** Height of the border wall above the ground band. */
    private static final int WALL_HEIGHT = 10;
    /** Interior air clearance above ground, for tree canopies and open sky-feel. */
    private static final int CLEAR_HEIGHT = 16;

    /** Terrain height variation: ground can sit 0-2 blocks above the base band (i.e. "1-3 blocks of variation"). */
    private static final int TERRAIN_VARIATION_LEVELS = 3;
    /** Spacing (blocks) between terrain-height control points, interpolated smoothly between them - gentle rolling hills rather than 1-block noise. */
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
    private static final Material PATH_MATERIAL_A = Material.COARSE_DIRT;
    private static final Material PATH_MATERIAL_B = Material.GRAVEL;

    /** Path half-width (3 blocks wide total, centered on the route line). */
    private static final int PATH_HALF_WIDTH = 1;
    /** How far (blocks) the path steers away from a tree it comes near. */
    private static final double PATH_TREE_AVOID_RADIUS = 5.0;
    /** Max lateral (Z) deflection allowed per step of X while steering. */
    private static final double PATH_MAX_STEP_DEFLECTION = 0.6;

    // Deliberately sparser than a flat density-scale-up from the old R25
    // clearing would give (that math worked out to 160-256 trees, which
    // read as overcrowded/cluttered across the full R100 clearing) - these
    // large multi-branch trees (see buildTree()) read as "big" individually,
    // so far fewer of them are needed for the clearing to feel forested
    // rather than packed.
    private static final int MIN_TREES = 45;
    private static final int MAX_TREES = 75;

    private DungeonHubBuilder() {
    }

    /**
     * Vertical correction between Area Zero's flat hand-built floor and
     * Floor 1's ENTRANCE room floor as the SDF cave carver actually
     * renders it. FloorBounds.walkableFloorY(1) is only the promised
     * "solid floor cap + SOLID_FLOOR_LAYERS" plane - but since
     * DungeonGraphPlanner's rooms/tunnels (including the guaranteed
     * ENTRANCE room the hub's doorway opens into) are irregular bubbly
     * SDF blobs, not flat-floored boxes, their curved floor sits
     * `domeH`-ish blocks below the blob's vertical center rather than
     * pinned exactly to walkableFloorY. For the ENTRANCE room that
     * lands its actual carved floor about 2 blocks ABOVE the hub's flat
     * ground, leaving a step at the doorway threshold nothing can walk
     * up (confirmed in-world: hub at y302, connecting cave floor at
     * y304). Bumping the hub's own floor up by that same amount lines
     * the two up so the threshold is walkable in both directions.
     */
    private static final int HUB_FLOOR_Y_CORRECTION = 2;

    /** Area Zero's ground Y - matched to Floor 1's actual ENTRANCE room floor (see HUB_FLOOR_Y_CORRECTION). */
    private static int hubFloorY(FloorBounds floorBounds) {
        return floorBounds.walkableFloorY(1) + HUB_FLOOR_Y_CORRECTION;
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
     * @param floor1Theme Floor 1's theme, used for the border wall's
     *                     material palette (see wallMaterial()) so Area
     *                     Zero's wall reads as level-with/matching
     *                     Floor 1's own walls instead of always being
     *                     plain stone regardless of which floor it
     *                     opens into.
     */
    public static void buildHub(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ,
                                 FloorTheme floor1Theme) {
        int hubFloorY = hubFloorY(floorBounds);
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        Material wallMaterial = wallMaterial(floor1Theme);

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
                    buildBorderColumn(world, hubFloorY, worldX, worldZ, wallMaterial);
                }
            }
        }

        carveDoorway(world, hubFloorY, hubCenterX, hubCenterZ);           // west: open to the dungeon
        buildPortalFrame(world, hubFloorY, hubCenterX, hubCenterZ, wallMaterial);      // east: glass portal

        List<int[]> treePositions = placeTrees(world, floorBounds, hubFloorY, hubCenterX, hubCenterZ, rng);
        buildPath(world, hubFloorY, hubCenterX, hubCenterZ, treePositions, rng);
    }

    /** Ground column inside the clearing: rolls a smooth interpolated height, fills it in, clears air above. */
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

    /**
     * Resolves the border wall's material from Floor 1's theme, so Area
     * Zero's wall textures the same as the floor it opens into instead
     * of always being plain stone regardless of theme. Falls back to
     * BORDER_MATERIAL (stone) if no theme is supplied or the theme has
     * no primary blocks configured.
     */
    private static Material wallMaterial(FloorTheme floor1Theme) {
        if (floor1Theme == null || floor1Theme.getPrimaryBlocks().isEmpty()) {
            return BORDER_MATERIAL;
        }
        return floor1Theme.getPrimaryBlocks().get(0);
    }

    /** Border ring column: solid wall from just below ground to WALL_HEIGHT above it, textured with wallMaterial. */
    private static void buildBorderColumn(World world, int hubFloorY, int worldX, int worldZ, Material wallMaterial) {
        for (int y = hubFloorY - 3; y <= hubFloorY + WALL_HEIGHT; y++) {
            world.getBlockAt(worldX, y, worldZ).setType(wallMaterial, false);
        }
        for (int y = hubFloorY + WALL_HEIGHT + 1; y <= hubFloorY + CLEAR_HEIGHT; y++) {
            world.getBlockAt(worldX, y, worldZ).setType(Material.AIR, false);
        }
    }

    /**
     * Deterministic, continuous-valued height at one of the coarse
     * TERRAIN_CELL_SIZE-spaced grid corners, in [0, TERRAIN_VARIATION_LEVELS-1]
     * (0-2, i.e. up to 2 blocks of relief across the clearing) - a
     * coordinate hash rather than a stored seed, so it's naturally
     * idempotent across rebuilds without needing to persist a heightmap.
     */
    private static double cornerHeight(int cellX, int cellZ) {
        long seed = 0x9E3779B97F4A7C15L
                ^ ((long) cellX * 0xBF58476D1CE4E5B9L)
                ^ ((long) cellZ * 0x94D049BB133111EBL);
        Random cellRng = new Random(seed);
        return cellRng.nextDouble() * (TERRAIN_VARIATION_LEVELS - 1);
    }

    /**
     * Smooth terrain height for a world column: bilinearly interpolates
     * between the four surrounding TERRAIN_CELL_SIZE-spaced grid corner
     * heights, with a smoothstep easing curve on each axis so the
     * result reads as gently rolling hills rather than the old flat,
     * hard-edged TERRAIN_CELL_SIZE x TERRAIN_CELL_SIZE plateaus.
     */
    private static double terrainHeight(int worldX, int worldZ) {
        double gx = (double) worldX / TERRAIN_CELL_SIZE;
        double gz = (double) worldZ / TERRAIN_CELL_SIZE;
        int x0 = (int) Math.floor(gx);
        int z0 = (int) Math.floor(gz);
        int x1 = x0 + 1;
        int z1 = z0 + 1;

        double tx = gx - x0;
        double tz = gz - z0;
        double sx = tx * tx * (3 - 2 * tx); // smoothstep easing
        double sz = tz * tz * (3 - 2 * tz);

        double h00 = cornerHeight(x0, z0);
        double h10 = cornerHeight(x1, z0);
        double h01 = cornerHeight(x0, z1);
        double h11 = cornerHeight(x1, z1);

        double top = h00 + (h10 - h00) * sx;
        double bottom = h01 + (h11 - h01) * sx;
        return top + (bottom - top) * sz;
    }

    /**
     * Integer block level a column's surface should sit at - the
     * smooth terrainHeight() rounded to the nearest block, since the
     * world is still voxel-quantized even though the underlying height
     * field driving it is continuous. Neighboring columns now only
     * ever differ by ~1 block (a walkable, staircase-like slope)
     * instead of jumping between whole flat plateaus.
     */
    private static int terrainLevel(int worldX, int worldZ) {
        return (int) Math.round(terrainHeight(worldX, worldZ));
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
     *
     * The border ring is circular, so a flat single-X-column breach
     * doesn't line up with the ring's true curvature: at the flanking
     * Z offsets of the 5-wide breach, the ring is thicker (measured
     * along X) than it is at the center, which previously left a
     * leftover, uncarved layer of solid border stone between the
     * clearing and the glass everywhere except the dead center - i.e.
     * only one block of the whole breach was actually walkable. Fixed
     * by carving the ENTIRE approach - every column between the
     * clearing's edge and the glass itself - to air first, in the same
     * tapered doorway shape, before placing the glass.
     */
    private static void buildPortalFrame(World world, int hubFloorY, int hubCenterX, int hubCenterZ, Material wallMaterial) {
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        int glassX = hubCenterX + maxRadius - 1; // sits within the wall band, one layer shy of the outer face
        int backerX = hubCenterX + maxRadius + 1; // one block beyond the wall's outer face

        // Approach path: carve every border column between the
        // clearing's edge (AREA_RADIUS - 1) and the glass (exclusive)
        // to air, in the same tapered silhouette as the doorway, so the
        // whole breach is walkable up to the glass regardless of how
        // the circular ring's thickness varies across the breach's width.
        for (int r = AREA_RADIUS - 1; r < glassX - hubCenterX; r++) {
            int worldX = hubCenterX + r;
            forEachDoorwayCell(cell -> world.getBlockAt(worldX, cell[0], cell[1]).setType(Material.AIR, false),
                    hubFloorY, hubCenterZ);
        }

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
                world.getBlockAt(backerX, hubFloorY + row, hubCenterZ + dz).setType(wallMaterial, false);
            }
        }
        // Ground lip across the WHOLE approach (interior clearing edge
        // through to the foot of the glass), not just a single column,
        // so the newly-walkable breach reads as one continuous floor
        // instead of stopping dead at the clearing's edge.
        for (int r = AREA_RADIUS - 1; r < glassX - hubCenterX; r++) {
            int worldX = hubCenterX + r;
            for (int dz = -DOORWAY_BOTTOM_HALF_WIDTH; dz <= DOORWAY_BOTTOM_HALF_WIDTH; dz++) {
                world.getBlockAt(worldX, hubFloorY, hubCenterZ + dz).setType(GROUND_SURFACE, false);
            }
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
     * Places MIN_TREES..MAX_TREES large custom trees at random valid
     * clearing spots - avoiding the border ring, both doorway mouths,
     * and each other. Each tree has buttress roots flaring from its
     * base, a tall trunk (8-14, thick 2x2 near the ground) and 3-5
     * large sweeping branches, each capped with a big leaf cluster,
     * plus a leaf cluster on the trunk's own top - distinctly NOT a
     * vanilla single-log-trunk-plus-canopy shape.
     */
    private static List<int[]> placeTrees(World world, FloorBounds floorBounds, int hubFloorY,
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
                if (Math.hypot(x - other[0], z - other[1]) < 7) {
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
        return placedAt;
    }

    /**
     * Builds one large custom tree: buttress roots flaring out from the
     * base, a tall thick trunk (double-width near the ground, tapering
     * to a single column higher up), and several large, thick, multi-
     * segment branches capped with big leaf clusters - explicitly NOT
     * vanilla scale/shape (thin single-log trunk, short stubby limbs).
     */
    private static void buildTree(World world, int baseX, int baseY, int baseZ, Random rng) {
        int trunkHeight = 8 + rng.nextInt(7); // 8-14, tall

        // Root flares: short log stubs radiating outward along the
        // ground from the base before the trunk rises, reading as
        // buttress roots rather than a tree just planted flat on top.
        int rootCount = 4 + rng.nextInt(3); // 4-6
        for (int i = 0; i < rootCount; i++) {
            double angle = (Math.PI * 2 * i / rootCount) + (rng.nextDouble() - 0.5) * 0.6;
            int rootLength = 2 + rng.nextInt(3); // 2-4
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double x = baseX;
            double z = baseZ;
            for (int step = 0; step < rootLength; step++) {
                x += dx;
                z += dz;
                // Roots dip slightly as they extend, then the ground
                // itself resumes - only touch the base and one layer
                // below so they read as gnarled roots, not a flat spoke.
                int ry = baseY - (step >= rootLength - 1 ? 1 : 0);
                world.getBlockAt((int) Math.round(x), ry, (int) Math.round(z)).setType(TRUNK_MATERIAL, false);
            }
        }

        // Trunk: a thick 2x2 base tapering to a single column for the
        // upper ~40% of the trunk's height, so it reads as a large,
        // heavy tree rather than a single-block-wide vanilla trunk.
        int thickUntil = (int) Math.round(trunkHeight * 0.6);
        for (int dy = 0; dy < trunkHeight; dy++) {
            world.getBlockAt(baseX, baseY + dy, baseZ).setType(TRUNK_MATERIAL, false);
            if (dy < thickUntil) {
                world.getBlockAt(baseX + 1, baseY + dy, baseZ).setType(TRUNK_MATERIAL, false);
                world.getBlockAt(baseX, baseY + dy, baseZ + 1).setType(TRUNK_MATERIAL, false);
                world.getBlockAt(baseX + 1, baseY + dy, baseZ + 1).setType(TRUNK_MATERIAL, false);
            }
        }
        leafCluster(world, baseX, baseY + trunkHeight, baseZ, 3);

        // Large branches: thick (2-log-wide cross-section), long,
        // sweeping limbs starting partway up the trunk, each capped
        // with a big leaf cluster.
        int limbCount = 3 + rng.nextInt(3); // 3-5
        for (int i = 0; i < limbCount; i++) {
            int startY = baseY + Math.max(2, (int) (trunkHeight * (0.35 + rng.nextDouble() * 0.4)));
            double angle = rng.nextDouble() * Math.PI * 2;
            int limbLength = 5 + rng.nextInt(5); // 5-9, large sweeping branches

            double x = baseX;
            double y = startY;
            double z = baseZ;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            // Perpendicular offset so the branch's second log (the
            // "thickness") sits beside the path rather than on top of it.
            double px = -Math.sin(angle);
            double pz = Math.cos(angle);
            double dyStep = 0.5; // branches angle upward as they extend

            for (int step = 0; step < limbLength; step++) {
                x += dx;
                y += dyStep;
                z += dz;
                int bx = (int) Math.round(x);
                int by = (int) Math.round(y);
                int bz = (int) Math.round(z);
                world.getBlockAt(bx, by, bz).setType(TRUNK_MATERIAL, false);
                // Thicken the first two-thirds of the branch so it
                // reads as a heavy limb, tapering to a single log near the tip.
                if (step < limbLength * 2 / 3) {
                    world.getBlockAt(bx + (int) Math.round(px), by, bz + (int) Math.round(pz))
                            .setType(TRUNK_MATERIAL, false);
                }
            }
            leafCluster(world, (int) Math.round(x), (int) Math.round(y), (int) Math.round(z), 3);
        }
    }

    /**
     * Lays a coarse dirt / gravel path along the ground from the west
     * doorway (into the dungeon) to the east portal approach, steering
     * around any obstacle trees rather than cutting straight through
     * them. Built after placeTrees() so tree positions are known.
     *
     * Algorithm: walk the route one X column at a time from the west
     * threshold to the east threshold, tracking a current Z offset from
     * hubCenterZ. At each column, any nearby tree base pushes the route
     * away from it (a simple repulsion), gently pulled back toward
     * hubCenterZ (dz = 0) when clear of obstacles so the path doesn't
     * permanently wander once past them. The path itself is
     * PATH_HALF_WIDTH*2+1 blocks wide, laid as the ground surface
     * material (alternating coarse dirt / gravel) at the correct
     * terrain height for each column, and skips individual cells that
     * would land exactly on a tree's own base column so it never
     * paves over a trunk even if a tree ends up right at the route's edge.
     */
    private static void buildPath(World world, int hubFloorY, int hubCenterX, int hubCenterZ,
                                   List<int[]> treePositions, Random rng) {
        int startX = -(AREA_RADIUS - 1); // west doorway threshold (local coords)
        int endX = AREA_RADIUS - 1;      // east portal approach threshold (local coords)

        double z = 0;
        Random materialRng = new Random(0x9A7D1E5L ^ ((long) hubCenterX << 16 | (hubCenterZ & 0xFFFF)));

        for (int x = startX; x <= endX; x++) {
            // Repulsion from nearby trees: only trees within
            // PATH_TREE_AVOID_RADIUS of the route's current position
            // (in X) exert any push, so distant trees don't affect it.
            double push = 0;
            for (int[] tree : treePositions) {
                double dx = x - tree[0];
                double dz = z - tree[1];
                double dist = Math.hypot(dx, dz);
                if (dist < PATH_TREE_AVOID_RADIUS && dist > 0.001) {
                    push += (dz / dist) * (PATH_TREE_AVOID_RADIUS - dist) / PATH_TREE_AVOID_RADIUS;
                }
            }
            // Gentle pull back toward the centerline so the path
            // straightens out again once it's clear of obstacles,
            // instead of permanently drifting off to one side.
            double pullBack = -z * 0.05;
            double deflection = clamp(push + pullBack, -PATH_MAX_STEP_DEFLECTION, PATH_MAX_STEP_DEFLECTION);
            z += deflection;
            // Keep the path within the clearing, well clear of the border ring.
            z = clamp(z, -(AREA_RADIUS - 6), AREA_RADIUS - 6);

            int centerZ = (int) Math.round(z);
            for (int dz = -PATH_HALF_WIDTH; dz <= PATH_HALF_WIDTH; dz++) {
                int localZ = centerZ + dz;
                if (isTreeBase(treePositions, x, localZ)) {
                    continue; // never pave directly over a tree's own trunk column
                }
                int worldX = hubCenterX + x;
                int worldZ = hubCenterZ + localZ;
                if (Math.hypot(x, localZ) > AREA_RADIUS - 2) {
                    continue; // stay inside the clearing, don't touch the border ring
                }
                int groundY = hubFloorY + terrainLevel(worldX, worldZ);
                Material pathBlock = materialRng.nextInt(3) == 0 ? PATH_MATERIAL_B : PATH_MATERIAL_A;
                world.getBlockAt(worldX, groundY, worldZ).setType(pathBlock, false);
            }
        }
    }

    private static boolean isTreeBase(List<int[]> treePositions, int x, int z) {
        for (int[] tree : treePositions) {
            if (tree[0] == x && tree[1] == z) {
                return true;
            }
        }
        return false;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
     * Spawns MIN_SLIMES..MAX_SLIMES level 1-2 medium slimes into Area Zero's
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
            slime.setSize(2); // medium (per design) - was 1 (small)
            slime.getPersistentDataContainer().set(tagKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);

            int level = 1 + random.nextInt(2); // level 1 or 2 only, per design
            levelApplicator.applyLevel(slime, level);
            spawned++;
        }
    }

    // 10x the original 4-7 range, per design change to make Area Zero
    // feel busier with slimes.
    private static final int MIN_SLIMES = 40;
    private static final int MAX_SLIMES = 70;

    /** The location players should be teleported to on /dungeon - the center of Area Zero's clearing. */
    public static Location entranceLocation(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        int groundY = hubFloorY(floorBounds) + terrainLevel(hubCenterX, hubCenterZ);
        return new Location(world, hubCenterX + 0.5, groundY + 1, hubCenterZ + 0.5);
    }

    /**
     * Corner 1 of the portal's trigger volume. DungeonPortalHandler only
     * supports a single axis-aligned box, so this is deliberately
     * restricted to the CORE (DOORWAY_TOP_HALF_WIDTH-wide) columns of
     * the glass, where every row 1..DOORWAY_TOTAL_HEIGHT is glass - a
     * true rectangular subset of the actual glass volume, rather than
     * an approximate box that could trigger from open air. The wider
     * flanking columns (dz = +-2) only reach DOORWAY_BOTTOM_ROWS tall,
     * so a rectangular box spanning the full 5-wide breach could not be
     * built without including non-glass cells at its top corners -
     * this narrower box is glass everywhere within it, so the player is
     * only ever teleported while genuinely standing inside the glass.
     */
    public static Location portalCorner1(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        int glassX = hubCenterX + maxRadius - 1;
        return new Location(world, glassX, hubFloorY(floorBounds) + 1,
                hubCenterZ - DOORWAY_TOP_HALF_WIDTH);
    }

    /** Corner 2 of the portal's trigger volume - see portalCorner1's docs for why this stays within the glass core. */
    public static Location portalCorner2(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        int maxRadius = AREA_RADIUS + BORDER_THICKNESS;
        int glassX = hubCenterX + maxRadius - 1;
        // +1 on X/Z so the box's max edge reaches the far face of the
        // glassX/+half-width block column rather than stopping at its
        // near face, and the top of DOORWAY_TOTAL_HEIGHT's block rather
        // than its bottom.
        return new Location(world, glassX + 1, hubFloorY(floorBounds) + 1 + DOORWAY_TOTAL_HEIGHT,
                hubCenterZ + DOORWAY_TOP_HALF_WIDTH + 1);
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
    public static int hubWestWallOuterFaceX(int floor1OriginX) {
        int hubCenterX = hubCenterX(floor1OriginX);
        return hubCenterX - (AREA_RADIUS + BORDER_THICKNESS);
    }

    public static int doorwayHalfWidth() {
        return DOORWAY_BOTTOM_HALF_WIDTH;
    }

    public static int doorwayHeight() {
        return DOORWAY_TOTAL_HEIGHT;
    }

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
