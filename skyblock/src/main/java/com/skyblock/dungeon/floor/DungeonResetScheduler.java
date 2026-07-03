package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.gen.DungeonCarveScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Drives the dungeon's weekly reset cycle: every player still inside
 * gets ejected to spawn (with their items - this is a scheduled
 * server reset, not a death, so no item loss applies), the dungeon
 * world is torn down and recreated from scratch under a brand-new
 * folder name with a fresh StoneBufferGenerator, and
 * DungeonFloorManager.resetAll() clears all in-memory floor/graph/boss
 * state to match.
 *
 * Why a NEW folder name every reset, instead of deleting and
 * recreating "dungeon" in place: earlier fixes here tried unloading,
 * verifying deletion with retries, and even renaming the old folder
 * aside before recreating under the same name - all of which still
 * left recreation happening at the exact same absolute path the old
 * world had just occupied. That's the trap. Paper/Bukkit's region-file
 * and chunk caching is keyed by path, not by World instance, so a
 * world recreated at a path that was JUST vacated can end up resolving
 * chunk reads through a stale cached handle instead of the genuinely
 * fresh bytes on disk - which looks exactly like "the reset ran, but
 * the old world is still there" (including manually-dug holes that
 * fresh generation could never reproduce on its own). Giving every
 * reset a folder name nobody has ever used before removes the shared
 * path entirely, so there's nothing for any caching layer to collide
 * with. See DungeonWorldNameStore for how the current name survives
 * a server restart mid-cycle.
 *
 * Call start() once during plugin onEnable; call cancel() in
 * onDisable to avoid a dangling task across reloads.
 */
public final class DungeonResetScheduler {

    /** One week, expressed in ticks (20 ticks/sec * 60 * 60 * 24 * 7). */
    public static final long RESET_INTERVAL_TICKS = 20L * 60 * 60 * 24 * 7;

    private final JavaPlugin plugin;
    private final DungeonFloorManager floorManager;
    private final File dataFolder;
    private final ChunkGeneratorFactory generatorFactory;
    private final java.util.function.Supplier<org.bukkit.Location> spawnLocationSupplier;
    private final DungeonCarveScheduler carveScheduler;
    private final Logger logger;

    private int taskId = -1;

    /**
     * Fired with the brand-new World instance once a reset finishes
     * recreating it. DungeonFloorManager.dungeonWorld is repointed
     * automatically before this fires (see performReset), but every
     * other component that cached a Location/World tied to the old
     * dungeon world (the entrance, the portal bounds, the block
     * protection listener) needs the same treatment, and only the
     * caller (SkyblockPlugin) knows about all of them - hence the
     * callback rather than this class reaching into unrelated systems.
     */
    private Consumer<World> onWorldRecreated = w -> { };

    /**
     * Fired once per player as they're ejected to spawn during a reset,
     * right after their teleport. performReset() only ever moved the
     * player's physical body - it never touched their persisted
     * DungeonPlayerState (isInsideDungeon flag + last coordinates). If a
     * player logs out any time before walking through the portal again,
     * DungeonJoinQuitListener trusts that stale persisted state and force-
     * teleports them straight back into the dungeon world at their old
     * coordinates on rejoin - coordinates that, post-reset, sit inside
     * solid unstructured stone. Wire this to clear + persist each
     * ejected player's dungeon state the same way the portal exit does.
     */
    private Consumer<Player> onPlayerEjected = p -> { };

    /**
     * Builds the world's chunk generator fresh each reset (a new
     * StoneBufferGenerator instance bound to Floor 1's origin) - supplied
     * as a factory rather than a single instance since ChunkGenerator
     * state shouldn't be reused across a world delete/recreate cycle.
     */
    @FunctionalInterface
    public interface ChunkGeneratorFactory {
        ChunkGenerator create();
    }

    /**
     * @param dataFolder the plugin's data folder, used to persist which
     *                    world folder name is currently live (see
     *                    DungeonWorldNameStore) so a server restart
     *                    mid-cycle doesn't fall back to the stale
     *                    default name.
     */
    public DungeonResetScheduler(JavaPlugin plugin, DungeonFloorManager floorManager, File dataFolder,
                                  ChunkGeneratorFactory generatorFactory,
                                  java.util.function.Supplier<org.bukkit.Location> spawnLocationSupplier,
                                  DungeonCarveScheduler carveScheduler,
                                  Logger logger) {
        this.plugin = plugin;
        this.floorManager = floorManager;
        this.dataFolder = dataFolder;
        this.generatorFactory = generatorFactory;
        this.spawnLocationSupplier = spawnLocationSupplier;
        this.carveScheduler = carveScheduler;
        this.logger = logger;
    }

