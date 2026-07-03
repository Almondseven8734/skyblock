package com.skyblock.dungeon.floor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.Set;

/**
 * Tracks boss kills per floor against that floor's required boss count
 * (FloorTheme.getBossCount() - normally 1, 2 on milestone floors).
 *
 * Once the required count is reached, the floor is considered cleared
 * and the caller (floor manager) should trigger staircase generation.
 * This class only tracks counts/identity of which boss entities have
 * died - it does not spawn staircases itself.
 */
public final class BossKillTracker {

    private final Map<Integer, AtomicInteger> killsByFloor = new ConcurrentHashMap<>();
    private final Map<Integer, Set<UUID>> killedBossIds = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> clearedFloors = new ConcurrentHashMap<>();

    /**
     * Records a boss kill on the given floor.
     *
     * @param floorNumber  the floor the boss died on
     * @param bossEntityId the boss entity's UUID, to prevent double-counting
     *                     the same boss if a death event somehow fires twice
     * @param requiredBossCount the floor's total required boss count for clearing
     * @return true if this kill caused the floor to become newly cleared
     *         (i.e. this was the kill that crossed the threshold - caller
     *         should trigger staircase generation exactly once, on this signal)
     */
    public boolean recordBossKill(int floorNumber, UUID bossEntityId, int requiredBossCount) {
        Set<UUID> killed = killedBossIds.computeIfAbsent(floorNumber, f -> ConcurrentHashMap.newKeySet());

        // Guard against double-counting the same boss entity.
        if (!killed.add(bossEntityId)) {
            return false;
        }

        AtomicInteger count = killsByFloor.computeIfAbsent(floorNumber, f -> new AtomicInteger(0));
        int newCount = count.incrementAndGet();

        boolean alreadyCleared = clearedFloors.getOrDefault(floorNumber, false);
        if (!alreadyCleared && newCount >= requiredBossCount) {
            clearedFloors.put(floorNumber, true);
            return true;
        }
        return false;
    }

    public boolean isFloorCleared(int floorNumber) {
        return clearedFloors.getOrDefault(floorNumber, false);
    }

    /**
     * Marks a floor as cleared without going through recordBossKill -
     * used exclusively by DungeonFloorStateStorage when restoring state
     * after a restart/crash. If floor N+1 was unlocked before the crash,
     * floor N's boss MUST already be dead (unlockFloor only ever gets
     * called from the "just cleared" branch), so this lets the restore
     * path re-establish that fact without a fake death event.
     */
    public void markFloorCleared(int floorNumber) {
        clearedFloors.put(floorNumber, true);
    }

    /** Every floor number currently recorded as cleared - used for state persistence. */
    public Set<Integer> clearedFloorNumbers() {
        return Set.copyOf(clearedFloors.keySet());
    }

    public int getKillCount(int floorNumber) {
        AtomicInteger count = killsByFloor.get(floorNumber);
        return count == null ? 0 : count.get();
    }

    /** Resets all tracked state - used on the weekly dungeon reset. */
    public void resetAll() {
        killsByFloor.clear();
        killedBossIds.clear();
        clearedFloors.clear();
    }
}
