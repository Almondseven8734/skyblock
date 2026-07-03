package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.util.FloorBounds;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates candidate staircase placements on a cleared floor:
 *   - must be at least FloorBounds.MIN_STAIRCASE_SEPARATION blocks from every other staircase already placed
 *   - must fall inside a generated room (caller supplies the room-containment check,
 *     since "is this point inside a room" depends on the generation planner's room graph)
 *
 * This class only handles the separation bookkeeping; room-containment
 * is delegated via RoomContainmentCheck so this stays decoupled from
 * the generation planner's internals.
 */
public final class StaircasePlacementValidator {

    /** Supplied by the generation planner: true if (x, z) on the given floor falls inside a generated room. */
    public interface RoomContainmentCheck {
        boolean isInsideGeneratedRoom(int floorNumber, double x, double z);
    }

    private final RoomContainmentCheck roomContainmentCheck;
    private final List<double[]> placedStaircases = new ArrayList<>();

    public StaircasePlacementValidator(RoomContainmentCheck roomContainmentCheck) {
        this.roomContainmentCheck = roomContainmentCheck;
    }

    /**
     * @return true if this candidate location is valid: far enough from
     *         every previously accepted staircase AND inside a generated room.
     */
    public boolean isValidPlacement(int floorNumber, double x, double z) {
        if (!roomContainmentCheck.isInsideGeneratedRoom(floorNumber, x, z)) {
            return false;
        }
        for (double[] existing : placedStaircases) {
            double dx = existing[0] - x;
            double dz = existing[1] - z;
            double distSq = dx * dx + dz * dz;
            double minSepSq = (double) FloorBounds.MIN_STAIRCASE_SEPARATION * FloorBounds.MIN_STAIRCASE_SEPARATION;
            if (distSq < minSepSq) {
                return false;
            }
        }
        return true;
    }

    /**
     * Records an accepted staircase location so future candidates are
     * checked against it too. Caller should only call this after
     * isValidPlacement returned true for the same coordinates.
     */
    public void recordPlacement(double x, double z) {
        placedStaircases.add(new double[]{x, z});
    }

    /**
     * Bulk-restores previously accepted staircase locations from a
     * persisted snapshot (server restart/crash) so future placements on
     * this floor still respect MIN_STAIRCASE_SEPARATION against
     * staircases that existed before the restart, not just ones placed
     * in the current process lifetime.
     */
    public void restorePlacements(List<double[]> coords) {
        placedStaircases.addAll(coords);
    }

    public int placedCount() {
        return placedStaircases.size();
    }

    public List<double[]> getPlacedStaircases() {
        return List.copyOf(placedStaircases);
    }

    /** Clears all recorded placements - used when a floor resets at week boundary. */
    public void reset() {
        placedStaircases.clear();
    }
}