    /**
     * Registers a callback invoked with the freshly recreated dungeon
     * World right after a reset completes. Set this once during plugin
     * wiring, before start() or any manual performReset() call (e.g.
     * via /dungeon reset).
     */
    public void setOnWorldRecreated(Consumer<World> onWorldRecreated) {
        this.onWorldRecreated = onWorldRecreated != null ? onWorldRecreated : (w -> { });
    }

    /** Registers a callback fired for each player ejected to spawn during a reset. */
    public void setOnPlayerEjected(Consumer<Player> onPlayerEjected) {
        this.onPlayerEjected = onPlayerEjected != null ? onPlayerEjected : (p -> { });
    }

    /** Schedules the recurring weekly reset. Safe to call once at plugin startup. */
    public void start() {
        if (taskId != -1) {
            return; // already scheduled
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin, this::performReset, RESET_INTERVAL_TICKS, RESET_INTERVAL_TICKS
        );
        logger.info("[Dungeon] Weekly reset scheduled (every " + RESET_INTERVAL_TICKS + " ticks).");
    }

    /** Cancels the scheduled reset task, e.g. on plugin disable. */
    public void cancel() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /**
     * Forces an immediate reset, e.g. via an admin command, outside the
     * normal weekly schedule.
     *
     * The actual unload/recreate is deferred one tick after the
     * ejection teleports: Bukkit.unloadWorld() refuses to unload a world
     * that still has players in it, and a same-tick check right after
     * calling player.teleport() across worlds isn't guaranteed to see
     * the transfer as fully complete yet. Without the delay, unloadWorld
     * can spuriously return false, silently aborting the whole reset
     * (world never recreated, floor state already wiped) - which looks
     * exactly like "the reset didn't work" from the console/logs.
     */
    public void performReset() {
        logger.info("[Dungeon] Starting weekly dungeon reset...");

        World oldWorld = floorManager.dungeonWorld();
        org.bukkit.Location spawnLoc = spawnLocationSupplier.get();

        // Eject every player currently inside the dungeon world to spawn,
        // keeping their items - this is a scheduled reset, not a death.
        for (Player player : oldWorld.getPlayers()) {
            player.teleport(spawnLoc);
            player.sendMessage("§6The dungeon is resetting for the week - you've been returned to spawn.");
            onPlayerEjected.accept(player);
        }

        floorManager.resetAll();

        Bukkit.getScheduler().runTask(plugin, () -> finishReset(oldWorld));
    }

    /** How many extra attempts to give a stubborn file/directory before giving up on it (background cleanup only). */
    private static final int DELETE_RETRY_ATTEMPTS = 5;

    /** Backoff between delete retries - region files can stay open briefly after unloadWorld() returns. */
    private static final long DELETE_RETRY_DELAY_MS = 100L;

    private void finishReset(World oldWorld) {
        String oldWorldName = oldWorld.getName();

        // Drop any carve jobs still sitting in the scheduler that target
        // this world before it goes away. Without this, a job enqueued
        // moments earlier (e.g. from a player's frontier, or - before the
        // DungeonFloorManager fix - from resetAll's boss room placement)
        // would still be in the queue when unloadWorld() below runs, and
        // draining it afterwards throws "Chunk system has shut down" -
        // exactly the exception in the crash log.
        carveScheduler.purgeWorld(oldWorld);

        boolean unloaded = Bukkit.unloadWorld(oldWorld, false);
        if (!unloaded) {
            logger.warning("[Dungeon] Failed to unload dungeon world '" + oldWorldName + "' - reset aborted, "
                    + "in-memory floor state was already cleared but the old world is still active. "
                    + "This usually means a player (or entity holding a reference) is still in the world - "
                    + "check for anyone who reconnected mid-reset.");
            return;
        }

        // The new world gets a folder name that has never existed before
        // on this server - not a fixed literal reused every time. That's
        // the actual fix: it guarantees nothing (on-disk data, an open
        // file handle, or an internal cache keyed by path) can carry over
        // from any old world, because nothing ever shared this exact path
        // before. No race with any old folder's deletion to gamble on,
        // because recreation doesn't depend on anything being gone first.
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        String newWorldName = DungeonWorldNameStore.generateNextName(seed);
        DungeonWorldNameStore.save(dataFolder, newWorldName, logger);

        World freshWorld = new WorldCreator(newWorldName)
                .generator(generatorFactory.create())
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();

        if (freshWorld == null) {
            logger.severe("[Dungeon] Failed to recreate dungeon world '" + newWorldName + "' after reset! "
                    + "The old world '" + oldWorldName + "' was already unloaded and is not being restored - "
                    + "manual intervention needed.");
            return;
        }

        // Repoint the floor manager at the new World instance before
        // anything else can touch it - onWorldRecreated below may
        // immediately rebuild the hub, and generation could start
        // firing the moment a player reconnects.
        floorManager.setDungeonWorld(freshWorld);
        onWorldRecreated.accept(freshWorld);

        logger.info("[Dungeon] Weekly dungeon reset complete - now running under new world folder '"
                + newWorldName + "'.");

        // Sweep the world container for every folder that matches our
        // naming scheme and is older than the cycle we just started, and
        // prune all of them in the background - not just the single
        // folder we know we just replaced. A plain "delete the previous
        // folder" approach silently leaks disk space forever the moment
        // any one cleanup fails (a crash mid-delete, a locked file that
        // outlasts the retry budget, a server restart between resets) -
        // this sweep is self-healing across cycles instead of depending
        // on every single prior cleanup having succeeded.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> pruneOldDungeonWorlds(newWorldName));
    }

