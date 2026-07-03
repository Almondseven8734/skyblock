package com.skyblock.dungeon.classes;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/**
 * The three playable dungeon classes. No class casts spells outright -
 * per design, only weapons trigger "semi-magical" active skills, and
 * each class is gated to a specific weapon family: a Bowman's skills
 * only fire while holding a bow/crossbow, a Swordsman's only while
 * holding a sword, and so on. WeaponSkillListener enforces this by
 * checking isAllowedWeapon() before letting any skill trigger.
 *
 * SCOUT is built around axes rather than a dedicated weapon type -
 * there's no vanilla "dagger" material, and axes read as the closest
 * fast-skirmisher weapon vanilla actually has. Flagging this as a
 * deliberate substitution in case the real 1200-weapon item system
 * (later piece) wants a distinct scout weapon category instead.
 */
public enum PlayerClassType {

    SWORDSMAN("Swordsman", "§c", EnumSet.of(
        Material.WOODEN_SWORD, Material.STONE_SWORD, Material.GOLDEN_SWORD,
        Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)),

    SCOUT("Scout", "§a", EnumSet.of(
        Material.WOODEN_AXE, Material.STONE_AXE, Material.GOLDEN_AXE,
        Material.IRON_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE)),

    BOWMAN("Bowman", "§e", EnumSet.of(
        Material.BOW, Material.CROSSBOW));

    private final String displayName;
    private final String color;
    private final Set<Material> allowedWeapons;

    PlayerClassType(String displayName, String color, Set<Material> allowedWeapons) {
        this.displayName = displayName;
        this.color = color;
        this.allowedWeapons = allowedWeapons;
    }

    public boolean isAllowedWeapon(Material material) {
        return allowedWeapons.contains(material);
    }

    public String getDisplayName() { return displayName; }
    public String getColor() { return color; }
    public String getColoredName() { return color + displayName; }
    public Set<Material> getAllowedWeapons() { return allowedWeapons; }
}
