package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Chunk-based cave noise carver. Replaces the old room/graph planner
 * with organic, noise-driven cave generation — think Minecraft cave
 * networks carved into the stone buffer layer.
 *
 * When planAndCarveNear() is called (by a player frontier or buffer
 * room), it determines which 16x16 chunk columns are within range and
 * carves any that haven't been carved yet using 3D value noise:
 *   - Noise below CAVE_THRESHOLD → air (carved cave space)
 *   - Otherwise → stone (or the floor's theme block on the floor layer)
 *   - Bottom 2 Y-layers of the floor band → always solid (walkable floor)
 *   - Top 1 Y-layer → always solid (ceiling, never exposed void)
 *
 * The result is an interconnected cave network that feels continuous
 * because coherent noise produces consistent values across chunk
 * boundaries — no seam carving needed.
 *
 * The RoomGraph is still maintained for boss room / buffer room
 * registration and staircase validation (those systems need to know
 * "is there carvedspace at XZ"), but it no longer drives the visual
 * shape of the dungeon.
 *
 * API surface is identical to the old planner so DungeonFloorManager
 * and all callers require no changes.
 */
public final class DungeonRoomPlanner {

    /** Chunk columns within this many blocks of a frontier get carved. */
    private static final int CARVE_RADIUS = 48;

    /**
     * Cave threshold: noise values below this are carved to air.
     * 0.44 gives roughly 35-40% of the volume as open space —
     * wide enough for comfortable movement, dense enough to feel like
     * a solid mountain with caves rather than open void.
     */
    private static final double CAVE_THRESHOLD = 0.44;

    /** Noise frequency along XZ — lower = larger, wider cave passages. */
    private static final double FREQ_XZ = 0.048;
    /** Noise frequency along Y — slightly higher than XZ compresses vertical range. */
    private static final double FREQ_Y  = 0.075;

    /** Floor Y-layers kept solid as walkable ground (from floorBottomY upward). */
    private static final int SOLID_FLOOR_LAYERS = FloorBounds.SOLID_FLOOR_LAYERS;
    /** Ceiling Y-layers kept solid (from floorTopY downward). */
    private static final int SOLID_CEIL_LAYERS  = FloorBounds.SOLID_CEIL_LAYERS;

    @FunctionalInterface
    public interface RoomCarveListener {
        void onRoomCarved(World world, int floorNumber, DungeonRoom room);
    }

    private final RoomGraph graph;
    private final FloorBounds floorBounds;
    private final int floorNumber;
    private final double originX;
    private final double originZ;
    private final FloorTheme theme;
    private final Logger logger;
    private final Random random;
    private final CaveNoise noise;
    private final DungeonCarveScheduler carveScheduler;

    /**
     * Chunk keys (chunkX<<32|chunkZ long) that have been carved OR are
     * already queued to be carved. Gating on enqueue (not just on
     * actual carve) is what stops onPlayerFrontier - which fires on
     * every whole-block move - from re-enqueuing the same chunk
     * hundreds of times while it's still sitting in the scheduler's
     * queue waiting its turn.
     */
    private final Set<Long> carvedChunks = ConcurrentHashMap.newKeySet();

    public DungeonRoomPlanner(RoomGraph graph, FloorBounds floorBounds, int floorNumber,
                               double originX, double originZ, FloorTheme theme,
                               Logger logger, Random random, DungeonCarveScheduler carveScheduler) {
        this.graph       = graph;
        this.floorBounds = floorBounds;
        this.floorNumber = floorNumber;
        this.originX     = originX;
        this.originZ     = originZ;
        this.theme       = theme;
        this.logger      = logger;
        this.random      = random;
        this.carveScheduler = carveScheduler;
        // Seed noise from the floor number so each floor feels different.
        this.noise       = new CaveNoise(floorNumber * 0x9e3779b97f4a7c15L + 0xdeadbeefcafeL);
    }

    private RoomCarveListener carveListener;

    public void setRoomCarveListener(RoomCarveListener listener) {
        this.carveListener = listener;
    }

    public RoomGraph graph() {
        return graph;
    }

    // ─── Boss / buffer room registration ────────────────────────────────────

    public DungeonRoom registerBossRoom(int x, int z, int radiusX, int radiusZ) {
        DungeonRoom room = new DungeonRoom(UUID.randomUUID(), x, z, radiusX, radiusZ, DungeonRoom.Type.BOSS);
        graph.addRoom(room);
        return room;
    }

    public DungeonRoom registerBufferRoom(int x, int z) {
        DungeonRoom existing = graph.roomContaining(x, z);
        if (existing != null) return existing;
        DungeonRoom room = new DungeonRoom(UUID.randomUUID(), x, z, 8, 8, DungeonRoom.Type.BUFFER);
        graph.addRoom(room);
        graph.addRoutingTarget(room.id());
        return room;
    }

    // ─── Main entry point ────────────────────────────────────────────────────

    /**
     * Queues carving for all uncarved/unqueued chunk columns within
     * CARVE_RADIUS of the given XZ frontier. Safe to call repeatedly —
     * already-carved-or-queued chunks are skipped instantly via the
     * carved-chunk set.
     *
     * IMPORTANT: this used to carve every chunk in the radius
     * synchronously, right here, on whatever thread called it (always
     * the main thread in practice - PlayerMoveEvent or an entity death
     * event). A 9x9 chunk radius is 81 chunk columns of noise sampling
     * and setType() calls done in one go; multiplied by the 3-8
     * staircases DungeonStaircaseOrchestrator places per floor clear
     * (each one calling this again for its own buffer room), that was
     * routinely hundreds of chunk-carves inside a single tick, long
     * enough to trip Paper's watchdog and crash the server. Now this
     * method only enqueues chunk jobs; DungeonCarveScheduler drains a
     * small fixed number of them per tick so no single tick ever does
     * more than a few chunks' worth of work. The player's own standing
     * chunk (dcx == dcz == 0) is enqueued urgent so it's essentially
     * still carved by the next tick; everything else is normal
     * priority and fills in over the following ticks as the player
     * approaches.
     */
    public void planAndCarveNear(World world, int frontierX, int frontierZ) {
        if (!floorBounds.isWithinGenerationRadius(originX, originZ, frontierX, frontierZ)) {
            return;
        }

        int chunkRadius = (CARVE_RADIUS >> 4) + 1;
        int centerChunkX = frontierX >> 4;
        int centerChunkZ = frontierZ >> 4;

        for (int dcx = -chunkRadius; dcx <= chunkRadius; dcx++) {
            for (int dcz = -chunkRadius; dcz <= chunkRadius; dcz++) {
                int cx = centerChunkX + dcx;
                int cz = centerChunkZ + dcz;

                // Quick leash check: skip chunk columns whose centre is outside the radius.
                double chunkCentreX = (cx << 4) + 8.0;
                double chunkCentreZ = (cz << 4) + 8.0;
                if (!floorBounds.isWithinGenerationRadius(originX, originZ, chunkCentreX, chunkCentreZ)) {
                    continue;
                }

                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (carvedChunks.add(key)) {
                    boolean urgent = dcx == 0 && dcz == 0;
                    if (carveScheduler != null) {
                        if (urgent) {
                            carveScheduler.enqueueUrgent(this, world, cx, cz);
                        } else {
                            carveScheduler.enqueueNormal(this, world, cx, cz);
                        }
                    } else {
                        // No scheduler wired (e.g. a unit test constructing
                        // this directly) - fall back to the old synchronous
                        // behaviour rather than silently doing nothing.
                        carveChunkColumn(world, cx, cz);
                    }
                }
            }
        }
    }

    /**
     * Immediately queues (urgent lane) every chunk column that a
     * registered boss room's footprint overlaps, regardless of player
     * position. Called once, right after registerBossRoom, so a boss
     * room is guaranteed to be fully carved well before any player
     * could possibly reach it on foot - previously a boss room only
     * ever got carved incidentally, whenever some player's frontier
     * radius happened to sweep over it, which meant a player who
     * walked straight to a boss room the moment it came into range
     * could arrive before it had been carved: DungeonBossRoomTrigger
     * would find no open column to spawn into and silently skip the
     * boss entirely, which is the "boss isn't spawning" symptom.
     */
    public void enqueueBossRoomAreaUrgent(World world, DungeonRoom bossRoom) {
        int radius = DungeonBossRoomGeometry.RADIUS + 16;
        int minChunkX = (bossRoom.centerX() - radius) >> 4;
        int maxChunkX = (bossRoom.centerX() + radius) >> 4;
        int minChunkZ = (bossRoom.centerZ() - radius) >> 4;
        int maxChunkZ = (bossRoom.centerZ() + radius) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                double chunkCentreX = (cx << 4) + 8.0;
                double chunkCentreZ = (cz << 4) + 8.0;
                if (!floorBounds.isWithinGenerationRadius(originX, originZ, chunkCentreX, chunkCentreZ)) {
                    continue;
                }
                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (carvedChunks.add(key)) {
                    if (carveScheduler != null) {
                        carveScheduler.enqueueUrgent(this, world, cx, cz);
                    } else {
                        carveChunkColumn(world, cx, cz);
                    }
                }
            }
        }
    }

    // ─── Cave carving ────────────────────────────────────────────────────────

    /** Entry point used by DungeonCarveScheduler to actually perform a queued carve. Package-visible on purpose. */
    void carveChunkColumnFromScheduler(World world, int chunkX, int chunkZ) {
        carveChunkColumn(world, chunkX, chunkZ);
    }

    private void carveChunkColumn(World world, int chunkX, int chunkZ) {
        int floorY = floorBounds.floorBottomY(floorNumber);
        int topY   = floorBounds.floorTopY(floorNumber);

        // The playable band is floorY..topY-1 inclusive.
        // Solid floor layers: floorY .. floorY + SOLID_FLOOR_LAYERS - 1
        // Solid ceiling layers: topY - SOLID_CEIL_LAYERS .. topY - 1
        int caveMinY = floorY + SOLID_FLOOR_LAYERS;
        int caveMaxY = topY   - SOLID_CEIL_LAYERS - 1; // inclusive

        List<Material> primary = theme.getPrimaryBlocks();
        List<Material> accent  = theme.getAccentBlocks();

        // Does a registered boss room's circular footprint reach into this
        // chunk at all? Checked once per chunk (cheap) rather than once per
        // column - a boss room's 30-block radius can only ever touch a
        // handful of chunks, so most calls here return null instantly.
        double chunkCenterX = (chunkX << 4) + 8.0;
        double chunkCenterZ = (chunkZ << 4) + 8.0;
        DungeonRoom bossRoom = graph.nearestBossRoomWithin(
                chunkCenterX, chunkCenterZ, DungeonBossRoomGeometry.RADIUS + 16);

        // Track whether anything was actually opened up so we only fire the
        // listener (and add a graph node) when there's real walkable space.
        boolean anyOpen = false;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (chunkX << 4) + lx;
                int wz = (chunkZ << 4) + lz;

                // Floor layers — always solid, themed. Shared by both boss
                // rooms and ordinary cave columns; only the cave band above
                // it differs.
                for (int y = floorY; y < floorY + SOLID_FLOOR_LAYERS; y++) {
                    Material m = (random.nextDouble() < 0.07) ? pick(accent) : pick(primary);
                    // applyPhysics=false: bulk carving must never trigger
                    // neighbor block-update cascades. Those cascades call
                    // getBlockState() on adjacent chunks, and if a
                    // neighbor chunk isn't loaded yet that forces a
                    // synchronous chunk load on the main thread - exactly
                    // the mechanism behind the "chunk wait" watchdog hangs.
                    world.getBlockAt(wx, y, wz).setType(m, false);
                }

                boolean inBossFootprint = bossRoom != null && DungeonBossRoomGeometry.isInsideFootprint(
                        wx - bossRoom.centerX(), wz - bossRoom.centerZ());

                if (inBossFootprint) {
                    // Explicit cylinder shape (drum wall + 4 open doorways)
                    // instead of noise - this is what makes a boss room
                    // read as a deliberately built arena rather than just
                    // another noise cave pocket. Gate doorways are carved
                    // open here; DungeonBossGateController seals them at
                    // runtime once the fight starts.
                    carveBossRoomColumn(world, bossRoom, wx, wz, floorY, primary, accent);
                    anyOpen = true;
                    continue;
                }

                // Cave band — noise-driven, but never carved outside the
                // safe-carve radius: that leaves a solid WALL_BAND_THICKNESS
                // ring of stone right at the edge of the generation leash so
                // players can never carve/walk straight out into the void.
                boolean withinSafeCarveRadius = floorBounds.isWithinCarveRadius(originX, originZ, wx, wz);
                for (int y = caveMinY; y <= caveMaxY; y++) {
                    if (withinSafeCarveRadius) {
                        double n = noise.sample(wx * FREQ_XZ, y * FREQ_Y, wz * FREQ_XZ);
                        if (n < CAVE_THRESHOLD) {
                            world.getBlockAt(wx, y, wz).setType(Material.AIR, false);
                            anyOpen = true;
                        }
                        // else: leave as stone (already placed by StoneBufferGenerator)
                    }
                    // else: inside the wall band - always leave solid, regardless of noise.
                }

                // Ceiling layer — always solid (stone, unchanged from buffer).
            }
        }

        if (!anyOpen) return;

        // Synthesise a DungeonRoom node for this chunk column so staircase
        // validators and chest/mob hooks have something to work with.
        int roomCx = (chunkX << 4) + 8;
        int roomCz = (chunkZ << 4) + 8;

        // Check for special registered rooms (boss/buffer) near this chunk
        // — if one falls here, honour its type; otherwise random chance of
        // chest room, else normal.
        DungeonRoom existing = graph.roomContaining(roomCx, roomCz);
        DungeonRoom room;
        if (existing != null && !existing.isCarved()) {
            room = existing;
        } else if (existing == null) {
            DungeonRoom.Type type = (random.nextDouble() < 0.12)
                    ? DungeonRoom.Type.CHEST
                    : DungeonRoom.Type.NORMAL;
            room = new DungeonRoom(UUID.randomUUID(), roomCx, roomCz, 8, 8, type);
            graph.addRoom(room);
        } else {
            room = existing;
        }

        room.markCarved();
        if (carveListener != null) {
            carveListener.onRoomCarved(world, floorNumber, room);
        }
    }

    // ─── Boss room cylinder carving ──────────────────────────────────────────

    /**
     * Carves a single XZ column of the boss room drum: solid wall ring
     * (except where a gate doorway cuts through it) or open interior,
     * for the full vertical span between the solid floor cap and solid
     * ceiling cap. Gates are carved open here by design - sealing them
     * is a runtime action owned by DungeonBossGateController, not a
     * generation-time one, since whether they're open depends on
     * combat state that doesn't exist yet at carve time.
     */
    private void carveBossRoomColumn(World world, DungeonRoom bossRoom, int wx, int wz, int floorY,
                                      List<Material> primary, List<Material> accent) {
        int dx = wx - bossRoom.centerX();
        int dz = wz - bossRoom.centerZ();

        int minY = floorY + SOLID_FLOOR_LAYERS;
        int maxY = floorY + DungeonBossRoomGeometry.HEIGHT - SOLID_CEIL_LAYERS - 1;

        boolean wallRing = DungeonBossRoomGeometry.isInsideWallRing(dx, dz);
        boolean isGate = wallRing && DungeonBossRoomGeometry.gateAt(dx, dz) != null;

        for (int y = minY; y <= maxY; y++) {
            if (wallRing && !isGate) {
                Material m = (random.nextDouble() < 0.1) ? pick(accent) : pick(primary);
                world.getBlockAt(wx, y, wz).setType(m, false);
            } else {
                world.getBlockAt(wx, y, wz).setType(Material.AIR, false);
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Material pick(List<Material> list) {
        if (list.isEmpty()) return Material.STONE;
        return list.get(random.nextInt(list.size()));
    }

    // ─── 3D Value Noise ──────────────────────────────────────────────────────

    /**
     * A compact 3D value noise implementation seeded per floor.
     * Uses a 256-entry permutation table and trilinear interpolation
     * so adjacent chunk boundaries always match — the key property that
     * makes caves feel continuous across chunk seams.
     */
    private static final class CaveNoise {

        private final int[] perm = new int[512];

        CaveNoise(long seed) {
            // Build a shuffled 0-255 permutation table, then mirror it.
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) p[i] = i;
            Random rng = new Random(seed);
            for (int i = 255; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
            }
            for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        }

        /** Returns a smooth value in [0, 1]. */
        double sample(double x, double y, double z) {
            int xi = (int) Math.floor(x) & 255;
            int yi = (int) Math.floor(y) & 255;
            int zi = (int) Math.floor(z) & 255;

            double xf = x - Math.floor(x);
            double yf = y - Math.floor(y);
            double zf = z - Math.floor(z);

            double u = fade(xf);
            double v = fade(yf);
            double w = fade(zf);

            // Hash the 8 corners of the unit cube.
            int aaa = perm[perm[perm[xi]   + yi]   + zi];
            int baa = perm[perm[perm[xi+1] + yi]   + zi];
            int aba = perm[perm[perm[xi]   + yi+1] + zi];
            int bba = perm[perm[perm[xi+1] + yi+1] + zi];
            int aab = perm[perm[perm[xi]   + yi]   + zi+1];
            int bab = perm[perm[perm[xi+1] + yi]   + zi+1];
            int abb = perm[perm[perm[xi]   + yi+1] + zi+1];
            int bbb = perm[perm[perm[xi+1] + yi+1] + zi+1];

            // Trilinear interpolation of pseudo-gradient dot products.
            double x1 = lerp(u, grad(aaa, xf,   yf,   zf),   grad(baa, xf-1, yf,   zf));
            double x2 = lerp(u, grad(aba, xf,   yf-1, zf),   grad(bba, xf-1, yf-1, zf));
            double y1 = lerp(v, x1, x2);

            double x3 = lerp(u, grad(aab, xf,   yf,   zf-1), grad(bab, xf-1, yf,   zf-1));
            double x4 = lerp(u, grad(abb, xf,   yf-1, zf-1), grad(bbb, xf-1, yf-1, zf-1));
            double y2 = lerp(v, x3, x4);

            // Map from [-1,1] to [0,1].
            return (lerp(w, y1, y2) + 1.0) * 0.5;
        }

        private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
        private static double lerp(double t, double a, double b) { return a + t * (b - a); }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
    }
}
