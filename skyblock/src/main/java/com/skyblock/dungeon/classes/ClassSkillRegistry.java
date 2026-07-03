package com.skyblock.dungeon.classes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-authored skill list, 2 per class as a working first pass of the
 * framework (swap for a data-driven config once the roster grows).
 * WeaponSkillListener owns the actual per-skill effect code and looks
 * these ids up by string, so adding a skill here without wiring a
 * matching case in WeaponSkillListener's switch will register the
 * skill as learnable but silently do nothing when triggered - keep
 * the two in sync.
 */
public final class ClassSkillRegistry {

    public static final String SWORDSMAN_WHIRLWIND = "whirlwind_strike";
    public static final String SWORDSMAN_SECOND_WIND = "second_wind";
    public static final String SCOUT_SPRINT_DASH = "sprint_dash";
    public static final String SCOUT_EVASIVE_ROLL = "evasive_roll";
    public static final String BOWMAN_VOLLEY = "volley";
    public static final String BOWMAN_POWER_SHOT = "power_shot";

    private final Map<String, ClassSkill> byId = new LinkedHashMap<>();

    public ClassSkillRegistry() {
        register(new ClassSkill(SWORDSMAN_WHIRLWIND, PlayerClassType.SWORDSMAN,
            "Whirlwind Strike", "Spin and hit every enemy within range, knocking them back.",
            5, 1, 100L, 10L));
        register(new ClassSkill(SWORDSMAN_SECOND_WIND, PlayerClassType.SWORDSMAN,
            "Second Wind", "Heal yourself and gain brief Resistance.",
            3, 2, 400L, 40L));

        register(new ClassSkill(SCOUT_SPRINT_DASH, PlayerClassType.SCOUT,
            "Sprint Dash", "Burst forward with a speed boost.",
            5, 1, 60L, 8L));
        register(new ClassSkill(SCOUT_EVASIVE_ROLL, PlayerClassType.SCOUT,
            "Evasive Roll", "Briefly gain Resistance and Speed to slip out of danger.",
            3, 2, 200L, 20L));

        register(new ClassSkill(BOWMAN_VOLLEY, PlayerClassType.BOWMAN,
            "Volley", "Instantly loose a spread of arrows without drawing.",
            5, 1, 100L, 10L));
        register(new ClassSkill(BOWMAN_POWER_SHOT, PlayerClassType.BOWMAN,
            "Power Shot", "Your next bow shot deals bonus damage in a small blast on impact.",
            3, 2, 160L, 20L));
    }

    private void register(ClassSkill skill) {
        byId.put(skill.getId(), skill);
    }

    public ClassSkill get(String id) {
        return byId.get(id);
    }

    public List<ClassSkill> forClass(PlayerClassType classType) {
        return byId.values().stream()
            .filter(s -> s.getClassType() == classType)
            .toList();
    }

    public java.util.Collection<ClassSkill> all() {
        return byId.values();
    }
}
