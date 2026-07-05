package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.floor.DungeonHubBuilder;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Chunk-based cave carver, now driven by a preplanned room/tunnel graph
 * (see DungeonGraphPlanner) instead of raw Perlin-threshold noise.
 *
 * Previously this class both DECIDED where rooms went (implicitly,
 * wherever noise happened to open air) and CARVED them, one chunk at a
 * time, with no advance layout - which is why rooms were flat-floored
 * domes (a plain noise threshold naturally produces a rounded blob
 * sitting on a flat cutoff plane) and connectivity between chunks was
 * never guaranteed (two adjacent chunks' noise could easily fail to
 * line up, leaving disconnected pockets - the "soft border" bug).
 *
 * Now DungeonGraphPlanner plans the ENTIRE floor's rooms and tunnels
 * upfront, once, the first time the floor is touched (getOrCreatePlanner
 * triggers this - see the constructor). This class's only remaining job
 * is RENDERING that plan into blocks, lazily, one chunk column at a
 * time, exactly as before: DungeonCarveScheduler still drains a small
 * fixed number of chunk jobs per tick, planAndCarveNear still just
 * enqueues work for whatever's near a frontier, and nothing about the
 * frontier-driven, infinite-feeling exploration loop changes. The only
 * difference is WHAT gets carved into a chunk once it's its turn: a
 * chunk now looks up which of the graph's already-placed rooms/tunnels
 * (via RoomGraph's spatial index, see RoomGraph.roomsNearChunk/
 * corridorsNearChunk) intersect it, and evaluates SdfShapes against
 * those specific candidates - producing bubbly, non-flat-floored rooms
 * and wide, arch-shaped tunnels sized relative to the rooms they
 * connect, instead of undirected noise.
 *
 * Because the graph is planned upfront with an explicit spanning tree
 * plus a guaranteed-connectivity bridge pass (see DungeonGraphPlanner),
 * this class no longer needs the old ensureConnectivity()/seam-connector
 * safety net - there's no such thing as "noise randomly failed to
 * connect two chunks" anymore, since every room and tunnel's exact
 * position was decided in advance and IS the connectivity guarantee.
 *
 * API surface is identical to the old planner so DungeonFloorManager
 * and all other callers require no changes.
 */
public final class DungeonRoomPlanner {

    /** Chunk columns within this many blocks of a frontier get carved. */
    private static final int CARVE_RADIUS = 48;

    /** Floor Y-layers kept solid as walkable ground (from floorBottomY upward), used only as a fallback cap. */
    private static final int SOLID_FLOOR_LAYERS = FloorBounds.SOLID_FLOOR_LAYERS;
    /** Ceiling Y-layers kept solid (from floorTopY downward), used only as a fallback cap. */
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

    /** Chunk keys that have actually completed carving (as opposed to merely queued). */
    private final Set<Long> carvedChunkGeometry = ConcurrentHashMap.newKeySet();

    /**
     * Chunk keys that were restored from a PAST session's persisted
     * snapshot (as opposed to carved during the current session). Used
     * exclusively by enqueueBossRoomAreaUrgent() to tell "this boss
     * room chunk already finished carving before, on a previous run -
     * don't touch it" apart from "this chunk was carved earlier THIS
     * session and needs to be overridden with the boss cylinder shape."
     * See that method's doc for the full story.
     */
    private final Set<Long> restoredChunkKeys = ConcurrentHashMap.newKeySet();

    /**
     * The floor's preplanned room/tunnel graph and its designated boss
     * room, produced once by DungeonGraphPlanner in the constructor
     * below (only when the supplied graph arrives empty - see there).
     */
    private final DungeonGraphPlanner.PlannedGraph plannedGraph;

