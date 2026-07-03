package com.skyblock.dungeon.items;

import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * The 4 armor slots the generator can produce. Same trick as
 * DungeonWeaponArchetype: vanilla's armor materials
 * (leather/chainmail/iron/gold/diamond/netherite) are exactly 6 steps,
 * one per DungeonRarity tier, so rarity reads as material tier here
 * too.
 */
public enum DungeonArmorSlot {

    HELMET("Helmet", 2.0, buildTieredMaterials(
        Material.LEATHER_HELMET, Material.CHAINMAIL_HELMET, Material.IRON_HELMET,
        Material.GOLDEN_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET)),

    CHESTPLATE("Chestplate", 5.0, buildTieredMaterials(
        Material.LEATHER_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.IRON_CHESTPLATE,
        Material.GOLDEN_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE)),

    LEGGINGS("Leggings", 4.0, buildTieredMaterials(
        Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.IRON_LEGGINGS,
        Material.GOLDEN_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS)),

    BOOTS("Boots", 2.0, buildTieredMaterials(
        Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS,
        Material.GOLDEN_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS));

    private final String baseName;
    private final double baseArmor;
    private final Map<DungeonRarity, Material> materialByRarity;

    DungeonArmorSlot(String baseName, double baseArmor, Map<DungeonRarity, Material> materialByRarity) {
        this.baseName = baseName;
        this.baseArmor = baseArmor;
        this.materialByRarity = materialByRarity;
    }

    public Material materialFor(DungeonRarity rarity) {
        return materialByRarity.get(rarity);
    }

    private static Map<DungeonRarity, Material> buildTieredMaterials(Material... materialsInRarityOrder) {
        Map<DungeonRarity, Material> map = new EnumMap<>(DungeonRarity.class);
        DungeonRarity[] rarities = DungeonRarity.values();
        for (int i = 0; i < rarities.length && i < materialsInRarityOrder.length; i++) {
            map.put(rarities[i], materialsInRarityOrder[i]);
        }
        return map;
    }

    public String getBaseName() { return baseName; }
    public double getBaseArmor() { return baseArmor; }
}