    /**
     * Finds every world folder matching DungeonWorldNameStore's naming
     * scheme whose encoded date is older than {@code currentWorldName}'s,
     * and deletes them. Purely best-effort background cleanup - nothing
     * live depends on this succeeding, since the dungeon is already
     * running under {@code currentWorldName} by the time this runs.
     */
    private void pruneOldDungeonWorlds(String currentWorldName) {
        java.util.Optional<java.time.Instant> currentInstant = DungeonWorldNameStore.extractInstant(currentWorldName);
        if (currentInstant.isEmpty()) {
            // We generated this name ourselves - shouldn't happen - but if
            // it somehow doesn't parse, don't risk pruning against a bad
            // reference point.
            logger.warning("[Dungeon] Could not parse a date out of the world name we just created ('"
                    + currentWorldName + "') - skipping old-world pruning this cycle.");
            return;
        }

        File container = Bukkit.getWorldContainer();
        File[] candidates = container.listFiles(File::isDirectory);
        if (candidates == null) {
            return;
        }

        for (File candidate : candidates) {
            String name = candidate.getName();
            if (name.equals(currentWorldName)) {
                continue; // never prune the world we're actively running
            }

            java.util.Optional<java.time.Instant> candidateInstant = DungeonWorldNameStore.extractInstant(name);
            if (candidateInstant.isEmpty()) {
                // Doesn't match our naming scheme at all - e.g. "world",
                // "world_nether", a pre-this-fix legacy "dungeon" folder,
                // or something unrelated entirely. Never touch it.
                continue;
            }

            if (!candidateInstant.get().isBefore(currentInstant.get())) {
                // Same age or (shouldn't happen) newer than what we just
                // made - leave it alone rather than guess.
                continue;
            }

            if (Bukkit.getWorld(name) != null) {
                // Defensive: never delete a folder backing a currently
                // loaded World object, no matter what its name/date says.
                continue;
            }

            if (deleteRecursively(candidate)) {
                logger.info("[Dungeon] Pruned old dungeon world folder '" + name + "'.");
            } else {
                logger.warning("[Dungeon] Old dungeon world folder '" + candidate.getPath() + "' could not be "
                        + "fully pruned this cycle - functionally harmless, it'll be retried on the next reset.");
            }
        }
    }

    /**
     * Deletes a file or directory tree, retrying each individual delete a
     * few times with a short backoff to ride out files that are still
     * briefly locked by async chunk-save I/O right after unloadWorld().
     * Purely best-effort background cleanup now - nothing live depends
     * on its result.
     */
    private boolean deleteRecursively(java.io.File file) {
        if (!file.exists()) {
            return true;
        }

        boolean allChildrenDeleted = true;
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                allChildrenDeleted &= deleteRecursively(child);
            }
        }

        if (!allChildrenDeleted) {
            return false;
        }

        if (file.delete()) {
            return true;
        }

        for (int attempt = 0; attempt < DELETE_RETRY_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(DELETE_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (file.delete()) {
                return true;
            }
        }

        logger.warning("[Dungeon] Could not delete '" + file.getPath() + "' after " + DELETE_RETRY_ATTEMPTS
                + " retries - still locked or in use.");
        return false;
    }
}
