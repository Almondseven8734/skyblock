package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;
import java.util.Random;

/**
 * Builds the physical Floor 0 structure: a small fixed, non-generating
 * entrance/hub room with a portal block volume back to spawn.
 *
 * This is plain block placement, not part of the procedural generator -
 * Floor 0 never generates or changes, per design ("Floor 0 isn't a
 * vertical floor, it's an entrance room"). Call buildHub() once, right
 * after the dungeon world is first created (it's idempotent-safe to
 * call again, it'll just overwrite the same blocks identically).
 *
 * The hub is positioned FLOOR_0_TO_FLOOR_1_OFFSET blocks east
 * (+X) of Floor 1's origin point, so walking out of the hub through its
 * west wall heads straight toward Floor 1's origin. All coordinates are
 * derived from Floor 1's origin passed into buildHub() rather than
 * hardcoded, so moving Floor 1's origin automatically moves the hub
 * with it.
 *
 * Vertically, the hub's floor sits at the SAME Y-band as Floor 1's own
 * walkable floor (FloorBounds.walkableFloorY(1)), not an arbitrary fixed
 * Y - it's derived from FloorBounds so the entrance always lands you
 * adjacent to Floor 1, never accidentally lined up with some other
 * floor deep in the stack.
 */
public final class DungeonHubBuilder {

    private static final int HUB_RADIUS_X = 8;
    private static final int HUB_RADIUS_Z = 8;
    private static final int HUB_HEIGHT = 5;

    private DungeonHubBuilder() {
    }

    /** Hub's floor Y - the same walkable band as Floor 1, so the two are vertically adjacent. */
    private static int hubFloorY(FloorBounds floorBounds) {
        return floorBounds.walkableFloorY(1);
    }

    /** Hub center sits FLOOR_0_TO_FLOOR_1_OFFSET blocks east (+X) of Floor 1's origin, same Z. */
    private static int hubCenterX(int floor1OriginX) {
        return floor1OriginX + FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET;
    }

    private static int hubCenterZ(int floor1OriginZ) {
        return floor1OriginZ;
    }

    /** Builds the Floor 0 room: floor, walls, ceiling, and a portal block volume on the far wall. */
    public static void buildHub(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        buildHub(world, floorBounds, floor1OriginX, floor1OriginZ, null);
    }

    /**
     * Builds the Floor 0 room: floor, walls, ceiling, a portal block
     * volume on the far wall, and a themed gateway breach on the west
     * wall connecting into the dungeon caves.
     *
     * @param floor1Theme Floor 1's theme, used to carve the gateway out
     *                     of the same block palette as the caves it
     *                     opens into (rather than a plain stone-brick
     *                     doorway) so it visually reads as a hole
     *                     breaking through into the dungeon, not just
     *                     an interior door. Null falls back to plain
     *                     stone bricks/cobblestone for the gateway if a
     *                     theme genuinely isn't available yet.
     */
    public static void buildHub(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ,
                                 FloorTheme floor1Theme) {
        int hubFloorY = hubFloorY(floorBounds);
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);

        // Floor.
        for (int x = -HUB_RADIUS_X; x <= HUB_RADIUS_X; x++) {
            for (int z = -HUB_RADIUS_Z; z <= HUB_RADIUS_Z; z++) {
                world.getBlockAt(hubCenterX + x, hubFloorY, hubCenterZ + z).setType(Material.SMOOTH_STONE);
            }
        }

        // Hollow interior air + walls/ceiling.
        for (int x = -HUB_RADIUS_X; x <= HUB_RADIUS_X; x++) {
            for (int z = -HUB_RADIUS_Z; z <= HUB_RADIUS_Z; z++) {
                boolean edge = Math.abs(x) == HUB_RADIUS_X || Math.abs(z) == HUB_RADIUS_Z;
                for (int y = 1; y <= HUB_HEIGHT; y++) {
                    Material material = (edge || y == HUB_HEIGHT) ? Material.STONE_BRICKS : Material.AIR;
                    world.getBlockAt(hubCenterX + x, hubFloorY + y, hubCenterZ + z).setType(material);
                }
            }
        }

