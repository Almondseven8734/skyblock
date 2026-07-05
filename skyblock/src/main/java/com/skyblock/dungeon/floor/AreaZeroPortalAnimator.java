package com.skyblock.dungeon.floor;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Animates Area Zero's east-wall portal: every glass block
 * independently flickers between dark blue and dark purple stained
 * glass on its own fixed-length cycle (a random period + phase rolled
 * once per block at start()), rather than the whole wall swapping
 * color in lockstep.
 *
 * One BukkitTask runs per (re)built portal - callers MUST cancel the
 * previous task before calling start() again after a world reset,
 * or the old task keeps ticking against blocks in a World Bukkit has
 * already unloaded/deleted.
 */
public final class AreaZeroPortalAnimator {

    private static final Material COLOR_A = Material.BLUE_STAINED_GLASS;
    private static final Material COLOR_B = Material.PURPLE_STAINED_GLASS;

    /** Per-block cycle length range, in ticks - short enough to read as "flickering", not a slow fade. */
    private static final int MIN_PERIOD_TICKS = 15;
    private static final int MAX_PERIOD_TICKS = 50;
    private static final int RUN_INTERVAL_TICKS = 2;

    private AreaZeroPortalAnimator() {
    }

    private static final class Cell {
        final Block block;
        final int period;
        final int phase;

        Cell(Block block, int period, int phase) {
            this.block = block;
            this.period = period;
            this.phase = phase;
        }
    }

    /**
     * Starts flickering the given glass block coordinates (see
     * DungeonHubBuilder.portalGlassCoordinates) in the given world.
     * Returns the BukkitTask handle so the caller can cancel it later
     * (required before the next reset rebuilds the portal elsewhere).
     */
    public static BukkitTask start(JavaPlugin plugin, World world, List<int[]> glassCoordinates) {
        Random random = new Random();
        List<Cell> cells = new ArrayList<>();
        for (int[] coord : glassCoordinates) {
            int period = MIN_PERIOD_TICKS + random.nextInt(MAX_PERIOD_TICKS - MIN_PERIOD_TICKS + 1);
            int phase = random.nextInt(period);
            cells.add(new Cell(world.getBlockAt(coord[0], coord[1], coord[2]), period, phase));
        }

        long[] elapsedTicks = {0L};
        return plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            elapsedTicks[0] += RUN_INTERVAL_TICKS;
            for (Cell cell : cells) {
                // Each cell only flips once per its own period, at its
                // own phase offset - independent per-block timers rather
                // than a shared global toggle.
                if (Math.floorMod(elapsedTicks[0] + cell.phase, cell.period) < RUN_INTERVAL_TICKS) {
                    Material current = cell.block.getType();
                    Material next = (current == COLOR_A) ? COLOR_B : COLOR_A;
                    cell.block.setType(next, false);
                }
            }
        }, RUN_INTERVAL_TICKS, RUN_INTERVAL_TICKS);
    }
}
