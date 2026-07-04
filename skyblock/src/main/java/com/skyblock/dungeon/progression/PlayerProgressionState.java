package com.skyblock.dungeon.progression;

import java.util.UUID;

/**
 * Per-player character progression: level, XP progress toward the
 * next level, and skill points earned/spent. Plain data holder -
 * persistence is handled by PlayerProgressionStorage, same split as
 * DungeonPlayerState/DungeonPlayerStateStorage.
 *
 * Character level is what future pieces (weapon tier gates, class
 * skill trees) key off of - this class only owns the number and the
 * XP curve bookkeeping, not what the level unlocks.
 */
public final class PlayerProgressionState {

    private final UUID playerId;
    private int characterLevel;
    private long currentXp; // progress toward the *next* level, not cumulative lifetime XP
    private int unspentSkillPoints;
    private int totalSkillPointsEarned;

    public PlayerProgressionState(UUID playerId) {
        this.playerId = playerId;
        this.characterLevel = CharacterLevelCurve.MIN_LEVEL;
        this.currentXp = 0L;
        this.unspentSkillPoints = 0;
        this.totalSkillPointsEarned = 0;
    }

    /**
     * Adds XP and rolls over as many level-ups as the amount covers
     * (a single big kill could span more than one level near the low
     * end of the curve). Returns how many levels were gained, 0 if
     * none (including the already-max-level case, where XP is simply
     * discarded since there's nowhere for it to go).
     */
    public int addXp(long amount) {
        if (characterLevel >= CharacterLevelCurve.MAX_LEVEL) {
            return 0;
        }
        currentXp += amount;
        int levelsGained = 0;

        while (characterLevel < CharacterLevelCurve.MAX_LEVEL) {
            long required = CharacterLevelCurve.xpToNextLevel(characterLevel);
            if (currentXp < required) {
                break;
            }
            currentXp -= required;
            characterLevel++;
            levelsGained++;

            int points = CharacterLevelCurve.skillPointsForReaching(characterLevel);
            unspentSkillPoints += points;
            totalSkillPointsEarned += points;
        }

        if (characterLevel >= CharacterLevelCurve.MAX_LEVEL) {
            currentXp = 0L; // no further curve to progress toward, don't let this grow unbounded
        }
        return levelsGained;
    }

    /** Fraction (0.0-1.0) of the way toward the next level, for XP-bar display. 1.0 at max level (bar reads full). */
    public float progressFraction() {
        if (characterLevel >= CharacterLevelCurve.MAX_LEVEL) {
            return 1.0f;
        }
        long required = CharacterLevelCurve.xpToNextLevel(characterLevel);
        if (required <= 0) {
            return 1.0f;
        }
        return (float) Math.min(1.0, Math.max(0.0, currentXp / (double) required));
    }

    public boolean spendSkillPoints(int amount) {
        if (amount <= 0 || amount > unspentSkillPoints) {
            return false;
        }
        unspentSkillPoints -= amount;
        return true;
    }

    /**
     * Wipes level/XP/unspent skill points back to zero. Used when a
     * player picks or switches their dungeon class, per design ("you
     * start at lvl 0 again" / "you lose all your levels when you
     * switch classes"). Deliberately goes below CharacterLevelCurve.
     * MIN_LEVEL (1) - that constant is the classless-leveling floor a
     * brand new player starts at, not a hard engine minimum, and the
     * XP curve math (xpToNextLevel, addXp) works fine starting from 0.
     * totalSkillPointsEarned is intentionally left untouched - it's a
     * lifetime stat, not a spendable balance.
     */
    public void resetToZero() {
        this.characterLevel = 0;
        this.currentXp = 0L;
        this.unspentSkillPoints = 0;
    }

    public UUID getPlayerId() { return playerId; }
    public int getCharacterLevel() { return characterLevel; }
    public long getCurrentXp() { return currentXp; }
    public int getUnspentSkillPoints() { return unspentSkillPoints; }
    public int getTotalSkillPointsEarned() { return totalSkillPointsEarned; }

    /** Restores exact saved values - used only by PlayerProgressionStorage when loading from disk. */
    void restore(int characterLevel, long currentXp, int unspentSkillPoints, int totalSkillPointsEarned) {
        this.characterLevel = characterLevel;
        this.currentXp = currentXp;
        this.unspentSkillPoints = unspentSkillPoints;
        this.totalSkillPointsEarned = totalSkillPointsEarned;
    }
}
