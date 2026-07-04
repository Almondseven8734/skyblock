package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Floor bosses spawn "anchored" to their pedestal (AI disabled, see
 * DungeonBossRoomTrigger) so they stand still on it rather than
 * immediately wandering off - a boss is meant to be found standing on
 * its dais, not stumbled into mid-wander. The moment a boss takes any
 * damage from anything, this releases the anchor (re-enables AI) so it
 * can chase, reposition, and use its kit normally for the rest of the
 * fight. This only ever fires once per boss in practice since AI only
 * needs enabling the first time - re-enabling an already-enabled AI is
 * a harmless no-op.
 */
public final class DungeonBossAnchorListener implements Listener {

    private final MobLevelApplicator levelApplicator;

    public DungeonBossAnchorListener(MobLevelApplicator levelApplicator) {
        this.levelApplicator = levelApplicator;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!levelApplicator.isBoss(living)) {
            return;
        }
        if (!living.hasAI()) {
            living.setAI(true);
        }
    }
}
