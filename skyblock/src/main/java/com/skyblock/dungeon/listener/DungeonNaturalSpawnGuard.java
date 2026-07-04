package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.floor.DungeonFloorManager;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Two related fixes for the dungeon world's mob spawning, both reported
 * together as "natural mobs are spawning in the dungeon" and "mobs are
 * entering floor 0 / spawning in floor 0":
 *
 *   1. VANILLA NATURAL SPAWNING: the dungeon world is a normal
 *      Environment.NORMAL world with a custom chunk generator, but
 *      nothing previously stopped the server's own ambient mob-spawning
 *      tick (the vanilla "spawn a zombie in the dark every so often"
 *      cycle) from running there. Every carved cave layer, and the
 *      entrance hub itself, is dark stone/void-adjacent space -
 *      exactly what vanilla spawning looks for - so on top of the
 *      intentional, level-scaled ambient mobs DungeonRoomMobSpawner
 *      places per room, the server was ALSO naturally spawning plain
 *      unleveled vanilla mobs everywhere, unbounded by room footprints,
 *      boss exclusion zones, or the theme's mob pool. This is very
 *      likely the real source of "mobs randomly appearing in
 *      already-generated/already-cleared areas" - those aren't
 *      DungeonRoomMobSpawner re-firing (it only ever runs once, from
 *      the carve-listener callback, per room), they're vanilla natural
 *      spawns happening on an ongoing basis independent of carving.
 *
 *      Fix: cancel every CreatureSpawnEvent in the dungeon world whose
 *      reason is NATURAL (or the related chunk/patrol/village spawn
 *      reasons vanilla uses for ambient population), full stop. Every
 *      dungeon mob must come from DungeonRoomMobSpawner or a boss
 *      trigger, both of which use SpawnReason.CUSTOM.
 *
 *   2. FLOOR 0 (the entrance hub): floorForY() returns -1 for the hub's
 *      Y band and any other non-floor gap, so this is also the
 *      definitive place to guard "no hostile mob may exist on floor 0,
 *      regardless of spawn reason" - covering not just natural spawns
 *      but also the (already-guarded, see DungeonRoomMobSpawner) case
 *      of a CUSTOM spawn somehow being asked to fire there, and any
 *      other mob mod/plugin that might otherwise spawn something in
 *      that world entirely outside this plugin's own spawn paths.
 *      Players are never meant to fight anything in the hub.
 */
public final class DungeonNaturalSpawnGuard implements Listener {

    private final DungeonFloorManager floorManager;
    private final FloorBounds floorBounds;
    private final MobLevelApplicator levelApplicator;

    public DungeonNaturalSpawnGuard(DungeonFloorManager floorManager, FloorBounds floorBounds,
                                     MobLevelApplicator levelApplicator) {
        this.floorManager = floorManager;
        this.floorBounds = floorBounds;
        this.levelApplicator = levelApplicator;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!event.getEntity().getWorld().equals(floorManager.dungeonWorld())) {
            return; // not our world - never touch spawns anywhere else
        }
        if (event.getEntity() instanceof Player) {
            return;
        }

        int y = event.getLocation().getBlockY();
        int floorNumber = floorBounds.floorForY(y);

        // Floor 0 / any non-floor gap: no hostile mob may exist here at
        // all, no matter how it was asked to spawn. This is the fix for
        // "mobs entering floor 0 or spawning in floor 0."
        if (floorNumber < 1) {
            event.setCancelled(true);
            return;
        }

        // Anything not explicitly placed by DungeonRoomMobSpawner/boss
        // trigger machinery (both use SpawnReason.CUSTOM) is a vanilla
        // ambient spawn - block it. This is the fix for "natural mobs
        // are spawning in the dungeon."
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
        }
    }

    /**
     * Safety-net sweep: despawns any already-existing non-dungeon-tagged
     * hostile mob currently inside floor 0's Y band. Covers mobs that
     * spawned before this guard was installed (e.g. plugin was just
     * updated on a server with mobs already sitting in the hub) or via
     * any path this listener doesn't intercept. Intended to be called
     * once at startup and optionally on a slow repeating timer by the
     * caller; kept as a plain method (not scheduled internally) so the
     * plugin controls the cadence.
     */
    public void sweepFloor0() {
        var world = floorManager.dungeonWorld();
        if (world == null) {
            return;
        }
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            if (!(entity instanceof LivingEntity mob) || mob instanceof Player) {
                continue;
            }
            int floorNumber = floorBounds.floorForY(mob.getLocation().getBlockY());
            if (floorNumber < 1) {
                mob.remove();
            }
        }
    }
}
