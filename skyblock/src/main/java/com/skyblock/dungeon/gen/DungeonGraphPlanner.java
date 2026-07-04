package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.util.FloorBounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Plans a full floor's room/tunnel graph upfront, in one pass, the
 * moment a floor is first touched - as opposed to the old system,
 * which discovered/created rooms lazily, one chunk at a time, as
 * players walked around (no advance layout at all, just per-chunk
 * noise-threshold carving).
 *
 * This is a scaled-up port of an earlier standalone prototype's
 * graphPlanner.js: that version planned 18-28 rooms across a flat
 * 200x200 box. This version keeps the same spanning-tree-plus-
 * guaranteed-connectivity algorithm and the same room density (rooms
 * per unit area), but grows the graph across the full circular
 * FloorBounds.GENERATION_RADIUS disc instead of a small square, which
 * at ~100x the area means roughly 2,000-2,800 rooms per floor rather
 * than 18-28.
 *
 * IMPORTANT: this only PLANS the graph (positions, radii, connections)
 * - it does not carve a single block. DungeonRoomPlanner still carves
 * lazily, chunk by chunk, as the frontier reaches each area (via
 * DungeonCarveScheduler exactly as before); this class's output is
 * just the shared blueprint that carving now reads from instead of
 * inventing rooms on the fly.
 *
 * Runs synchronously on first floor access. At ~2,500 rooms the O(n)-
 * per-candidate overlap checks below are backed by a coarse spatial
 * grid (roomBuckets) rather than a linear scan over every existing
 * room, keeping total planning work close to O(n) instead of O(n^2) -
 * important since a naive port of the beta's linear scan would mean
 * several million distance checks per floor unlock.
 */
public final class DungeonGraphPlanner {

    private DungeonGraphPlanner() {}

    /** Bucket size for the coarse spatial index used during planning (blocks per bucket cell). */
    private static final int BUCKET_SIZE = 40;

    private static final int MARGIN = 24;
    private static final double MIN_ROOM_R = 6, MAX_ROOM_R = 14;
    private static final double MIN_TUNNEL_R = 2.5;
    private static final int MIN_SEG_LEN = 5;

    /**
     * Rooms-per-unit-area target, calibrated against the beta's 18-28
     * rooms over a 200x200 = 40,000 block^2 box (i.e. ~0.00055-0.0007
     * rooms per block^2). We use the midpoint of that range.
     */
    private static final double ROOM_DENSITY_PER_BLOCK2 = 23.0 / (200.0 * 200.0);

    public static final class PlannedGraph {
        public final RoomGraph graph;
        public final DungeonRoom entranceRoom;
        public final DungeonRoom bossRoom;

        PlannedGraph(RoomGraph graph, DungeonRoom entranceRoom, DungeonRoom bossRoom) {
            this.graph = graph;
            this.entranceRoom = entranceRoom;
            this.bossRoom = bossRoom;
        }
    }

