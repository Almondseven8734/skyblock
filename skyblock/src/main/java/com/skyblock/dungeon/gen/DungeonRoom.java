package com.skyblock.dungeon.gen;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A single node in a floor's room/corridor graph.
 *
 * Rooms are irregular "bubbly" blobs (see SdfShapes.irregularRoomSDF),
 * not flat-floored boxes - centerX/centerZ/radiusX/radiusZ still
 * describe an axis-aligned bounding footprint (used by spawn-locating,
 * boss-exclusion-radius math, and persistence, none of which need the
 * exact blob shape), but the actual carved shape additionally varies
 * per-column based on floorY (the blob's vertical center, which can
 * sit anywhere within the floor's playable band, not just on the
 * floor), domeH (vertical radius of the blob), and noiseSeed (which
 * seeds the per-room angular/lobe noise so every room's blobbiness is
 * distinct - see SdfShapes for the actual math).
 */
public final class DungeonRoom {

    public enum Type {
        NORMAL,
        BUFFER,      // landing room directly beneath a staircase from the floor above
        BOSS,
        CHEST,
        ENTRANCE,    // the room the floor's staircase/gateway from above opens into
        JUNCTION,    // small connective node, mid-tunnel branch point or bridge stitch
        DEAD_END
    }

    private final UUID id;
    private final int centerX;
    private final int centerZ;
    private final int radiusX;
    private final int radiusZ;
    private final Type type;
    private final Set<UUID> connectedRoomIds = new LinkedHashSet<>();

    /**
     * Vertical center of this room's SDF blob, in world Y. Unlike the
     * old flat-floor carver (which always sat every room on the same
     * floorBottomY + SOLID_FLOOR_LAYERS plane), a bubbly room's belly
     * can sit anywhere within the floor's playable band - see
     * DungeonGraphPlanner's placement logic for how this is chosen and
     * clamped so the blob always fits within the floor slot.
     */
    private final double floorY;

    /** Vertical radius of the SDF blob (see SdfShapes.irregularRoomSDF's domeH parameter). */
    private final double domeH;

    /** Per-room seed for the SDF's angular lobe/noise perturbation, so no two rooms are identically shaped. */
    private final int noiseSeed;

    private boolean carved = false;
    private boolean looted = false;
    private boolean cleared = false;

    public DungeonRoom(UUID id, int centerX, int centerZ, int radiusX, int radiusZ, Type type) {
        this(id, centerX, centerZ, radiusX, radiusZ, type, centerX, radiusX, 0);
        // Fallback constructor: floorY/domeH/noiseSeed given nominal
        // defaults derived from the other params for callers that don't
        // care about SDF shape (e.g. legacy BUFFER room registration).
    }

    public DungeonRoom(UUID id, int centerX, int centerZ, int radiusX, int radiusZ, Type type,
                        double floorY, double domeH, int noiseSeed) {
        if (radiusX <= 0 || radiusZ <= 0) {
            throw new IllegalArgumentException("Room radii must be positive");
        }
        this.id = id;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.type = type;
        this.floorY = floorY;
        this.domeH = domeH;
        this.noiseSeed = noiseSeed;
    }

    public boolean containsXZ(int x, int z) {
        return Math.abs(x - centerX) <= radiusX && Math.abs(z - centerZ) <= radiusZ;
    }

    /** Euclidean distance from this room's center to a point, in the XZ plane. */
    public double distanceTo(int x, int z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public void connectTo(UUID otherRoomId) {
        connectedRoomIds.add(otherRoomId);
    }

    public UUID id() { return id; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int radiusX() { return radiusX; }
    public int radiusZ() { return radiusZ; }
    public Type type() { return type; }
    public Set<UUID> connectedRoomIds() { return connectedRoomIds; }

    public double floorY() { return floorY; }
    public double domeH() { return domeH; }
    public int noiseSeed() { return noiseSeed; }

    public boolean isCarved() { return carved; }
    public void markCarved() { this.carved = true; }

    public boolean isLooted() { return looted; }
    public void markLooted() { this.looted = true; }

    public boolean isCleared() { return cleared; }
    public void markCleared() { this.cleared = true; }
}
