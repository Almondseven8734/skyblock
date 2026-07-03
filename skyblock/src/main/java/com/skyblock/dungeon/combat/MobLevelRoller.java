package com.skyblock.dungeon.combat;

import java.util.Random;

/**
 * Rolls a 1-100 mob level for a spawn, weighted by floor depth.
 *
 * Each floor gets a level "window" [windowMin, windowMax] that shifts
 * upward with depth, and rolls within that window are squared-biased
 * toward the low end so that most mobs on any given floor are near
 * the window floor, with high-window rolls being comparatively rare -
 * this is what makes floor 1 "mostly level 1-8 zombies with the
 * occasional level 15" rather than a flat random spread.
 *
 * Windows are sized off FloorBounds.maxFloorCount() (18 on a standard
 * -64..320 world) so the level curve automatically stretches or
 * compresses to fit however many floors actually exist, rather than
 * being hand-tuned to exactly 18.
 */
public final class MobLevelRoller {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 100;

    /** Ambient mobs roll anywhere in the floor's window, biased low via the squared curve. */
    private static final double AMBIENT_BIAS_POWER = 2.0;

    /**
     * Bosses roll only in the top third of the floor's window, still
     * squared-biased low within that narrowed band - so a boss is
     * always the strongest thing on its floor without every boss on a
     * floor being max-window every time.
     */
    private static final double BOSS_WINDOW_FLOOR_FRACTION = 2.0 / 3.0;
    private static final double BOSS_BIAS_POWER = 1.5;

    private final int maxFloorCount;
    private final Random random;

    public MobLevelRoller(int maxFloorCount, Random random) {
        if (maxFloorCount < 1) {
            throw new IllegalArgumentException("maxFloorCount must be >= 1");
        }
        this.maxFloorCount = maxFloorCount;
        this.random = random;
    }

    /** Rolls a level for an ordinary ambient/room mob on the given floor. */
    public int rollAmbientLevel(int floorNumber) {
        int[] window = windowForFloor(floorNumber);
        return rollBiasedInRange(window[0], window[1], AMBIENT_BIAS_POWER);
    }

    /** Rolls a level for a floor's boss, always at or above the floor's ambient mobs. */
    public int rollBossLevel(int floorNumber) {
        int[] window = windowForFloor(floorNumber);
        int bossMin = window[0] + (int) Math.round((window[1] - window[0]) * BOSS_WINDOW_FLOOR_FRACTION);
        bossMin = Math.min(bossMin, window[1]);
        return rollBiasedInRange(bossMin, window[1], BOSS_BIAS_POWER);
    }

    /**
     * The [min, max] level window for a floor: base level climbs ~5-6
     * per floor and the window widens slightly with depth so deeper
     * floors have more level variance among their mobs, both clamped
     * to [MIN_LEVEL, MAX_LEVEL].
     */
    int[] windowForFloor(int floorNumber) {
        double depthFraction = clamp01((floorNumber - 1) / (double) Math.max(1, maxFloorCount - 1));

        int base = MIN_LEVEL + (int) Math.round(depthFraction * (MAX_LEVEL - 12 - MIN_LEVEL));
        int width = 12 + (int) Math.round(depthFraction * 18); // 12 wide on floor 1, up to ~30 on the last floor

        int min = clampLevel(base);
        int max = clampLevel(base + width);
        if (max < min) max = min;
        return new int[]{min, max};
    }

    private int rollBiasedInRange(int min, int max, double biasPower) {
        if (max <= min) return clampLevel(min);
        double t = Math.pow(random.nextDouble(), biasPower); // biasPower > 1 skews toward 0
        int rolled = min + (int) Math.round(t * (max - min));
        return clampLevel(rolled);
    }

    private static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