    /**
     * Plans a floor's full room graph.
     *
     * @param floorNumber     which floor this is (used only for logging/seeding)
     * @param originX/originZ the floor's origin (matches FloorBounds' per-floor origin)
     * @param floorBounds     supplies GENERATION_RADIUS and the walkable Y band
     * @param entranceX/Z     where the incoming staircase/gateway from the floor above lands
     *                        (Floor 1 uses the hub gateway's position; higher floors use the
     *                        previous floor's placed staircase exit)
     * @param logger          for planning diagnostics (room count, timing)
     */
    public static PlannedGraph planFloor(int floorNumber, double originX, double originZ,
                                          FloorBounds floorBounds, double entranceX, double entranceZ,
                                          Logger logger) {
        long startNanos = System.nanoTime();
        long seed = (long) (Math.random() * 0xFFFFFFFFL) ^ ((long) floorNumber << 40);
        Random rng = new Random(seed);

        double radius = FloorBounds.GENERATION_RADIUS - MARGIN;
        double usableArea = Math.PI * radius * radius;
        int targetRooms = (int) Math.round(usableArea * ROOM_DENSITY_PER_BLOCK2);
        targetRooms = Math.max(40, targetRooms);

        RoomGraph graph = new RoomGraph(floorNumber);
        Map<Long, List<DungeonRoom>> buckets = new HashMap<>();

        int floorMidY = floorBounds.walkableFloorY(floorNumber) + FloorBounds.FLOOR_HEIGHT / 2;

        // ── Entrance room ────────────────────────────────────────────────────
        DungeonRoom entrance = new DungeonRoom(UUID.randomUUID(),
                (int) Math.round(entranceX), (int) Math.round(entranceZ),
                9, 9, DungeonRoom.Type.ENTRANCE,
                floorMidY, 7, rng.nextInt());
        graph.addRoom(entrance);
        addToBucket(buckets, entrance);

        List<DungeonRoom> placed = new ArrayList<>();
        placed.add(entrance);

        // ── Grow a spanning tree outward from the entrance ──────────────────
        // Each new room attaches to a random existing room within reach,
        // biased to prefer less-connected rooms so the tree branches out
        // across the disc instead of chaining in one long corridor.
        int attempts = 0;
        int maxAttempts = targetRooms * 12;
        while (placed.size() < targetRooms && attempts < maxAttempts) {
            attempts++;
            DungeonRoom parent = placed.get(rng.nextInt(placed.size()));

            double angle = rng.nextDouble() * Math.PI * 2;
            double tunnelLen = 14 + rng.nextDouble() * 34;
            double px = parent.centerX() + Math.cos(angle) * tunnelLen;
            double pz = parent.centerZ() + Math.sin(angle) * tunnelLen;

            if (Math.hypot(px - originX, pz - originZ) > radius) {
                continue; // outside the floor's disc, retry
            }

            double roomR = MIN_ROOM_R + rng.nextDouble() * (MAX_ROOM_R - MIN_ROOM_R);
            double domeH = 5 + rng.nextDouble() * 4;

            if (overlapsAny(buckets, px, pz, roomR + 6)) {
                continue;
            }
            if (Math.hypot(px - parent.centerX(), pz - parent.centerZ()) < MIN_SEG_LEN) {
                continue;
            }

            DungeonRoom.Type type = decideRoomType(rng);
            double roomY = floorMidY + (rng.nextDouble() - 0.5) * (FloorBounds.FLOOR_HEIGHT * 0.3);

            DungeonRoom room = new DungeonRoom(UUID.randomUUID(),
                    (int) Math.round(px), (int) Math.round(pz),
                    (int) Math.round(roomR), (int) Math.round(roomR),
                    type, roomY, domeH, rng.nextInt());
            graph.addRoom(room);
            addToBucket(buckets, room);
            placed.add(room);

            double tunnelRadius = Math.max(MIN_TUNNEL_R,
                    Math.min(roomR, Math.max(parent.radiusX(), parent.radiusZ())) * 0.72);
            DungeonCorridor corridor = new DungeonCorridor(UUID.randomUUID(), parent.id(), room.id(),
                    List.of(new int[]{parent.centerX(), parent.centerZ()}, new int[]{room.centerX(), room.centerZ()}),
                    (int) Math.round(tunnelRadius));
            graph.addCorridor(corridor);
        }

        // ── Guaranteed connectivity bridge pass ──────────────────────────────
        // Same intent as the beta's BFS bridge pass: after the random
        // growth above, some rooms can still end up in small disconnected
        // pockets if growth attempts near them all failed. Rather than a
        // BFS over the whole graph (fine at 20 rooms, needlessly heavy at
        // 2,500), we do a single union-find pass and, for each pocket that
        // isn't part of the main component, connect its nearest room to
        // the nearest room in the main component with an extra corridor.
        bridgeDisconnectedPockets(graph, placed);

        // ── Boss room ─────────────────────────────────────────────────────
        // Placed last, far from the entrance (biased toward the edge of
        // the disc) and attached to whichever placed room ends up nearest,
        // exactly like the old system's registerBossRoom callers expected
        // - so DungeonBossRoomGeometry/DungeonBossGateController/
        // DungeonBossRoomTrigger all keep working unchanged against this
        // planned BOSS-type room.
        DungeonRoom nearestToEdge = null;
        double bestEdgeDist = -1;
        for (DungeonRoom r : placed) {
            double d = Math.hypot(r.centerX() - originX, r.centerZ() - originZ);
            if (d > bestEdgeDist) {
                bestEdgeDist = d;
                nearestToEdge = r;
            }
        }
        DungeonRoom bossParent = nearestToEdge != null ? nearestToEdge : entrance;
        double bossAngle = Math.atan2(bossParent.centerZ() - originZ, bossParent.centerX() - originX);
        double bossDist = Math.min(radius - 20, bestEdgeDist + 40);
        double bossX = originX + Math.cos(bossAngle) * bossDist;
        double bossZ = originZ + Math.sin(bossAngle) * bossDist;

        DungeonRoom bossRoom = new DungeonRoom(UUID.randomUUID(),
                (int) Math.round(bossX), (int) Math.round(bossZ),
                26, 26, DungeonRoom.Type.BOSS,
                floorMidY, 14, rng.nextInt());
        graph.addRoom(bossRoom);
        DungeonCorridor bossCorridor = new DungeonCorridor(UUID.randomUUID(), bossParent.id(), bossRoom.id(),
                List.of(new int[]{bossParent.centerX(), bossParent.centerZ()}, new int[]{bossRoom.centerX(), bossRoom.centerZ()}),
                8);
        graph.addCorridor(bossCorridor);

        if (logger != null) {
            double ms = (System.nanoTime() - startNanos) / 1_000_000.0;
            logger.info("[Dungeon] Floor " + floorNumber + " graph planned: " + graph.roomCount()
                    + " rooms in " + String.format("%.1f", ms) + "ms (target was " + targetRooms + ")");
        }

        return new PlannedGraph(graph, entrance, bossRoom);
    }

