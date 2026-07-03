package com.skyblock.dungeon.gen;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Ticks a bounded number of chunk-column cave carves per server tick
 * instead of letting a single frontier/staircase event carve dozens
 * of chunks synchronously in one go.
 *
 * This is the fix for the "server thread stuck inside
 * DungeonRoomPlanner.carveChunkColumn / ServerLevel.sendBlockUpdated"
 * watchdog crash: DungeonRoomPlanner.planAndCarveNear used to carve
 * an entire ~9x9 chunk radius (81 chunk columns, each hundreds of
 * setType() calls with physics/light updates) fully synchronously on
 * the main thread, every time a player crossed a whole-block XZ
 * boundary. DungeonStaircaseOrchestrator.onFloorCleared then made it
 * worse by firing that same 9x9 carve 3-8 times in a row (once per
 * staircase's buffer-room frontier) inside a single EntityDeathEvent
 * handler call - easily 400+ fresh chunk carves in one tick, which
 * blocked the main thread long enough for Paper's watchdog to declare
 * the server hung and force-kill it (exit code 70 in the crash log).
 *
 * Now planAndCarveNear/registerBufferRoomFrontier only enqueue work
 * here; this class's own repeating task drains a small, fixed number
 * of chunk jobs off the queue every tick, so no single tick ever does
 * more than CHUNKS_PER_TICK chunk-columns' worth of block edits.
 *
 * Two priority lanes:
 *   - urgent: boss room footprints (queued the moment a boss room is
 *     registered, so it's guaranteed carved well before any player
 *     could possibly walk there) and the player's own standing chunk.
 *   - normal: look-ahead frontier chunks and buffer-room chunks below
 *     new staircases, where a few ticks of latency is invisible.
 * Urgent jobs always drain first.
 */
public final class DungeonCarveScheduler {

    /** Max chunk columns carved per server tick, across both lanes. */
    private static final int CHUNKS_PER_TICK = 6;

    private record Job(DungeonRoomPlanner planner, World world, int chunkX, int chunkZ) {}

    private final Deque<Job> urgent = new ArrayDeque<>();
    private final Deque<Job> normal = new ArrayDeque<>();

    private BukkitTask task;

    public void start(JavaPlugin plugin) {
        if (task != null) {
            return; // already running
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void enqueueUrgent(DungeonRoomPlanner planner, World world, int chunkX, int chunkZ) {
        urgent.add(new Job(planner, world, chunkX, chunkZ));
    }

    public void enqueueNormal(DungeonRoomPlanner planner, World world, int chunkX, int chunkZ) {
        normal.add(new Job(planner, world, chunkX, chunkZ));
    }

    /** True if this scheduler currently has no pending work for the given planner (used by tests/diagnostics). */
    public boolean isIdle() {
        return urgent.isEmpty() && normal.isEmpty();
    }

    private void tick() {
        int budget = CHUNKS_PER_TICK;
        budget = drain(urgent, budget);
        drain(normal, budget);
    }

    private int drain(Deque<Job> queue, int budget) {
        while (budget > 0 && !queue.isEmpty()) {
            Job job = queue.poll();
            job.planner().carveChunkColumnFromScheduler(job.world(), job.chunkX(), job.chunkZ());
            budget--;
        }
        return budget;
    }
}
