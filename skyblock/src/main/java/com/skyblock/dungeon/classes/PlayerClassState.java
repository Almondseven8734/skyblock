package com.skyblock.dungeon.classes;

import com.skyblock.dungeon.progression.PlayerProgressionState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player class choice and skill ranks. Plain data holder -
 * persistence is PlayerClassStorage, same split as every other
 * dungeon state/storage pair in this codebase.
 *
 * Class choice/switching rules (how many classes at once, level/guild
 * gates, the level-0 reset on switch) all live in
 * ClassProgressionService, not here - this class stays a plain data
 * holder so the GUI and the text command can share one rule
 * implementation instead of drifting out of sync.
 */
public final class PlayerClassState {

    private final UUID playerId;
    private PlayerClassType classType; // null until chosen
    private final Map<String, Integer> skillRanks = new HashMap<>();

    public PlayerClassState(UUID playerId) {
        this.playerId = playerId;
    }

    public boolean hasClass() {
        return classType != null;
    }

    /**
     * Sets (or switches) this player's class unconditionally and wipes
     * every previously learned skill rank - a fresh class always starts
     * with a clean skill tree. Gating (level/guild requirements, "can
     * this player do this right now") is ClassProgressionService's job,
     * not this class's - by the time this is called, that's already
     * been checked.
     */
    public void setClass(PlayerClassType type) {
        this.classType = type;
        this.skillRanks.clear();
    }

    public int getRank(String skillId) {
        return skillRanks.getOrDefault(skillId, 0);
    }

    /**
     * Spends 1 skill point from the given progression state to raise a
     * skill by one rank, if all the following hold: the player has
     * chosen this skill's class, the skill isn't already maxed, and
     * the progression state has an unspent point to spend. Returns the
     * new rank, or -1 if nothing happened (caller can tell a no-op
     * apart from "now rank 0", which investPoint never returns).
     */
    public int investPoint(ClassSkill skill, PlayerProgressionState progression) {
        if (classType != skill.getClassType()) {
            return -1;
        }
        int current = getRank(skill.getId());
        if (current >= skill.getMaxRank()) {
            return -1;
        }
        if (!progression.spendSkillPoints(skill.getPointCostPerRank())) {
            return -1;
        }
        int newRank = current + 1;
        skillRanks.put(skill.getId(), newRank);
        return newRank;
    }

    public UUID getPlayerId() { return playerId; }
    public PlayerClassType getClassType() { return classType; }
    public Map<String, Integer> getSkillRanks() { return skillRanks; }

    /** Restores exact saved values - used only by PlayerClassStorage when loading from disk. */
    void restore(PlayerClassType classType, Map<String, Integer> ranks) {
        this.classType = classType;
        this.skillRanks.clear();
        this.skillRanks.putAll(ranks);
    }
}
