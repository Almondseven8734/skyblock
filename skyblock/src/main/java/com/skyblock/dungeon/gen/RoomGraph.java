package com.skyblock.dungeon.gen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Holds the full room/corridor graph for a single floor: every room
 * placed so far (carved or still queued) and every corridor between
 * them. This is pure data + queries, no Bukkit dependency, no carving
 * logic - DungeonRoomPlanner owns the actual generation decisions and
 * world writes, this class just tracks "what exists where" so the
 * planner can reason about connectivity and routing targets.
 *
 * Since DungeonGraphPlanner now plans a full floor's rooms upfront
 * (~2,000-2,800 per floor across the R2000 disc) instead of the old
 * system's handful of rooms discovered lazily one chunk at a time, the
 * naive linear-scan queries below (roomContaining, nearestBossRoomWithin,
 * corridorsNear) would each cost thousands of distance checks - and
 * DungeonRoomPlanner calls query methods like these once per carved
 * chunk column, so a floor's worth of carving would mean many millions
 * of checks overall. spatialIndex (built lazily, once, from whatever
 * rooms/corridors exist the first time it's needed) buckets rooms and
 * corridor waypoints by chunk so carve-time queries only scan the
 * handful of rooms/corridors actually near the chunk in question.
 *
 * The index is a snapshot taken on first use - fine here because
 * DungeonGraphPlanner populates the ENTIRE graph upfront before any
 * carving/querying happens, so by the time DungeonRoomPlanner starts
 * asking questions, the graph is already complete and immutable in
 * practice (routingTargets/carved-state mutations don't change
 * position, so they don't need to invalidate the index).
 *
 * Not thread-safe by design - the planner is expected to serialize
 * all mutations onto a single async generation thread per floor.
 */
public final class RoomGraph {

    private final int floorNumber;
    private final Map<UUID, DungeonRoom> rooms = new LinkedHashMap<>();
    private final Map<UUID, DungeonCorridor> corridors = new LinkedHashMap<>();

    /**
     * Rooms that other systems (staircase placement, buffer rooms) want
     * the planner to eventually connect toward. The planner consults this
     * when deciding corridor routing so disconnected pockets link up
     * over time instead of staying isolated.
     */
    private final List<UUID> routingTargets = new ArrayList<>();

    /** Chunk-size bucket used by the spatial index (16 = exactly one Minecraft chunk). */
    private static final int CHUNK_SIZE = 16;
    /**
     * How many chunk-buckets around a room's own center it also gets
     * indexed under, so a large room (radius up to ~30) is still found
     * by a query against a nearby-but-not-exactly-central chunk. Cheap
     * duplication (each room/corridor gets added to a handful of bucket
     * lists) traded for correctness at query time.
     */
    private static final int INDEX_PADDING_CHUNKS = 3;

    private Map<Long, List<DungeonRoom>> roomBuckets;
    private Map<Long, List<DungeonCorridor>> corridorBuckets;

    public RoomGraph(int floorNumber) {
        this.floorNumber = floorNumber;
    }

    public int floorNumber() { return floorNumber; }

    public void addRoom(DungeonRoom room) {
        rooms.put(room.id(), room);
        roomBuckets = null; // invalidate - rebuilt lazily on next query
    }

    public void addCorridor(DungeonCorridor corridor) {
        corridors.put(corridor.id(), corridor);
        DungeonRoom from = rooms.get(corridor.fromRoomId());
        DungeonRoom to = rooms.get(corridor.toRoomId());
        if (from != null) from.connectTo(corridor.toRoomId());
        if (to != null) to.connectTo(corridor.fromRoomId());
        corridorBuckets = null; // invalidate - rebuilt lazily on next query
    }

    public DungeonRoom getRoom(UUID id) {
        return rooms.get(id);
    }

    public Collection<DungeonRoom> allRooms() {
        return rooms.values();
    }

    public Collection<DungeonCorridor> allCorridors() {
        return corridors.values();
    }

    // ─── Spatial index ───────────────────────────────────────────────────────

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private void ensureRoomIndex() {
        if (roomBuckets != null) return;
        Map<Long, List<DungeonRoom>> buckets = new HashMap<>();
        for (DungeonRoom room : rooms.values()) {
            int spanChunks = (int) Math.ceil(Math.max(room.radiusX(), room.radiusZ()) / (double) CHUNK_SIZE)
                    + INDEX_PADDING_CHUNKS;
            int cx = Math.floorDiv(room.centerX(), CHUNK_SIZE);
            int cz = Math.floorDiv(room.centerZ(), CHUNK_SIZE);
            for (int dx = -spanChunks; dx <= spanChunks; dx++) {
                for (int dz = -spanChunks; dz <= spanChunks; dz++) {
                    buckets.computeIfAbsent(chunkKey(cx + dx, cz + dz), k -> new ArrayList<>()).add(room);
                }
            }
        }
        roomBuckets = buckets;
    }

    private void ensureCorridorIndex() {
        if (corridorBuckets != null) return;
        Map<Long, List<DungeonCorridor>> buckets = new HashMap<>();
        for (DungeonCorridor corridor : corridors.values()) {
            // Bucket a corridor under every chunk its waypoint polyline
            // passes near (padded by its width + INDEX_PADDING_CHUNKS),
            // by walking the segment between each consecutive waypoint
            // pair in chunk-sized steps.
            List<int[]> wp = corridor.waypoints();
            int padChunks = (int) Math.ceil(corridor.width() / (double) CHUNK_SIZE) + INDEX_PADDING_CHUNKS;
            for (int i = 0; i < wp.size() - 1; i++) {
                int ax = wp.get(i)[0], az = wp.get(i)[1];
                int bx = wp.get(i + 1)[0], bz = wp.get(i + 1)[1];
                double len = Math.hypot(bx - ax, bz - az);
                int steps = Math.max(1, (int) Math.ceil(len / CHUNK_SIZE));
                for (int s = 0; s <= steps; s++) {
                    double t = (double) s / steps;
                    int px = (int) Math.round(ax + (bx - ax) * t);
                    int pz = (int) Math.round(az + (bz - az) * t);
                    int cx = Math.floorDiv(px, CHUNK_SIZE);
                    int cz = Math.floorDiv(pz, CHUNK_SIZE);
                    for (int dx = -padChunks; dx <= padChunks; dx++) {
                        for (int dz = -padChunks; dz <= padChunks; dz++) {
                            buckets.computeIfAbsent(chunkKey(cx + dx, cz + dz), k -> new ArrayList<>()).add(corridor);
                        }
                    }
                }
            }
        }
        // Deduplicate: a long/winding corridor can get added to the same
        // bucket multiple times across different waypoint segments.
        for (List<DungeonCorridor> list : buckets.values()) {
            List<DungeonCorridor> deduped = new ArrayList<>(new java.util.LinkedHashSet<>(list));
            list.clear();
            list.addAll(deduped);
        }
        corridorBuckets = buckets;
    }

    /**
     * All rooms whose indexed footprint (padded, see INDEX_PADDING_CHUNKS)
     * overlaps this chunk. Used by DungeonRoomPlanner as the fast
     * candidate list to run the precise SdfShapes.irregularRoomSDF test
     * against, instead of testing every room on the floor.
     */
    public List<DungeonRoom> roomsNearChunk(int chunkX, int chunkZ) {
        ensureRoomIndex();
        return roomBuckets.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
    }

    /**
     * All corridors whose indexed path (padded, see INDEX_PADDING_CHUNKS)
     * passes near this chunk. Used by DungeonRoomPlanner as the fast
     * candidate list to run the precise SdfShapes.tunnelArchSDF test
     * against, instead of testing every corridor on the floor.
     */
    public List<DungeonCorridor> corridorsNearChunk(int chunkX, int chunkZ) {
        ensureCorridorIndex();
        return corridorBuckets.getOrDefault(chunkKey(chunkX, chunkZ), List.of());
    }

    /** Returns the room (if any) whose footprint contains this XZ point. Index-accelerated. */
    public DungeonRoom roomContaining(int x, int z) {
        int chunkX = Math.floorDiv(x, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(z, CHUNK_SIZE);
        for (DungeonRoom room : roomsNearChunk(chunkX, chunkZ)) {
            if (room.containsXZ(x, z)) {
                return room;
            }
        }
        return null;
    }

    /** Nearest room center to a given point, or null if no rooms exist yet. */
    public DungeonRoom nearestRoom(int x, int z) {
        DungeonRoom nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (DungeonRoom room : rooms.values()) {
            double d = room.distanceTo(x, z);
            if (d < bestDist) {
                bestDist = d;
                nearest = room;
            }
        }
        return nearest;
    }

    /**
     * Nearest BOSS-type room within maxDist of a point, or null if none
     * qualifies. Used by DungeonRoomPlanner to detect whether a chunk
     * column about to be carved overlaps a registered boss room's
     * footprint and needs the explicit cylinder shape instead of
     * ordinary cave noise. Index-accelerated: since there are only ever
     * a handful of BOSS rooms per floor (typically exactly one), this
     * scans the small candidate list from roomsNearChunk rather than
     * every room on the floor.
     */
    public DungeonRoom nearestBossRoomWithin(double x, double z, double maxDist) {
        int chunkX = Math.floorDiv((int) Math.round(x), CHUNK_SIZE);
        int chunkZ = Math.floorDiv((int) Math.round(z), CHUNK_SIZE);
        DungeonRoom best = null;
        double bestDist = Double.MAX_VALUE;
        for (DungeonRoom room : roomsNearChunk(chunkX, chunkZ)) {
            if (room.type() != DungeonRoom.Type.BOSS) continue;
            double d = room.distanceTo((int) Math.round(x), (int) Math.round(z));
            if (d <= maxDist && d < bestDist) {
                bestDist = d;
                best = room;
            }
        }
        return best;
    }

    /**
     * Registers a room (typically a buffer room or a room containing a
     * just-placed staircase) as something future generation should try
     * to route toward, per the "intentionally connect known frontiers"
     * design rule.
     */
    public void addRoutingTarget(UUID roomId) {
        if (!routingTargets.contains(roomId)) {
            routingTargets.add(roomId);
        }
    }

    public void removeRoutingTarget(UUID roomId) {
        routingTargets.remove(roomId);
    }

    public List<UUID> routingTargets() {
        return routingTargets;
    }

    /**
     * The nearest routing target room to a given point that is NOT
     * already connected (directly or transitively isn't checked here,
     * only direct adjacency) to the room at that point. Used by the
     * planner to decide whether to bias a new corridor toward stitching
     * two pockets together rather than just expanding outward blindly.
     */
    public DungeonRoom nearestUnconnectedRoutingTarget(DungeonRoom from, int maxDistance) {
        DungeonRoom best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID targetId : routingTargets) {
            if (targetId.equals(from.id()) || from.connectedRoomIds().contains(targetId)) {
                continue;
            }
            DungeonRoom target = rooms.get(targetId);
            if (target == null) continue;
            double d = from.distanceTo(target.centerX(), target.centerZ());
            if (d <= maxDistance && d < bestDist) {
                bestDist = d;
                best = target;
            }
        }
        return best;
    }

    public int roomCount() {
        return rooms.size();
    }
}
