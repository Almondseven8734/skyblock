package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.gen.RoomGraph;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Wires together the full "boss dies -> floor clears -> staircases
 * spawn -> next floor unlocks" sequence. This is the last big piece:
 * it owns no generation logic of its own (that's DungeonRoomPlanner)
 * and no floor/graph storage of its own (that's DungeonFloorManager)
 * - it's purely the event-driven glue between BossKillTracker,
 * StaircasePlacementValidator, and the floor manager's frontier hooks.
 *
 * Boss entities must be registered via registerBoss(...) when spawned
 * (by whatever spawns them - MobBuffApplicator for buffed-vanilla
 * floors, or a MilestoneBoss subclass for scripted floors) so this
 * orchestrator knows which floor a given death belongs to.
 */
public final class DungeonStaircaseOrchestrator implements Listener {

    /**
     * Random range for how many staircases spawn when a floor clears.
     * Previously 3-8, which combined with the ~2000-block generation
     * leash meant staircases were a needle-in-a-haystack find on foot -
     * that's the actual reason none turned up in playtests, not a
     * placement bug. ~100 spreads enough of them around that players
     * exploring a cleared floor have a realistic chance of running into
     * one without walking the entire leash radius.
     */
    private static final int MIN_STAIRCASES = 90;
    private static final int MAX_STAIRCASES = 110;

    /**
     * How many candidate points to try per staircase before giving up on
     * that slot. Raised from 50 alongside the staircase count bump: with
     * ~100 targets all needing 40+ blocks of separation from each other,
     * later staircases in the batch have far fewer valid open slots left
     * among already-carved rooms, so they need more attempts to still
     * reliably land one.
     */
    private static final int MAX_PLACEMENT_ATTEMPTS_PER_STAIRCASE = 150;

    private final DungeonFloorManager floorManager;
    private final Logger logger;
    private final Random random;

    /** Tracks which floor a live boss entity belongs to, so its death event can be attributed correctly. */
    private final Map<UUID, Integer> bossEntityFloors = new ConcurrentHashMap<>();

    public DungeonStaircaseOrchestrator(DungeonFloorManager floorManager, Logger logger, Random random) {
        this.floorManager = floorManager;
        this.logger = logger;
        this.random = random;
    }

    /**
     * Registers a live boss entity as belonging to a floor, so its
     * eventual death is attributed to that floor's BossKillTracker.
     * Must be called at spawn time by whatever code creates the boss
     * (buffed-vanilla mob spawner or a MilestoneBoss instance).
     */
    public void registerBoss(UUID bossEntityId, int floorNumber) {
        bossEntityFloors.put(bossEntityId, floorNumber);
    }

    // ─── Event entry point ──────────────────────────────────────────────────

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Integer floorNumber = bossEntityFloors.remove(entity.getUniqueId());
        if (floorNumber == null) {
            return; // not a tracked dungeon boss
        }

        FloorTheme theme = floorManager.getTheme(floorNumber);
        boolean justCleared = floorManager.bossKillTracker()
                .recordBossKill(floorNumber, entity.getUniqueId(), theme.getBossCount());

        if (justCleared) {
            logger.info("[Dungeon] Floor " + floorNumber + " cleared - generating staircases.");
            onFloorCleared(floorNumber);
        }
    }

    // ─── Floor clear handling ───────────────────────────────────────────────

    private void onFloorCleared(int floorNumber) {
        RoomGraph graph = floorManager.getOrCreateRoomGraph(floorNumber);
        StaircasePlacementValidator validator = floorManager.getOrCreateStaircaseValidator(floorNumber);
        World world = floorManager.dungeonWorld();
        FloorBounds bounds = floorManager.floorBounds();

        List<DungeonRoom> carvedRooms = new ArrayList<>();
        for (DungeonRoom room : graph.allRooms()) {
            if (room.isCarved() && room.type() != DungeonRoom.Type.BOSS) {
                carvedRooms.add(room);
            }
        }

        if (carvedRooms.isEmpty()) {
            logger.warning("[Dungeon] Floor " + floorNumber + " cleared but no carved rooms exist yet - "
                    + "queuing a single staircase at the floor's origin as a fallback.");
            placeStaircase(floorNumber, world, bounds, 0, 0, validator);
            unlockNextFloor(floorNumber);
            return;
        }

        int targetCount = MIN_STAIRCASES + random.nextInt(MAX_STAIRCASES - MIN_STAIRCASES + 1);
        int placed = 0;

        for (int i = 0; i < targetCount; i++) {
            boolean success = false;
            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS_PER_STAIRCASE; attempt++) {
                DungeonRoom room = carvedRooms.get(random.nextInt(carvedRooms.size()));
                int x = room.centerX() + random.nextInt(room.radiusX() * 2 + 1) - room.radiusX();
                int z = room.centerZ() + random.nextInt(room.radiusZ() * 2 + 1) - room.radiusZ();

                if (validator.isValidPlacement(floorNumber, x, z)) {
                    validator.recordPlacement(x, z);
                    placeStaircase(floorNumber, world, bounds, x, z, validator);
                    success = true;
                    break;
                }
            }
            if (success) {
                placed++;
            }
        }

        logger.info("[Dungeon] Floor " + floorNumber + ": placed " + placed + "/" + targetCount + " staircases.");
        logSampleCoordinates(floorNumber, validator);
        unlockNextFloor(floorNumber);
    }

    /**
     * Logs a handful of the staircases just placed so their real
     * in-world coordinates are easy to find in the server log for
     * verification, instead of having to stumble onto one on foot.
     */
    private void logSampleCoordinates(int floorNumber, StaircasePlacementValidator validator) {
        List<double[]> all = validator.getPlacedStaircases();
        if (all.isEmpty()) return;
        int sampleSize = Math.min(5, all.size());
        List<double[]> shuffled = new ArrayList<>(all);
        java.util.Collections.shuffle(shuffled, random);
        StringBuilder sb = new StringBuilder("[Dungeon] Floor " + floorNumber + " sample staircase coords: ");
        for (int i = 0; i < sampleSize; i++) {
            double[] c = shuffled.get(i);
            int borderY = floorManager.floorBounds().floorBottomY(floorNumber) - 1;
            sb.append(String.format("(%.0f, %d, %.0f)", c[0], borderY, c[1]));
            if (i < sampleSize - 1) sb.append(", ");
        }
        logger.info(sb.toString());
    }

    /**
     * Carves a physical staircase shaft from floor N down through the
     * border into floor N+1, and registers the buffer room directly
     * beneath it as an active generation frontier on the next floor,
     * per "each staircase has its own buffer room directly at the
     * bottom of the staircase."
     *
     * If the target chunk isn't loaded yet, Bukkit's getBlockAt still
     * resolves (loading it synchronously); for a fully async-safe
     * version this should be queued via a ChunkLoadEvent check instead -
     * left as a follow-up since correctness here matters more than the
     * loading strategy for a first pass.
     */
    private void placeStaircase(int floorNumber, World world, FloorBounds bounds, int x, int z,
                                 StaircasePlacementValidator validator) {
        int floorBottomY = bounds.floorBottomY(floorNumber);
        int borderY = floorBottomY - 1; // the 1-block border separating this floor from the one below
        int floorBelowTopY = bounds.floorTopY(floorNumber + 1);

        // Punch a hole through this floor's bottom border so the shaft connects through.
        world.getBlockAt(x, borderY, z).setType(Material.AIR);

        // Vanilla ladder shaft down to the top of the floor below.
        for (int y = floorBottomY - 2; y >= floorBelowTopY; y--) {
            world.getBlockAt(x, y, z).setType(Material.LADDER);
        }

        // Register the buffer room on the floor below, directly beneath this staircase.
        floorManager.registerBufferRoomFrontier(floorNumber + 1, x, z);
    }

    private void unlockNextFloor(int floorNumber) {
        int nextFloor = floorNumber + 1;
        if (floorManager.floorBounds().maxFloorCount() >= nextFloor) {
            floorManager.unlockFloor(nextFloor);
        } else {
            logger.info("[Dungeon] Floor " + floorNumber + " was the deepest possible floor - no further floor to unlock.");
        }
    }
}
