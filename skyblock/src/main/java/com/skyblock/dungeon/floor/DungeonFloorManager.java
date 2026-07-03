package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.gen.DungeonCarveScheduler;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.gen.DungeonRoomPlanner;
import com.skyblock.dungeon.gen.RoomGraph;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Logger;

/**
 * Central coordinator for all per-floor dungeon state: lazily creates
 * and owns each floor's RoomGraph + DungeonRoomPlanner + theme +
 * StaircasePlacementValidator, tracks which floors are unlocked, and
 * is the single entry point player-tracking code calls into every
 * tick to drive "generate near active frontiers" behavior.
 *
 * This class deliberately does NOT decide when a floor becomes
 * cleared or when staircases physically spawn - that event wiring
 * (boss death -> validate placements -> build staircases -> unlock
 * next floor) belongs to the upcoming boss/staircase trigger
 * orchestrator, which will call unlockFloor(...) and read the
 * per-floor accessors exposed here. This class only owns floor
 * lifecycle/state bookkeeping and frontier-driven generation dispatch.
 *
 * One instance of this class exists for the whole dungeon (one shared
 * world, per design) and lives for the dungeon's full one-week cycle
 * until resetAll() is called.
 */
public final class DungeonFloorManager {

    private World dungeonWorld;
    private final FloorBounds floorBounds;
    private final FloorThemeRegistry themeRegistry;
    private final BossKillTracker bossKillTracker = new BossKillTracker();
    private final Logger logger;
    private final java.util.Random random;
    private final DungeonCarveScheduler carveScheduler;

    /** Floor 1's origin XZ - every subsequent floor shares the same XZ, stacked directly below. */
    private final double floor1OriginX;
    private final double floor1OriginZ;

    private final Set<Integer> unlockedFloors = new ConcurrentSkipListSet<>();
    private final ConcurrentHashMap<Integer, RoomGraph> roomGraphs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, DungeonRoomPlanner> planners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, StaircasePlacementValidator> staircaseValidators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, DungeonRoom> bossRooms = new ConcurrentHashMap<>();

    public DungeonFloorManager(World dungeonWorld, FloorBounds floorBounds, FloorThemeRegistry themeRegistry,
                                double floor1OriginX, double floor1OriginZ,
                                Logger logger, java.util.Random random, DungeonCarveScheduler carveScheduler) {
        this.dungeonWorld = dungeonWorld;
        this.floorBounds = floorBounds;
        this.themeRegistry = themeRegistry;
        this.floor1OriginX = floor1OriginX;
        this.floor1OriginZ = floor1OriginZ;
        this.logger = logger;
        this.random = random;
        this.carveScheduler = carveScheduler;

        // Floor 1 is the only floor open at the start of the week.
        unlockedFloors.add(1);
        placeBossRoom(1, getOrCreatePlanner(1));
    }

    // ─── Floor lifecycle ────────────────────────────────────────────────────

    public boolean isFloorUnlocked(int floorNumber) {
        return unlockedFloors.contains(floorNumber);
    }

    /**
     * Marks a floor as unlocked for the whole server (shared progression -
     * once unlocked, anyone can walk down, never teleport). Idempotent.
     */
    public void unlockFloor(int floorNumber) {
        if (unlockedFloors.add(floorNumber)) {
            logger.info("[Dungeon] Floor " + floorNumber + " unlocked for the server.");
            // Eagerly create its room graph/planner now so buffer rooms can
            // be registered into it the moment staircases are placed above.
            DungeonRoomPlanner planner = getOrCreatePlanner(floorNumber);
            placeBossRoom(floorNumber, planner);
        }
    }

    /**
     * Boss room placement is fully independent of how much terrain has
     * generated, per design - a fast/lucky group could stumble onto it
     * early. We pick a random point "a ways away" from the floor's
     * origin (40-90% of the generation leash) so it's never trivially
     * at the entrance, then register it as a real room in the graph;
     * DungeonRoomPlanner carves it like any other room once a frontier
     * reaches it.
     */
    private void placeBossRoom(int floorNumber, DungeonRoomPlanner planner) {
        double angle = random.nextDouble() * Math.PI * 2;
        double distance = FloorBounds.GENERATION_RADIUS * (0.4 + random.nextDouble() * 0.5);
        int x = (int) Math.round(floor1OriginX + Math.cos(angle) * distance);
        int z = (int) Math.round(floor1OriginZ + Math.sin(angle) * distance);

        int radiusX = 8 + random.nextInt(5);
        int radiusZ = 8 + random.nextInt(5);

        DungeonRoom bossRoom = planner.registerBossRoom(x, z, radiusX, radiusZ);
        bossRooms.put(floorNumber, bossRoom);

        // Queue the boss room's own footprint for carving right now,
        // urgent priority, independent of any player's position. Boss
        // rooms used to only get carved incidentally whenever a
        // player's ordinary frontier radius happened to sweep over
        // them - a player who reached the boss room's XZ bounds before
        // that had happened would trigger DungeonBossRoomTrigger with
        // no open column to spawn into, and the boss would silently
        // never spawn. This guarantees the room is carved (or already
        // mid-carve, a few ticks out) well before anyone can walk there.
        planner.enqueueBossRoomAreaUrgent(dungeonWorld, bossRoom);

        logger.info("[Dungeon] Floor " + floorNumber + " boss room placed at (" + x + ", " + z + ").");
    }

