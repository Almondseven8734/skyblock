package com.skyblock.dungeon.classes;

/**
 * Static definition of one class skill. Skills are ranked 1..maxRank;
 * each rank costs skillPointCostPerRank skill points to learn (spent
 * via PlayerClassState.investPoint) and scales the skill's effect and
 * cooldown - the actual per-rank magnitude math lives in
 * WeaponSkillListener next to each skill's effect implementation, not
 * here, since it differs per skill (heal amount vs. dash distance vs.
 * arrow count aren't the same kind of number).
 */
public final class ClassSkill {

    private final String id;
    private final PlayerClassType classType;
    private final String displayName;
    private final String description;
    private final int maxRank;
    private final int pointCostPerRank;
    private final long baseCooldownTicks;
    private final long cooldownReductionPerRankTicks;

    public ClassSkill(String id, PlayerClassType classType, String displayName, String description,
                       int maxRank, int pointCostPerRank,
                       long baseCooldownTicks, long cooldownReductionPerRankTicks) {
        this.id = id;
        this.classType = classType;
        this.displayName = displayName;
        this.description = description;
        this.maxRank = maxRank;
        this.pointCostPerRank = pointCostPerRank;
        this.baseCooldownTicks = baseCooldownTicks;
        this.cooldownReductionPerRankTicks = cooldownReductionPerRankTicks;
    }

    /** Cooldown at a given learned rank (1..maxRank); never drops below 1 second. */
    public long cooldownTicksAtRank(int rank) {
        long reduced = baseCooldownTicks - (rank - 1L) * cooldownReductionPerRankTicks;
        return Math.max(20L, reduced);
    }

    public String getId() { return id; }
    public PlayerClassType getClassType() { return classType; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getMaxRank() { return maxRank; }
    public int getPointCostPerRank() { return pointCostPerRank; }
}
