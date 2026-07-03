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
 * either more than RENDER_RANGE blocks away or have solid blocks
 * between them and the player - purely a render-cost reduction for
 * rooms packed with ambient mobs, since the 3x spawn-rate pass means
 * far more entities are alive per floor at once. Independently of
 * that, name tags are only shown once a mob is within the closer
 * NAMETAG_RANGE - a mob can be fully rendered and walking around
 * without its tag showing yet.
 *
 * Deliberately client-side only for entity visibility
 * (Player#hideEntity/#showEntity): the mob keeps ticking, pathing, and
 * existing normally server-side the whole time, so combat/AI/aggro are
 * completely unaffected - a mob that walks back into range or into the
 * player's line of sight is simply shown again next pass.
 *
 * Name-tag visibility is NOT per-player, though - LivingEntity#setCustomNameVisible
 * is a single flag broadcast to every viewer, there's no vanilla API
 * for "show this tag to player A but not player B". So it's driven off
 * whichever player currently rendering the mob is closest to it: if
 * anyone who can currently see the mob is within NAMETAG_RANGE, the tag
 * shows for everyone who can see the mob. In a full dungeon party
 * (who are usually close together anyway) this reads correctly for
 * whoever's actually nearest; it's the one property that's genuinely
 * shared rather than fully per-player.
 */
public final class DungeonEntityVisibilityCuller {

    /** A mob farther than this from every player is hidden entirely, regardless of line of sight. */
    private static final double RENDER_RANGE = 20.0;
    /** Even a rendered mob only shows its name tag once some player is this close. */
    private static final double NAMETAG_RANGE = 10.0;
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

        // Closest distance, across all players, at which each mob is
        // currently being rendered to someone this tick - used below to
        // drive the (necessarily global, see class doc) nametag flag.
        // Only mobs some player can actually see get an entry.
        Map<UUID, Double> nearestRenderedDistance = new HashMap<>();

        for (Player player : dungeonWorld.getPlayers()) {
            updateVisibilityFor(player, dungeonWorld, nearestRenderedDistance);
        }

        applyNameTagVisibility(nearestRenderedDistance);

        // Drop bookkeeping for players who've left the dungeon world entirely,
        // so this map doesn't quietly grow across a long-running server.
        hiddenByPlayer.keySet().removeIf(uuid -> {
            Player p = plugin.getServer().getPlayer(uuid);
            return p == null || !p.getWorld().equals(dungeonWorld);
        });
    }

    private void updateVisibilityFor(Player player, World dungeonWorld, Map<UUID, Double> nearestRenderedDistance) {
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

            double distance = eye.distance(mob.getEyeLocation());
            boolean shouldHide = shouldHide(eye, mob, distance);
            boolean isHidden = currentlyHidden.contains(mob.getUniqueId());

            if (shouldHide && !isHidden) {
                player.hideEntity(plugin, mob);
                currentlyHidden.add(mob.getUniqueId());
            } else if (!shouldHide && isHidden) {
                player.showEntity(plugin, mob);
                currentlyHidden.remove(mob.getUniqueId());
            }

            if (!shouldHide) {
                nearestRenderedDistance.merge(mob.getUniqueId(), distance, Math::min);
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

    /**
     * Sets each currently-rendered mob's nametag on or off based on the
     * closest player who can see it this tick. Mobs nobody can currently
     * see are left alone entirely - their tag state doesn't matter to
     * anyone until they're rendered again, at which point this runs
     * fresh for them anyway.
     */
    private void applyNameTagVisibility(Map<UUID, Double> nearestRenderedDistance) {
        for (Map.Entry<UUID, Double> entry : nearestRenderedDistance.entrySet()) {
            org.bukkit.entity.Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (entity instanceof LivingEntity mob) {
                mob.setCustomNameVisible(entry.getValue() <= NAMETAG_RANGE);
            }
        }
    }

    /**
     * True if this mob should be hidden from this player entirely: either
     * it's farther than RENDER_RANGE, or - checked independently of
     * range - a solid block sits between the two. A mob 3 blocks away
     * through a wall is still hidden even though it's well within range.
     */
    private boolean shouldHide(Location playerEye, LivingEntity mob, double distance) {
        if (distance > RENDER_RANGE) {
            return true;
        }
        return isBlockedByTerrain(playerEye, mob.getEyeLocation(), distance);
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
