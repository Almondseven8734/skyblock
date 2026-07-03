package com.skyblock.dungeon.combat.ai;

import org.bukkit.entity.Bat;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Vanilla bats are passive and never attack players, so dungeon bats
 * need a bolted-on AI: periodically scan for a nearby player, then
 * dive straight at them with a velocity burst and deal contact damage
 * once close enough, before resetting to scan again.
 *
 * One instance is started per spawned bat (see start()) and
 * self-cancels the moment the bat dies or despawns, so nothing needs
 * to track a global registry of active bats.
 */
public final class BatSwoopAI extends BukkitRunnable {

    private enum State { SCANNING, DIVING, COOLDOWN }

    private static final double DETECT_RANGE = 10.0;
    private static final double DIVE_TRIGGER_RANGE = 7.0;
    private static final double HIT_RANGE = 1.4;
    private static final double DIVE_SPEED = 1.1;
    private static final double DIVE_DAMAGE = 3.0;
    private static final int DIVE_MAX_TICKS = 30; // give up and re-scan if the dive drags on too long
    private static final int COOLDOWN_TICKS = 40;

    private final Bat bat;
    private State state = State.SCANNING;
    private Player currentTarget;
    private int stateTicks = 0;

    private BatSwoopAI(Bat bat) {
        this.bat = bat;
    }

    /** Starts the swoop AI loop for a bat, ticking every 2 ticks (10/sec). */
    public static void start(JavaPlugin plugin, Bat bat) {
        BatSwoopAI ai = new BatSwoopAI(bat);
        ai.runTaskTimer(plugin, 0L, 2L);
    }

    @Override
    public void run() {
        if (!bat.isValid() || bat.isDead()) {
            cancel();
            return;
        }

        switch (state) {
            case SCANNING -> scan();
            case DIVING -> dive();
            case COOLDOWN -> cooldown();
        }
    }

    private void scan() {
        Player nearest = findNearestPlayer(DETECT_RANGE);
        if (nearest == null) return;

        double distance = bat.getLocation().distance(nearest.getLocation());
        if (distance <= DIVE_TRIGGER_RANGE) {
            currentTarget = nearest;
            state = State.DIVING;
            stateTicks = 0;
        }
    }

    private void dive() {
        stateTicks++;
        if (currentTarget == null || !currentTarget.isValid() || currentTarget.isDead()
            || stateTicks > DIVE_MAX_TICKS) {
            enterCooldown();
            return;
        }

        Vector toTarget = currentTarget.getLocation().toVector()
            .subtract(bat.getLocation().toVector());
        double distance = toTarget.length();

        if (distance <= HIT_RANGE) {
            currentTarget.damage(DIVE_DAMAGE, bat);
            enterCooldown();
            return;
        }

        bat.setVelocity(toTarget.normalize().multiply(DIVE_SPEED));
    }

    private void cooldown() {
        stateTicks++;
        if (stateTicks >= COOLDOWN_TICKS) {
            state = State.SCANNING;
            currentTarget = null;
            stateTicks = 0;
        }
    }

    private void enterCooldown() {
        state = State.COOLDOWN;
        stateTicks = 0;
        currentTarget = null;
    }

    private Player findNearestPlayer(double range) {
        Player nearest = null;
        double nearestDistSq = range * range;
        for (Player player : bat.getWorld().getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double distSq = player.getLocation().distanceSquared(bat.getLocation());
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }
}
