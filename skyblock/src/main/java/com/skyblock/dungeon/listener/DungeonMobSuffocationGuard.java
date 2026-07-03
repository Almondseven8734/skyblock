package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Cancels suffocation ("in a wall") damage against dungeon-tagged mobs.
 *
 * Chunk carving is now scheduled asynchronously across several ticks
 * (see DungeonCarveScheduler) rather than happening synchronously the
 * instant a room is registered. An ambient mob can spawn via
 * DungeonSpawnLocator's verified-open-column check and then have a
 * neighboring column re-carve slightly late, or ride a moving frontier
 * edge, leaving it briefly embedded in not-yet-opened stone. Vanilla's
 * suffocation damage would kill it outright in that window - instead
 * we just no-op the damage here and let the carve scheduler catch up;
 * the mob unstucks itself once the surrounding stone opens.
 *
 * Only suppresses damage for mobs MobLevelApplicator has tagged as
 * dungeon mobs, so this has no effect on vanilla mobs/players anywhere
 * else on the server (including players, who should still suffocate
 * normally if they somehow end up in a wall).
 */
public final class DungeonMobSuffocationGuard implements Listener {

    private final MobLevelApplicator levelApplicator;

    public DungeonMobSuffocationGuard(MobLevelApplicator levelApplicator) {
        this.levelApplicator = levelApplicator;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSuffocationDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }
        if (levelApplicator.readLevel(livingEntity) < 0) {
            return; // not a dungeon mob - let vanilla suffocation rules apply
        }
        event.setCancelled(true);
    }
}