    public DungeonRoomPlanner(RoomGraph graph, FloorBounds floorBounds, int floorNumber,
                               double originX, double originZ, FloorTheme theme,
                               Logger logger, Random random, DungeonCarveScheduler carveScheduler) {
        // Fallback only - callers should prefer the explicit-entrance
        // constructor below with the floor's REAL doorway location.
        // Floor 1's actual hub gateway sits at
        // originX + FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET (EAST, +X;
        // see DungeonHubBuilder.gatewayPoint()), not west of origin - a
        // previous version of this fallback pointed west and silently
        // planned the entire graph anchored on the opposite side of the
        // disc from where the hub actually opens, leaving the real
        // doorway with no nearby planned room/corridor at all (nothing
        // carved there, ever). This fallback is now at least on the
        // correct side, but DungeonFloorManager.getOrCreatePlanner
        // always supplies the exact gateway point directly now, so this
        // path shouldn't be hit in normal operation.
        this(graph, floorBounds, floorNumber, originX, originZ, theme, logger, random, carveScheduler,
                originX + FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET - 24, originZ);
    }

    /**
     * Full constructor allowing an explicit entrance position (where the
     * incoming staircase/gateway from the floor above lands). Every real
     * caller should use this overload with the floor's actual doorway
     * location - for Floor 1 that's DungeonHubBuilder.gatewayPoint(),
     * for higher floors it's the previous floor's placed staircase exit.
     * The no-entrance-argument constructor above is a last-resort
     * fallback only, not something normal wiring should rely on.
     */
    public DungeonRoomPlanner(RoomGraph graph, FloorBounds floorBounds, int floorNumber,
                               double originX, double originZ, FloorTheme theme,
                               Logger logger, Random random, DungeonCarveScheduler carveScheduler,
                               double entranceX, double entranceZ) {
        this.graph       = graph;
        this.floorBounds = floorBounds;
        this.floorNumber = floorNumber;
        this.originX     = originX;
        this.originZ     = originZ;
        this.theme       = theme;
        this.logger      = logger;
        this.random      = random;
        this.carveScheduler = carveScheduler;

        // Plan the full floor's room/tunnel graph right now, once, up
        // front - this is the one piece of work that ISN'T lazy/
        // frontier-driven, everything downstream of this (actual block
        // carving) still happens lazily per chunk exactly as before.
        // Only plan fresh when the graph is genuinely empty, so a graph
        // already populated elsewhere (e.g. restored from a persistence
        // snapshot, or a boss room registered against it before this
        // constructor ran) isn't clobbered or duplicated.
        if (graph.roomCount() == 0) {
            this.plannedGraph = DungeonGraphPlanner.planFloor(
                    floorNumber, originX, originZ, floorBounds, entranceX, entranceZ, logger);
            for (DungeonRoom room : plannedGraph.graph.allRooms()) {
                graph.addRoom(room);
            }
            for (DungeonCorridor corridor : plannedGraph.graph.allCorridors()) {
                graph.addCorridor(corridor);
            }
        } else {
            this.plannedGraph = null;
        }
    }

    private RoomCarveListener carveListener;

    public void setRoomCarveListener(RoomCarveListener listener) {
        this.carveListener = listener;
    }

    public RoomGraph graph() {
        return graph;
    }

    /**
     * Snapshot of every chunk key currently marked carved-or-queued.
     * Used by DungeonFloorStateStorage to persist generation progress
     * so a restart doesn't forget what's already been carved.
     */
    public Set<Long> carvedChunkKeys() {
        return Set.copyOf(carvedChunks);
    }

    /**
     * Re-seeds the carved-chunk set from a persisted snapshot, BEFORE
     * any player can call planAndCarveNear() again. This is what stops
     * planAndCarveNear() from re-queuing (and re-carving, overwriting
     * whatever players already built/looted/staircased) chunks that
     * were already carved before a crash or restart.
     */
    public void restoreCarvedChunks(Collection<Long> keys) {
        carvedChunks.addAll(keys);
        restoredChunkKeys.addAll(keys);
        carvedChunkGeometry.addAll(keys);
    }

    // ─── Boss / buffer room registration ────────────────────────────────────

