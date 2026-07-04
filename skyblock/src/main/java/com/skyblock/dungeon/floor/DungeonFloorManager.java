package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.gen.DungeonCarveScheduler;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.gen.DungeonRoomPlanner;
import com.skyblock.dungeon.gen.RoomGraph;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.World;

import java.util.List;
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
        this(dungeonWorld, floorBounds, themeRegistry, floor1OriginX, floor1OriginZ, logger, random,
                carveScheduler, true);
    }

    /**
     * @param autoInitializeFloor1 when true (the normal case), the
     *        constructor immediately unlocks floor 1 and places a fresh
     *        random boss room for it, exactly as before. Pass false when
     *        the caller is about to call applySnapshot() with restored
     *        state from a previous run - in that case a fresh random
     *        boss room would just be discarded/replaced anyway, wasting
     *        a real carve pass at a location nothing will ever use.
     */
    public DungeonFloorManager(World dungeonWorld, FloorBounds floorBounds, FloorThemeRegistry themeRegistry,
                                double floor1OriginX, double floor1OriginZ,
                                Logger logger, java.util.Random random, DungeonCarveScheduler carveScheduler,
                                boolean autoInitializeFloor1) {
        this.dungeonWorld = dungeonWorld;
        this.floorBounds = floorBounds;
        this.themeRegistry = themeRegistry;
        this.floor1OriginX = floor1OriginX;
        this.floor1OriginZ = floor1OriginZ;
        this.logger = logger;
        this.random = random;
        this.carveScheduler = carveScheduler;

        if (autoInitializeFloor1) {
            // Floor 1 is the only floor open at the start of the week.
            unlockedFloors.add(1);
            placeBossRoom(1, getOrCreatePlanner(1));
        }
    }

    /**
     * Restores previously-persisted floor state (unlocked floors, boss
     * rooms, carved chunks, staircase placements, boss-kill status) after
     * a server restart. Must be called once, right after construction
     * with autoInitializeFloor1=false, before any player can move or
     * trigger generation. Order matters: carved chunks are restored
     * BEFORE boss room carve jobs are (re-)enqueued, so already-carved
     * boss room chunks are correctly skipped instead of re-carved.
     */
    public void applySnapshot(DungeonFloorStateStorage.FloorSnapshot snapshot) {
        unlockedFloors.addAll(snapshot.unlockedFloors());

        for (var entry : snapshot.bossRooms().entrySet()) {
            int floor = entry.getKey();
            DungeonFloorStateStorage.BossRoomRecord r = entry.getValue();
            DungeonRoomPlanner planner = getOrCreatePlanner(floor);
            DungeonRoom room = planner.registerBossRoom(r.x(), r.z(), r.radiusX(), r.radiusZ());
            bossRooms.put(floor, room);
        }

        for (var entry : snapshot.carvedChunks().entrySet()) {
            getOrCreatePlanner(entry.getKey()).restoreCarvedChunks(entry.getValue());
        }

        // Now safe to (re-)enqueue boss room carve scans: chunks already
        // carved before the restart were just restored above, so this is
        // a cheap no-op scan for them and only actually carves anything
        // for a boss room that genuinely never finished carving.
        for (Integer floor : snapshot.bossRooms().keySet()) {
            DungeonRoom room = bossRooms.get(floor);
            if (room != null) {
                getOrCreatePlanner(floor).enqueueBossRoomAreaUrgent(dungeonWorld, room);
            }
        }

        for (var entry : snapshot.staircases().entrySet()) {
            getOrCreateStaircaseValidator(entry.getKey()).restorePlacements(entry.getValue());
        }

        for (Integer clearedFloor : snapshot.clearedFloors()) {
            bossKillTracker.markFloorCleared(clearedFloor);
        }

        logger.info("[Dungeon] Restored persisted state: " + snapshot.unlockedFloors().size() + " floor(s) unlocked, "
                + snapshot.bossRooms().size() + " boss room(s), "
                + snapshot.carvedChunks().values().stream().mapToInt(Set::size).sum() + " carved chunk(s), "
                + snapshot.staircases().values().stream().mapToInt(List::size).sum() + " staircase(s).");
    }

    /** Every floor number that currently has a registered boss room - used for state persistence. */
    public Set<Integer> bossRoomFloors() {
        return Set.copyOf(bossRooms.keySet());
    }

    /** Every floor number that currently has an active (created) planner - used for state persistence. */
    public Set<Integer> activeFloorNumbers() {
        return Set.copyOf(planners.keySet());
    }

    /** Every floor number that currently has a staircase validator - used for state persistence. */
    public Set<Integer> staircaseValidatorFloors() {
        return Set.copyOf(staircaseValidators.keySet());
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
     * Boss room placement now simply adopts the floor's own preplanned
     * boss room - DungeonGraphPlanner already places exactly one BOSS
     * room per floor as part of the graph (on a corridor branch, far
     * from the entrance, fully connected, carved via SDF). This method
     * used to independently roll its own random (x, z) and register a
     * SECOND, unrelated BOSS room via planner.registerBossRoom(x, z,
     * ...) - since that roll had no knowledge of where the graph's own
     * boss room actually landed, the two would only coincide by luck.
     * When they didn't coincide, the graph's real boss room still got
     * carved (bubbly SDF, fully connected) while this method's random
     * roll ALSO created a second, disconnected, drum-carved BOSS room
     * at an arbitrary location - occasionally right next to the
     * entrance - and only the second (most-recently-registered) one
     * ever got treated as "the" boss room by getBossRoom()/
     * DungeonBossRoomTrigger, even though it had none of the graph's
     * carving/connectivity guarantees. Now there is exactly one boss
     * room per floor, period: whatever DungeonGraphPlanner planned.
     */
    private void placeBossRoom(int floorNumber, DungeonRoomPlanner planner) {
        // Idempotency guard: still worth keeping, since unlockFloor()
        // and setDungeonWorld()'s post-reset re-placement can both call
        // this for the same floor number.
        if (bossRooms.containsKey(floorNumber)) {
            logger.warning("[Dungeon] placeBossRoom(" + floorNumber + ") called but a boss room is already "
                    + "registered for this floor - skipping duplicate placement.");
            return;
        }

        DungeonRoom bossRoom = planner.plannedBossRoom();
        if (bossRoom == null) {
            logger.warning("[Dungeon] Floor " + floorNumber + " has no preplanned boss room in its graph - "
                    + "this should not happen; DungeonGraphPlanner should always place exactly one.");
            return;
        }
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

        logger.info("[Dungeon] Floor " + floorNumber + " boss room adopted from planned graph at ("
                + bossRoom.centerX() + ", " + bossRoom.centerZ() + ").");
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

            // The graph planner needs the floor's REAL doorway location
            // so it plants the guaranteed ENTRANCE room (and the
            // corridors that branch off it) exactly where players
            // actually walk in - not at some independently-guessed
            // point. Using the planner's built-in default here was the
            // bug behind "it's building the dungeon inside floor 0 and
            // not carving anywhere": that default assumed the entrance
            // was on the west edge of the disc, but Floor 1's hub
            // gateway is actually on the EAST side
            // (originX + FLOOR_0_TO_FLOOR_1_OFFSET, see
            // DungeonHubBuilder.gatewayPoint()) - so the entire graph
            // got planned anchored 4000 blocks away from where the real
            // doorway breaks through, leaving that doorway's chunks with
            // no nearby planned room/corridor at all and therefore
            // nothing for carveChunkColumn to ever open.
            //
            // TODO: floors above 1 don't yet have a wired staircase-exit
            // position to pass here (see DungeonRoomPlanner's class doc
            // on the explicit-entrance constructor) - they still fall
            // through to DungeonRoomPlanner's own fallback, which is now
            // at least correctly east-anchored but isn't tied to any
            // specific floor's real staircase landing spot yet. Wire the
            // actual per-floor staircase exit through here once that
            // position is tracked/available (DungeonStaircaseOrchestrator
            // already knows it at placement time).
            double[] entrance = (f == 1)
                    ? com.skyblock.dungeon.floor.DungeonHubBuilder.gatewayPoint((int) floor1OriginX, (int) floor1OriginZ)
                    : null;

            DungeonRoomPlanner planner = (entrance != null)
                    ? new DungeonRoomPlanner(graph, floorBounds, f, floor1OriginX, floor1OriginZ, theme,
                            logger, random, carveScheduler, entrance[0], entrance[1])
                    : new DungeonRoomPlanner(graph, floorBounds, f, floor1OriginX, floor1OriginZ, theme,
                            logger, random, carveScheduler);

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

        // If resetAll() ran before this (the normal reset order - see
        // DungeonResetScheduler.performReset/finishReset), Floor 1 is
        // unlocked but has no boss room yet: placeBossRoom() was
        // deliberately NOT called from resetAll(), because at that
        // point dungeonWorld was still the OLD world instance (about to
        // be unloaded) - enqueueing the boss room's carve jobs against
        // it meant those jobs either threw "chunk system has shut down"
        // once the old world was unloaded, or in the best case carved
        // the wrong World object entirely. Either way the boss room's
        // chunk keys got marked as queued/carved and would never be
        // retried against the real new world, so the boss room was
        // permanently uncarvable and the boss could never spawn. Placing
        // it here instead, now that dungeonWorld correctly points at the
        // freshly created world, is the actual fix.
        if (unlockedFloors.contains(1) && bossRooms.get(1) == null) {
            placeBossRoom(1, getOrCreatePlanner(1));
        }
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

    /**
     * Wipes all in-memory floor state for the weekly dungeon reset. The
     * world itself is regenerated separately, and critically, AFTER this
     * method returns (see DungeonResetScheduler.performReset ->
     * finishReset) - dungeonWorld here is still the old, soon-to-be-
     * unloaded World instance. Floor 1's boss room is deliberately NOT
     * re-placed here for that reason: doing so would enqueue its carve
     * jobs against the wrong World object. It's placed instead from
     * setDungeonWorld(), once dungeonWorld actually points at the fresh
     * world.
     */
    public void resetAll() {
        unlockedFloors.clear();
        unlockedFloors.add(1);
        roomGraphs.clear();
        planners.clear();
        staircaseValidators.values().forEach(StaircasePlacementValidator::reset);
        staircaseValidators.clear();
        bossRooms.clear();
        bossKillTracker.resetAll();
        logger.info("[Dungeon] Weekly reset complete - floor state cleared, Floor 1 unlocked "
                + "(boss room will be placed once the new world is live).");
    }
}