    /** The registered boss room for a floor, or null if that floor hasn't been unlocked/placed yet. */
    public DungeonRoom getBossRoom(int floorNumber) {
        return bossRooms.get(floorNumber);
    }

    public Set<Integer> unlockedFloors() {
        return Set.copyOf(unlockedFloors);
    }

    // ─── Per-floor component access (lazy) ──────────────────────────────────

    public RoomGraph getOrCreateRoomGraph(int floorNumber) {
        return roomGraphs.computeIfAbsent(floorNumber, RoomGraph::new);
    }

    private com.skyblock.dungeon.gen.DungeonRoomPlanner.RoomCarveListener globalCarveListener;

    /**
     * Registers a single carve listener applied to every floor's planner
     * (existing and future). Used to wire mob spawning, chest loot
     * placement, and boss room triggers without those systems needing
     * direct references to every per-floor planner instance.
     */
    public void setGlobalRoomCarveListener(com.skyblock.dungeon.gen.DungeonRoomPlanner.RoomCarveListener listener) {
        this.globalCarveListener = listener;
        planners.values().forEach(p -> p.setRoomCarveListener(listener));
    }

    public DungeonRoomPlanner getOrCreatePlanner(int floorNumber) {
        return planners.computeIfAbsent(floorNumber, f -> {
            RoomGraph graph = getOrCreateRoomGraph(f);
            FloorTheme theme = themeRegistry.getTheme(f);
            DungeonRoomPlanner planner = new DungeonRoomPlanner(graph, floorBounds, f, floor1OriginX, floor1OriginZ, theme, logger, random, carveScheduler);
            if (globalCarveListener != null) {
                planner.setRoomCarveListener(globalCarveListener);
            }
            return planner;
        });
    }

    public StaircasePlacementValidator getOrCreateStaircaseValidator(int floorNumber) {
        return staircaseValidators.computeIfAbsent(floorNumber, f -> {
            RoomGraph graph = getOrCreateRoomGraph(f);
            return new StaircasePlacementValidator(
                    (floorNum, x, z) -> graph.roomContaining((int) Math.round(x), (int) Math.round(z)) != null
            );
        });
    }

    public BossKillTracker bossKillTracker() {
        return bossKillTracker;
    }

    public FloorTheme getTheme(int floorNumber) {
        return themeRegistry.getTheme(floorNumber);
    }

    public FloorBounds floorBounds() {
        return floorBounds;
    }

    public World dungeonWorld() {
        return dungeonWorld;
    }

    /** Floor 1's origin X - needed by anything detecting the Floor 0 hub → Floor 1 crossing. */
    public double floor1OriginX() {
        return floor1OriginX;
    }

    /** Floor 1's origin Z - needed by anything detecting the Floor 0 hub → Floor 1 crossing. */
    public double floor1OriginZ() {
        return floor1OriginZ;
    }

    /**
     * Repoints this manager at a freshly (re)created dungeon World.
     * dungeonWorld was a `final` field before this fix - after
     * DungeonResetScheduler unloaded/deleted/recreated the world during
     * a reset, Bukkit hands back a brand-new World object (same name,
     * different instance), but every carve/frontier call in this class
     * kept using the old, now-unloaded World reference forever, silently
     * no-oping on block edits. Call this right after the reset creates
     * the new World, before any player can trigger generation again.
     */
    public void setDungeonWorld(World dungeonWorld) {
        this.dungeonWorld = dungeonWorld;
    }

    // ─── Frontier-driven generation ─────────────────────────────────────────

    /**
     * Called every tick (or on movement, throttled by the caller) for
     * every player currently inside the dungeon. Dispatches into that
     * floor's planner so real dungeon content carves in just ahead of
     * them, per the two-tier generation model.
     *
     * No-ops if the floor isn't unlocked - players physically cannot be
     * on a locked floor since staircases down don't exist until it's
     * unlocked, but this guard keeps the call safe regardless of caller.
     */
    public void onPlayerFrontier(int floorNumber, double x, double z) {
        if (!isFloorUnlocked(floorNumber)) {
            return;
        }
        DungeonRoomPlanner planner = getOrCreatePlanner(floorNumber);
        planner.planAndCarveNear(dungeonWorld, (int) Math.round(x), (int) Math.round(z));
    }

    /**
     * Called by the staircase orchestrator once a buffer room location is
     * known beneath a newly placed staircase, registering it as both a
     * real room and an active generation frontier on the floor below.
     */
    public void registerBufferRoomFrontier(int floorBelow, int x, int z) {
        DungeonRoomPlanner planner = getOrCreatePlanner(floorBelow);
        planner.registerBufferRoom(x, z);
        planner.planAndCarveNear(dungeonWorld, x, z);
    }

    // ─── Weekly reset ───────────────────────────────────────────────────────

    /** Wipes all in-memory floor state for the weekly dungeon reset. The world itself is regenerated separately. */
    public void resetAll() {
        unlockedFloors.clear();
        unlockedFloors.add(1);
        roomGraphs.clear();
        planners.clear();
        staircaseValidators.values().forEach(StaircasePlacementValidator::reset);
        staircaseValidators.clear();
        bossRooms.clear();
        bossKillTracker.resetAll();
        placeBossRoom(1, getOrCreatePlanner(1));
        logger.info("[Dungeon] Weekly reset complete - floor state cleared, Floor 1 unlocked.");
    }
}