    /**
     * The graph's own designated boss room - the single BOSS-type room
     * DungeonGraphPlanner placed as part of this floor's preplanned
     * graph (on a corridor branch, connected, carved via SDF). This is
     * "the" boss room for the floor; callers should use this instead of
     * ever rolling/registering a second, independent BOSS room, which
     * previously caused two boss rooms to exist simultaneously (one
     * graph-integrated and correctly placed, one legacy/random and
     * often landing right next to spawn) with only one of them actually
     * getting boss-spawn logic applied.
     *
     * Returns null only if this planner didn't plan a fresh graph (i.e.
     * the graph was already populated when this planner was
     * constructed, meaning some earlier planner/instance owns the
     * planned boss room already) - in practice every real call path
     * goes through a planner that either just planned fresh or is
     * looking at a graph a fresh-planning planner already populated, so
     * graph.nearestBossRoomWithin can also be used as a fallback lookup
     * for that case (see registerBossRoom below, used by snapshot
     * restore).
     */
    public DungeonRoom plannedBossRoom() {
        if (plannedGraph != null && plannedGraph.bossRoom != null) {
            return plannedGraph.bossRoom;
        }
        return null;
    }

    /**
     * Resolves a boss room by proximity to a previously-persisted
     * (x, z) location - used exclusively by DungeonFloorStateStorage's
     * restore path, where the boss room's real position is whatever
     * the graph freshly planned this run (see DungeonGraphPlanner's
     * planFloor - it isn't currently seeded deterministically, so a
     * restored coordinate is a best-effort proximity hint, not a
     * guaranteed exact match). This does NOT create a second BOSS room
     * on a miss - it strictly returns the graph's own planned boss room
     * (or the nearest existing BOSS room in the graph) so the
     * "two boss rooms" bug can't reoccur via the restore path either.
     */
    public DungeonRoom registerBossRoom(int x, int z, int radiusX, int radiusZ) {
        DungeonRoom existing = graph.nearestBossRoomWithin(x, z, Math.max(radiusX, radiusZ) + 64);
        if (existing != null) {
            return existing;
        }
        DungeonRoom planned = plannedBossRoom();
        if (planned != null) {
            return planned;
        }
        // Last resort: scan the whole graph for any BOSS room at all,
        // rather than ever fabricating a brand new one.
        for (DungeonRoom room : graph.allRooms()) {
            if (room.type() == DungeonRoom.Type.BOSS) {
                return room;
            }
        }
        return null;
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
     * carved-chunk set. Behaviourally identical to the old version:
     * only enqueues work, DungeonCarveScheduler drains it a few chunks
     * per tick, the player's own chunk goes in the urgent lane.
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
     * could possibly reach it on foot.
     *
     * A chunk is force-carved (even if carvedChunks already contains
     * its key) UNLESS that key came from a restored persistence
     * snapshot (restoredChunkKeys) - see restoreCarvedChunks(). That
     * distinction stops every server restart from re-triggering
     * onRoomCarved (ambient mobs/chests) in an already-cleared boss
     * room, while still letting a boss room registered mid-session
     * override whatever plain carving may have already touched its
     * chunks.
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

                if (restoredChunkKeys.contains(key)) {
                    continue;
                }

                carvedChunks.add(key);
                if (carveScheduler != null) {
                    carveScheduler.enqueueUrgent(this, world, cx, cz);
                } else {
                    carveChunkColumn(world, cx, cz);
                }
            }
        }
    }

    // ─── Cave carving ────────────────────────────────────────────────────────

    /**
     * Room types allowed to carve past the wall band (isWithinCarveRadius's
     * boundary) instead of being capped by it like ordinary generation.
     * ENTRANCE is the floor's gateway node, which can legitimately sit
     * right at the edge of the disc; BUFFER is a staircase landing from
     * the floor above, which is placed at a specific incoming position
     * this planner doesn't control either. Both need to carve through
     * regardless of exactly where that happens to fall relative to the
     * wall band.
     */
    private static boolean isWallBandWhitelisted(DungeonRoom.Type type) {
        return type == DungeonRoom.Type.ENTRANCE || type == DungeonRoom.Type.BUFFER;
    }

    /** Entry point used by DungeonCarveScheduler to actually perform a queued carve. Package-visible on purpose. */
    void carveChunkColumnFromScheduler(World world, int chunkX, int chunkZ) {
        carveChunkColumn(world, chunkX, chunkZ);
    }

    /**
     * Carves one 16x16 chunk column by evaluating SdfShapes against
     * whichever planned rooms/tunnels (from the spatial index built by
     * DungeonGraphPlanner into RoomGraph) actually reach into this
     * chunk - replacing the old flat Perlin-threshold sampling.
     *
     * Per column: solid by default; open to air wherever ANY nearby
     * room's irregularRoomSDF is <= 0, OR any nearby corridor's
     * tunnelArchSDF says the column falls inside the arch. A thin solid
     * floor cap and ceiling cap are still enforced at the very top/
     * bottom of the floor's playable Y band (not per-room) purely as a
     * safety net against a room/tunnel SDF poking through into the
     * buffer stone above/below the floor slot.
     */
    private void carveChunkColumn(World world, int chunkX, int chunkZ) {
        int floorBottomY = floorBounds.floorBottomY(floorNumber);
        int floorTopY    = floorBounds.floorTopY(floorNumber);
        int bandMinY = floorBottomY + 1;             // one safety layer above the absolute floor
        int bandMaxY = floorTopY - 2;                 // one safety layer below the absolute ceiling

        // Only meaningful on floor 1 (see the guard below), but cheap
        // enough to always compute from this floor's own origin rather
        // than special-casing the call site.
        int hubWestWallOuterFaceX = DungeonHubBuilder.hubWestWallOuterFaceX((int) Math.round(originX));

        List<Material> primary = theme.getPrimaryBlocks();
        List<Material> accent  = theme.getAccentBlocks();

        List<DungeonRoom> nearbyRooms = graph.roomsNearChunk(chunkX, chunkZ);
        List<DungeonCorridor> nearbyCorridors = graph.corridorsNearChunk(chunkX, chunkZ);

        // Boss room takes priority and uses the explicit drum/gate shape
        // (DungeonBossRoomGeometry) rather than the bubbly SDF, so
        // DungeonBossGateController's gate-sealing logic keeps working
        // unchanged - it operates on the exact wall-ring/gate math in
        // that class, not on an SDF surface.
        double chunkCenterX = (chunkX << 4) + 8.0;
        double chunkCenterZ = (chunkZ << 4) + 8.0;
        DungeonRoom bossRoom = graph.nearestBossRoomWithin(
                chunkCenterX, chunkCenterZ, DungeonBossRoomGeometry.RADIUS + 16);

        boolean anyOpen = false;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = (chunkX << 4) + lx;
                int wz = (chunkZ << 4) + lz;

                boolean inBossFootprint = bossRoom != null && DungeonBossRoomGeometry.isInsideFootprint(
                        wx - bossRoom.centerX(), wz - bossRoom.centerZ());

                if (inBossFootprint) {
                    carveBossRoomColumn(world, bossRoom, wx, wz, floorBottomY, primary, accent);
                    anyOpen = true;
                    continue;
                }

                // Floor 1 only: never touch anything at or east of Area
                // Zero's west wall outer face. That's the hub's own
                // territory - buildHub() already placed its wall and
                // doorway breach there, and the entrance tunnel this
                // planner grows west from the gateway point must stop
                // short of it and hand off cleanly, not carve back
                // through it. Without this guard, the SDF carve pass
                // (which has no concept of the hub at all) would
                // eventually reseal the doorway with stone or blow extra
                // holes through the wall the moment a player's frontier
                // reached this far east - silently undoing buildHub()'s
                // work after the fact, every time this chunk got
                // (re)carved.
                if (floorNumber == 1 && wx >= hubWestWallOuterFaceX) {
                    continue;
                }

                boolean withinSafeCarveRadius = floorBounds.isWithinCarveRadius(originX, originZ, wx, wz);

                for (int y = bandMinY; y <= bandMaxY; y++) {
                    boolean open = false;

                    // The wall band (isWithinCarveRadius's boundary) is a
                    // GENERATION LIMIT for ordinary rooms/corridors, not
                    // an unconditional hard wall - WALL_BAND-listed room
                    // types (ENTRANCE, and any BUFFER staircase landing)
                    // are allowed to carve straight through it, since
                    // those are legitimate floor features that can
                    // legitimately sit right at the edge of the disc
                    // (e.g. the entrance node planted at the hub gateway
                    // point). Everything else still respects the band,
                    // guaranteeing WALL_BAND_THICKNESS of solid stone
                    // between ordinary cave space and the void beyond
                    // GENERATION_RADIUS.
                    for (DungeonRoom room : nearbyRooms) {
                        if (room.type() == DungeonRoom.Type.BOSS) continue; // handled above
                        if (!withinSafeCarveRadius && !isWallBandWhitelisted(room.type())) continue;
                        double sdf = SdfShapes.irregularRoomSDF(wx, y, wz,
                                room.centerX(), room.floorY(), room.centerZ(),
                                room.radiusX(), room.domeH(), room.radiusZ(), room.noiseSeed());
                        if (sdf <= 0) {
                            open = true;
                            break;
                        }
                    }
                    if (!open) {
                        for (DungeonCorridor corridor : nearbyCorridors) {
                            DungeonRoom fromRoom = graph.getRoom(corridor.fromRoomId());
                            DungeonRoom toRoom = graph.getRoom(corridor.toRoomId());
                            boolean corridorWhitelisted =
                                    (fromRoom != null && isWallBandWhitelisted(fromRoom.type()))
                                    || (toRoom != null && isWallBandWhitelisted(toRoom.type()));
                            if (!withinSafeCarveRadius && !corridorWhitelisted) continue;

                            List<int[]> wp = corridor.waypoints();
                            boolean insideAnySegment = false;
                            double ay = fromRoom != null ? fromRoom.floorY() : y;
                            double by = toRoom != null ? toRoom.floorY() : y;
                            for (int i = 0; i < wp.size() - 1 && !insideAnySegment; i++) {
                                int[] a = wp.get(i);
                                int[] b = wp.get(i + 1);
                                insideAnySegment = SdfShapes.tunnelArchSDF(wx, y, wz,
                                        a[0], ay, a[1], b[0], by, b[1], corridor.width());
                            }
                            if (insideAnySegment) {
                                open = true;
                                break;
                            }
                        }
                    }

                    // Absolute-edge safety caps: never open air at the
                    // very bottom/top layer of the floor's playable band,
                    // regardless of what any SDF says.
                    if (y <= floorBottomY || y >= floorTopY - 1) {
                        open = false;
                    }

                    if (open) {
                        world.getBlockAt(wx, y, wz).setType(Material.AIR, false);
                        anyOpen = true;
                    } else {
                        Material m = (random.nextDouble() < 0.07) ? pick(accent) : pick(primary);
                        world.getBlockAt(wx, y, wz).setType(m, false);
                    }
                }

                // Absolute floor/ceiling caps themselves, always solid.
                Material floorMat = (random.nextDouble() < 0.07) ? pick(accent) : pick(primary);
                world.getBlockAt(wx, floorBottomY, wz).setType(floorMat, false);
                Material ceilMat = (random.nextDouble() < 0.07) ? pick(accent) : pick(primary);
                world.getBlockAt(wx, floorTopY - 1, wz).setType(ceilMat, false);
            }
        }

        carvedChunkGeometry.add(chunkKey(chunkX, chunkZ));

        if (!anyOpen) return;

        int roomCx = (chunkX << 4) + 8;
        int roomCz = (chunkZ << 4) + 8;

        // With the preplanned graph, a room almost always already exists
        // at/near this chunk (graph.roomContaining uses the same spatial
        // index) - the synthesise-a-room fallback below only matters for
        // chunks that opened purely because a tunnel passed through them
        // without a room center landing exactly there.
        DungeonRoom existing = graph.roomContaining(roomCx, roomCz);
        DungeonRoom room;
        if (existing != null) {
            room = existing;
        } else {
            room = new DungeonRoom(UUID.randomUUID(), roomCx, roomCz, 8, 8, DungeonRoom.Type.NORMAL);
            graph.addRoom(room);
        }

        if (!room.isCarved()) {
            room.markCarved();
            if (carveListener != null) {
                carveListener.onRoomCarved(world, floorNumber, room);
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
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

        for (int y = floorY; y < minY; y++) {
            Material m = (random.nextDouble() < 0.1) ? pick(accent) : pick(primary);
            world.getBlockAt(wx, y, wz).setType(m, false);
        }
        for (int y = maxY + 1; y < floorY + DungeonBossRoomGeometry.HEIGHT; y++) {
            Material m = (random.nextDouble() < 0.1) ? pick(accent) : pick(primary);
            world.getBlockAt(wx, y, wz).setType(m, false);
        }

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
}
