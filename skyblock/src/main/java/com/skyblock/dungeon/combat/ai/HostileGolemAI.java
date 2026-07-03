package com.skyblock.dungeon.combat.ai;

import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Vanilla iron golems are neutral - they only fight back once attacked
 * or once defending a village they belong to. As a dungeon mob they're
 * meant to be straightforwardly hostile, so this periodically forces
 * the golem's attack target to the nearest player in range; vanilla
 * melee AI handles the actual attacking once a target is set.
 *
 * Self-cancels once the golem dies/despawns, same pattern as
 * BatSwoopAI - one instance per spawned golem, no global registry.
 */
public final class HostileGolemAI extends BukkitRunnable {

    private static final double AGGRO_RANGE = 16.0;
    private static final long PERIOD_TICKS = 20L; // re-check target once a second

    private final IronGolem golem;

    private HostileGolemAI(IronGolem golem) {
        this.golem = golem;
    }

    public static void start(JavaPlugin plugin, IronGolem golem) {
        HostileGolemAI ai = new HostileGolemAI(golem);
        ai.runTaskTimer(plugin, 0L, PERIOD_TICKS);
    }

    @Override
    public void run() {
        if (!golem.isValid() || golem.isDead()) {
            cancel();
            return;
        }

        if (golem.getTarget() != null && golem.getTarget().isValid() && !golem.getTarget().isDead()) {
            return; // already chasing someone
        }

        Player nearest = findNearestPlayer();
        if (nearest != null) {
            golem.setTarget(nearest);
        }
    }

    private Player findNearestPlayer() {
        Player nearest = null;
        double nearestDistSq = AGGRO_RANGE * AGGRO_RANGE;
        for (Player player : golem.getWorld().getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double distSq = player.getLocation().distanceSquared(golem.getLocation());
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }
}