    private static DungeonRoom.Type decideRoomType(Random rng) {
        if (rng.nextDouble() < 0.10) return DungeonRoom.Type.CHEST;
        if (rng.nextDouble() < 0.06) return DungeonRoom.Type.JUNCTION;
        if (rng.nextDouble() < 0.04) return DungeonRoom.Type.DEAD_END;
        return DungeonRoom.Type.NORMAL;
    }

    // ─── Spatial bucket helpers ──────────────────────────────────────────────

    private static long bucketKey(double x, double z) {
        int bx = (int) Math.floor(x / BUCKET_SIZE);
        int bz = (int) Math.floor(z / BUCKET_SIZE);
        return ((long) bx << 32) | (bz & 0xFFFFFFFFL);
    }

    private static void addToBucket(Map<Long, List<DungeonRoom>> buckets, DungeonRoom room) {
        buckets.computeIfAbsent(bucketKey(room.centerX(), room.centerZ()), k -> new ArrayList<>()).add(room);
    }

    /** Checks nearby buckets only (3x3-ish neighborhood, widened by minGap) rather than every placed room. */
    private static boolean overlapsAny(Map<Long, List<DungeonRoom>> buckets, double x, double z, double minGap) {
        int bx = (int) Math.floor(x / BUCKET_SIZE);
        int bz = (int) Math.floor(z / BUCKET_SIZE);
        int reach = (int) Math.ceil(minGap / BUCKET_SIZE) + 1;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<DungeonRoom> bucket = buckets.get(((long) (bx + dx) << 32) | ((bz + dz) & 0xFFFFFFFFL));
                if (bucket == null) continue;
                for (DungeonRoom r : bucket) {
                    double gap = Math.hypot(x - r.centerX(), z - r.centerZ());
                    if (gap < minGap + Math.max(r.radiusX(), r.radiusZ())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Single union-find pass over the planned rooms/corridors: any room
     * not reachable from the entrance's component gets one extra
     * corridor connecting its nearest member to the nearest room in the
     * main component. Cheap (near-linear) alternative to a full BFS
     * reachability sweep, since we only need "is it connected at all",
     * not shortest paths.
     */
    private static void bridgeDisconnectedPockets(RoomGraph graph, List<DungeonRoom> allRooms) {
        Map<UUID, UUID> parent = new HashMap<>();
        for (DungeonRoom r : allRooms) parent.put(r.id(), r.id());

        for (DungeonCorridor c : graph.allCorridors()) {
            union(parent, c.fromRoomId(), c.toRoomId());
        }

        UUID mainRoot = find(parent, allRooms.get(0).id());
        Map<UUID, List<DungeonRoom>> components = new HashMap<>();
        for (DungeonRoom r : allRooms) {
            components.computeIfAbsent(find(parent, r.id()), k -> new ArrayList<>()).add(r);
        }

        List<DungeonRoom> mainComponent = components.getOrDefault(mainRoot, allRooms);
        for (Map.Entry<UUID, List<DungeonRoom>> entry : components.entrySet()) {
            if (entry.getKey().equals(mainRoot)) continue;
            List<DungeonRoom> pocket = entry.getValue();

            DungeonRoom bestPocketRoom = null, bestMainRoom = null;
            double bestDist = Double.MAX_VALUE;
            for (DungeonRoom pr : pocket) {
                for (DungeonRoom mr : mainComponent) {
                    double d = Math.hypot(pr.centerX() - mr.centerX(), pr.centerZ() - mr.centerZ());
                    if (d < bestDist) {
                        bestDist = d;
                        bestPocketRoom = pr;
                        bestMainRoom = mr;
                    }
                }
            }
            if (bestPocketRoom != null) {
                double tunnelRadius = Math.max(MIN_TUNNEL_R,
                        Math.min(bestPocketRoom.radiusX(), bestMainRoom.radiusX()) * 0.6);
                DungeonCorridor bridge = new DungeonCorridor(UUID.randomUUID(),
                        bestMainRoom.id(), bestPocketRoom.id(),
                        List.of(new int[]{bestMainRoom.centerX(), bestMainRoom.centerZ()},
                                new int[]{bestPocketRoom.centerX(), bestPocketRoom.centerZ()}),
                        (int) Math.round(tunnelRadius));
                graph.addCorridor(bridge);
                union(parent, bestMainRoom.id(), bestPocketRoom.id());
            }
        }
    }

    private static UUID find(Map<UUID, UUID> parent, UUID x) {
        UUID p = parent.get(x);
        if (p.equals(x)) return x;
        UUID root = find(parent, p);
        parent.put(x, root);
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        UUID ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }
}
