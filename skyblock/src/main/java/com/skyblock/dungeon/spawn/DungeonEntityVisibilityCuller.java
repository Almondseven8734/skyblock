package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.floor.DungeonFloorManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Periodically hides dungeon mobs from a player's client (rather than
 * despawning or otherwise affecting them server-side) once they're
 * either more than VISIBLE_RANGE blocks away or have solid blocks
 * between them and the player - purely a render-cost reduction for
 * rooms packed with ambient mobs, since the 3x spawn-rate pass means
 * far more entities are alive per floor at once.
 *
 * Deliberately client-side only (Player#hideEntity/#showEntity): the
 * mob keeps ticking, pathing, and existing normally server-side the
 * whole time, so combat/AI/aggro are completely unaffected - a mob
 * that walks back into range or into the player's line of sight is
 * simply shown again next pass.
 */
public final class DungeonEntityVisibilityCuller {

    private static final double VISIBLE_RANGE = 10.0;
    /** How far out to even bother scanning for candidate mobs per player. */
    private static final double SCAN_RADIUS = 48.0;
    private static final long PERIOD_TICKS = 10L; // twice a second

    private final JavaPlugin plugin;
    private final DungeonFloorManager floorManager;
    private final MobLevelApplicator levelApplicator;

    /** Per-player set of entity UUIDs currently hidden from them, so we only call hide/show on actual state changes. */
    private final Map<UUID, Set<UUID>> hiddenByPlayer = new HashMap<>();

    private BukkitTask task;

    public DungeonEntityVisibilityCuller(JavaPlugin plugin, DungeonFloorManager floorManager,
                                          MobLevelApplicator levelApplicator) {
        this.plugin = plugin;
        this.floorManager = floorManager;
        this.levelApplicator = levelApplicator;
    }

    public void start() {
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        World dungeonWorld = floorManager.dungeonWorld();
        if (dungeonWorld == null) {
            return;
        }

        for (Player player : dungeonWorld.getPlayers()) {
            updateVisibilityFor(player, dungeonWorld);
        }

        // Drop bookkeeping for players who've left the dungeon world entirely,
        // so this map doesn't quietly grow across a long-running server.
        hiddenByPlayer.keySet().removeIf(uuid -> {
            Player p = plugin.getServer().getPlayer(uuid);
            return p == null || !p.getWorld().equals(dungeonWorld);
        });
    }

    private void updateVisibilityFor(Player player, World dungeonWorld) {
        Location eye = player.getEyeLocation();
        Set<UUID> currentlyHidden = hiddenByPlayer.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());

        Collection<org.bukkit.entity.Entity> nearby = dungeonWorld.getNearbyEntities(
                player.getLocation(), SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS
        );

        Set<UUID> stillCandidates = new HashSet<>();

        for (org.bukkit.entity.Entity entity : nearby) {
            if (!(entity instanceof LivingEntity mob) || mob instanceof Player) {
                continue;
            }
            if (levelApplicator.readLevel(mob) < 0) {
                continue; // not a dungeon-tagged mob, leave vanilla visibility alone
            }

            stillCandidates.add(mob.getUniqueId());
            boolean shouldHide = shouldHide(eye, mob);
            boolean isHidden = currentlyHidden.contains(mob.getUniqueId());

            if (shouldHide && !isHidden) {
                player.hideEntity(plugin, mob);
                currentlyHidden.add(mob.getUniqueId());
            } else if (!shouldHide && isHidden) {
                player.showEntity(plugin, mob);
                currentlyHidden.remove(mob.getUniqueId());
            }
        }

        // A previously-hidden mob that walked (or was carved) out of scan
        // range entirely won't appear in `nearby` anymore - make sure it
        // gets shown again rather than staying permanently hidden once it
        // re-enters range next time (its UUID would otherwise still be
        // marked hidden with no code path left to clear it).
        currentlyHidden.removeIf(uuid -> {
            if (stillCandidates.contains(uuid)) {
                return false;
            }
            org.bukkit.entity.Entity entity = plugin.getServer().getEntity(uuid);
            if (entity instanceof LivingEntity) {
                player.showEntity(plugin, entity);
            }
            return true;
        });
    }

    private boolean shouldHide(Location playerEye, LivingEntity mob) {
        Location mobEye = mob.getEyeLocation();
        double distance = playerEye.distance(mobEye);
        if (distance > VISIBLE_RANGE) {
            return true;
        }
        return isBlockedByTerrain(playerEye, mobEye, distance);
    }

    private boolean isBlockedByTerrain(Location from, Location to, double distance) {
        if (distance < 0.1) {
            return false;
        }
        org.bukkit.util.Vector direction = to.toVector().subtract(from.toVector()).normalize();
        RayTraceResult hit = from.getWorld().rayTraceBlocks(
                from, direction, distance - 0.05, FluidCollisionMode.NEVER, true
        );
        return hit != null && hit.getHitBlock() != null;
    }
}