        // Portal volume: a 3x3 plane on the south wall (-Z side), distinct
        // from the west-wall exit, decorative marker matching
        // DungeonPortalHandler's portalCorner1/2 box.
        int portalX = hubCenterX;
        int portalZBase = hubCenterZ - HUB_RADIUS_Z + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                Material material = (dx == 0) ? Material.GLASS : Material.SEA_LANTERN;
                world.getBlockAt(portalX + dx, hubFloorY + dy, portalZBase).setType(material);
            }
        }

        buildGateway(world, floorBounds, hubFloorY, hubCenterX, hubCenterZ, floor1Theme);
    }

    /**
     * Carves the west-wall gateway connecting Floor 0's hub to the
     * dungeon caves. Previously this was a plain 1-wide, 2-tall doorway
     * cut cleanly into the stone-brick wall - functional, but it read
     * as an interior door rather than a breach into a cave system, and
     * its flat rectangular cut didn't match the organic cave shapes on
     * the other side at all.
     *
     * This now carves a much larger (5-wide, 4-tall) ragged opening -
     * jittered per-column height/width via a fixed-seed Random so it's
     * deterministic and idempotent across repeated buildHub() calls,
     * not full rectangular - and re-themes the blocks immediately
     * around the opening (jambs, lintel, floor lip) using Floor 1's own
     * primary/accent palette instead of the hub's plain stone bricks,
     * so the transition from "built room" to "natural cave" is visually
     * continuous rather than an abrupt material swap right at the
     * doorway.
     */
    private static void buildGateway(World world, FloorBounds floorBounds, int hubFloorY,
                                      int hubCenterX, int hubCenterZ, FloorTheme floor1Theme) {
        List<Material> primary = (floor1Theme != null) ? floor1Theme.getPrimaryBlocks()
                : List.of(Material.STONE, Material.COBBLESTONE);
        List<Material> accent = (floor1Theme != null) ? floor1Theme.getAccentBlocks()
                : List.of(Material.MOSSY_COBBLESTONE);

        // Deterministic per-column jitter - same seed every call so
        // repeated buildHub() invocations (idempotent by design, see
        // class docs) always carve an identical gateway shape rather
        // than a different random one each time.
        Random jitter = new Random(0xCAFED00Dl ^ ((long) hubCenterX << 32 | (hubCenterZ & 0xFFFFFFFFL)));

        int gatewayX = hubCenterX - HUB_RADIUS_X;
        int halfWidth = 2; // 5 blocks wide (centerZ-2..centerZ+2)
        int baseHeight = 4;

        for (int dz = -halfWidth - 1; dz <= halfWidth + 1; dz++) {
            int z = hubCenterZ + dz;
            boolean insideCore = Math.abs(dz) <= halfWidth;

            // Ragged edge columns (one block wider than the core opening
            // on each side) only carve partway up, giving the opening an
            // uneven, broken-through silhouette instead of a clean
            // rectangle.
            int columnHeight = insideCore
                    ? baseHeight + jitter.nextInt(2)               // 4-5 tall through the core
                    : 1 + jitter.nextInt(2);                       // 1-2 tall on the ragged fringe

            for (int y = 1; y <= columnHeight; y++) {
                world.getBlockAt(gatewayX, hubFloorY + y, z).setType(Material.AIR, false);
            }

            // Re-theme the jamb blocks directly bordering the opening
            // (immediately above the carved column, and the floor lip)
            // with Floor 1's own palette so the hub-to-cave transition
            // reads as one continuous material instead of a hard seam
            // between stone bricks and cave stone.
            if (insideCore) {
                Material jambMaterial = (jitter.nextDouble() < 0.3) ? pick(accent, jitter) : pick(primary, jitter);
                world.getBlockAt(gatewayX, hubFloorY + columnHeight + 1, z).setType(jambMaterial, false);
                world.getBlockAt(gatewayX, hubFloorY, z).setType(pick(primary, jitter), false);
                // One block further out (already outside the hub's own
                // wall ring) also gets re-themed so the floor material
                // itself transitions before the cave's own generation
                // pass ever reaches this column.
                world.getBlockAt(gatewayX - 1, hubFloorY, z).setType(pick(primary, jitter), false);
            }
        }
    }

    private static Material pick(List<Material> list, Random random) {
        if (list.isEmpty()) return Material.STONE;
        return list.get(random.nextInt(list.size()));
    }

    /** The location players should be teleported to on /dungeon - just inside the hub room. */
    public static Location entranceLocation(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        return new Location(world, hubCenterX(floor1OriginX), hubFloorY(floorBounds) + 1, hubCenterZ(floor1OriginZ));
    }

    /** Corner 1 of the portal trigger volume, matching the visual marker built in buildHub(). */
    public static Location portalCorner1(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        return new Location(world, hubCenterX - 1, hubFloorY(floorBounds) + 1, hubCenterZ - HUB_RADIUS_Z + 2);
    }

    /** Corner 2 of the portal trigger volume, matching the visual marker built in buildHub(). */
    public static Location portalCorner2(World world, FloorBounds floorBounds, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        return new Location(world, hubCenterX + 1, hubFloorY(floorBounds) + 3, hubCenterZ - HUB_RADIUS_Z + 1);
    }
}
