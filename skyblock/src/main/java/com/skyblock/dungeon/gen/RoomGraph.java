package com.skyblock.dungeon.gen;

import java.util.ArrayList;
import java.util.Collection;
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

    public RoomGraph(int floorNumber) {
        this.floorNumber = floorNumber;
    }

    public int floorNumber() { return floorNumber; }

    public void addRoom(DungeonRoom room) {
        rooms.put(room.id(), room);
    }

    public void addCorridor(DungeonCorridor corridor) {
        corridors.put(corridor.id(), corridor);
        DungeonRoom from = rooms.get(corridor.fromRoomId());
        DungeonRoom to = rooms.get(corridor.toRoomId());
        if (from != null) from.connectTo(corridor.toRoomId());
        if (to != null) to.connectTo(corridor.fromRoomId());
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

    /** Returns the room (if any) whose footprint contains this XZ point. */
    public DungeonRoom roomContaining(int x, int z) {
        for (DungeonRoom room : rooms.values()) {
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
     * ordinary cave noise.
     */
    public DungeonRoom nearestBossRoomWithin(double x, double z, double maxDist) {
        DungeonRoom best = null;
        double bestDist = Double.MAX_VALUE;
        for (DungeonRoom room : rooms.values()) {
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
