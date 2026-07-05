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
import org.bukkit.event.entity.SlimeSplitEvent;

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
 *      Y band and any other non-floor gap. The only mob allowed to
 *      exist there is DungeonHubBuilder's own Area Zero slime
 *      population (SpawnReason.CUSTOM, EntityType.SLIME) - every other
 *      spawn on floor 0, of any type or reason, is blocked. This
 *      covers not just natural spawns but also any other mob mod/
 *      plugin that might otherwise spawn something in that world
 *      entirely outside this plugin's own spawn paths.
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

        // Anything not explicitly placed by this plugin's own dungeon
        // spawn machinery (DungeonRoomMobSpawner, boss triggers, and
        // DungeonHubBuilder's Area Zero slimes all go through
        // World.spawnEntity(), which fires SpawnReason.CUSTOM) is a
        // vanilla ambient spawn, or a spawn from some other plugin/mob
        // mod entirely - block it everywhere in this world, floor 0
        // included. This is the fix for "natural mobs are spawning in
        // the dungeon" and "mobs that aren't from the dungeon can't
        // spawn in the dungeon world."
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) {
            event.setCancelled(true);
            return;
        }

        // Floor 0 (Area Zero) is a CUSTOM-spawn exception, not a
        // blanket ban: DungeonHubBuilder.spawnSlimes() intentionally
        // populates it with slimes via SpawnReason.CUSTOM, and that's
        // the only thing allowed to spawn there. Any other floor-0
        // CUSTOM spawn (e.g. DungeonRoomMobSpawner/boss triggers
        // firing on floor 0, which should never happen by construction
        // since both are keyed off floor >= 1 rooms, but is guarded
        // here defensively) is still blocked, since players are never
        // meant to fight anything but Area Zero's own slimes in the
        // hub.
        if (floorNumber < 1) {
            // Can't check DungeonHubBuilder's area_zero_slime PDC tag
            // here - it's stamped onto the entity AFTER
            // World.spawnEntity() returns, which is after this event
            // has already fired, so the tag is never present yet at
            // this point. EntityType.SLIME is a sufficient stand-in:
            // it's the only entity type this plugin's dungeon spawn
            // machinery ever places on floor 0 via SpawnReason.CUSTOM.
            if (event.getEntityType() != org.bukkit.entity.EntityType.SLIME) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Dungeon slimes (Area Zero's included) never split on death -
     * per design, a medium slime dying should just die, not spawn a
     * cluster of tiny slimes. Vanilla slime-split behavior is
     * otherwise unconditional on death, so this has to be blocked
     * explicitly rather than left as a side effect of size/AI changes.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent event) {
        if (!event.getEntity().getWorld().equals(floorManager.dungeonWorld())) {
            return; // not our world - never touch other worlds' slimes
        }
        event.setCancelled(true);
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
            if (mob instanceof org.bukkit.entity.Slime) {
                continue; // Area Zero's own slimes belong on floor 0 - never sweep those
            }
            int floorNumber = floorBounds.floorForY(mob.getLocation().getBlockY());
            if (floorNumber < 1) {
                mob.remove();
            }
        }
    }
}
