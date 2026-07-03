package com.skyblock.dungeon.progression;

/**
 * Pure math for the character-level XP curve. No Bukkit dependency on
 * purpose, same as FloorBounds - unit-testable in isolation.
 *
 * Character level is entirely separate from vanilla Minecraft level;
 * DungeonXpListener repurposes the vanilla XP bar as a *visual*
 * display of this system while a player is in the dungeon, but the
 * underlying number here is our own.
 */
public final class CharacterLevelCurve {

    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 60;

    /** XP required to go from level N to N+1 scales roughly as N^1.45, tuned so early levels are fast and late levels are a real grind. */
    private static final double CURVE_BASE = 45.0;
    private static final double CURVE_EXPONENT = 1.45;

    /** Flat skill points granted on every level-up. */
    private static final int SKILL_POINTS_PER_LEVEL = 1;
    /** Extra bonus skill points granted every MILESTONE_INTERVAL levels (10, 20, 30...). */
    private static final int MILESTONE_BONUS_POINTS = 2;
    private static final int MILESTONE_INTERVAL = 10;

    private CharacterLevelCurve() {
    }

    /** XP required to advance from `level` to `level + 1`. Returns 0 at/above MAX_LEVEL (nothing further to earn). */
    public static long xpToNextLevel(int level) {
        if (level >= MAX_LEVEL) {
            return 0L;
        }
        return Math.round(CURVE_BASE * Math.pow(level, CURVE_EXPONENT)) + 20L;
    }

    /** Skill points awarded for reaching `newLevel` (called once per level gained). */
    public static int skillPointsForReaching(int newLevel) {
        int points = SKILL_POINTS_PER_LEVEL;
        if (newLevel % MILESTONE_INTERVAL == 0) {
            points += MILESTONE_BONUS_POINTS;
        }
        return points;
    }
}
