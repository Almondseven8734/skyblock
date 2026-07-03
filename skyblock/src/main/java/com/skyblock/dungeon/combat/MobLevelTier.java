package com.skyblock.dungeon.combat;

import org.bukkit.Material;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * The 5 level bands a rolled mob level (1-100) falls into. Each band
 * defines the potion-effect buffs/debuffs applied on top of the
 * continuous health/damage/speed curve in MobLevelApplicator, plus
 * the vanilla-material gear tier equipped on spawn (a placeholder
 * scheme - swapped for the real 1200-weapon/800-armor item system
 * once that piece exists).
 *
 * Levels are intentionally weighted low by MobLevelRoller, so NOVICE
 * mobs are the most common thing a player fights on any given floor,
 * and ASCENDED is meant to be a rare, visibly distinct threat.
 */
public enum MobLevelTier {

    NOVICE(1, 20, "§7Novice", Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
        Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS, Material.WOODEN_SWORD,
        List.of(new PotionEffect(PotionEffectType.WEAKNESS, Integer.MAX_VALUE, 0, false, false))),

    ADEPT(21, 40, "§aAdept", Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
        Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS, Material.STONE_SWORD,
        List.of()),

    VETERAN(41, 60, "§9Veteran", Material.IRON_HELMET, Material.IRON_CHESTPLATE,
        Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.IRON_SWORD,
        List.of(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0, false, false))),

    ELITE(61, 80, "§5Elite", Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
        Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.DIAMOND_SWORD,
        List.of(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, false, false),
                new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, false, false))),

    ASCENDED(81, 100, "§6§lAscended", Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
        Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS, Material.NETHERITE_SWORD,
        List.of(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 2, false, false),
                new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 1, false, false),
                new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, false, false)));

    private final int minLevel;
    private final int maxLevel;
    private final String displayName;
    private final Material helmet;
    private final Material chestplate;
    private final Material leggings;
    private final Material boots;
    private final Material weapon;
    private final List<PotionEffect> effects;

    MobLevelTier(int minLevel, int maxLevel, String displayName,
                 Material helmet, Material chestplate, Material leggings, Material boots,
                 Material weapon, List<PotionEffect> effects) {
        this.minLevel = minLevel;
        this.maxLevel = maxLevel;
        this.displayName = displayName;
        this.helmet = helmet;
        this.chestplate = chestplate;
        this.leggings = leggings;
        this.boots = boots;
        this.weapon = weapon;
        this.effects = List.copyOf(effects);
    }

    public static MobLevelTier forLevel(int level) {
        for (MobLevelTier tier : values()) {
            if (level >= tier.minLevel && level <= tier.maxLevel) {
                return tier;
            }
        }
        // Levels are clamped to 1-100 before this is ever called, but
        // fall back to the top tier rather than throw on a stray >100.
        return ASCENDED;
    }

    public int getMinLevel() { return minLevel; }
    public int getMaxLevel() { return maxLevel; }
    public String getDisplayName() { return displayName; }
    public Material getHelmet() { return helmet; }
    public Material getChestplate() { return chestplate; }
    public Material getLeggings() { return leggings; }
    public Material getBoots() { return boots; }
    public Material getWeapon() { return weapon; }
    public List<PotionEffect> getEffects() { return effects; }
}
