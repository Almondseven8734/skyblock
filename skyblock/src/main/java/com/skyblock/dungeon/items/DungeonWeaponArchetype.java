package com.skyblock.dungeon.items;

import com.skyblock.dungeon.classes.PlayerClassType;
import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * The 4 weapon families the generator can produce, matched 1:1 to
 * PlayerClassType's weapon gating (see PlayerClassType.isAllowedWeapon)
 * so every generated weapon slots straight into the class skill
 * system from Piece 4 - a generated SWORD is usable (and
 * skill-triggering) for a Swordsman with zero extra wiring.
 *
 * SWORD and AXE ride the vanilla 6-material tool progression
 * (wood/stone/iron/gold/diamond/netherite), which happens to be
 * exactly 6 steps - one per DungeonRarity tier, so rarity visually
 * reads as material tier the same way it already does for
 * MobLevelTier's mob gear. BOW and CROSSBOW have no vanilla material
 * tiers, so their rarity reads through name/color/lore/glint instead.
 */
public enum DungeonWeaponArchetype {

    SWORD("Sword", PlayerClassType.SWORDSMAN, 4.0, buildTieredMaterials(
        Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
        Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD)),

    AXE("Axe", PlayerClassType.SCOUT, 5.0, buildTieredMaterials(
        Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE,
        Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE)),

    BOW("Bow", PlayerClassType.BOWMAN, 3.0, fixedMaterial(Material.BOW)),

    CROSSBOW("Crossbow", PlayerClassType.BOWMAN, 3.5, fixedMaterial(Material.CROSSBOW));

    private final String baseName;
    private final PlayerClassType classType;
    private final double baseDamage;
    private final Map<DungeonRarity, Material> materialByRarity;

    DungeonWeaponArchetype(String baseName, PlayerClassType classType, double baseDamage,
                            Map<DungeonRarity, Material> materialByRarity) {
        this.baseName = baseName;
        this.classType = classType;
        this.baseDamage = baseDamage;
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

    private static Map<DungeonRarity, Material> fixedMaterial(Material material) {
        Map<DungeonRarity, Material> map = new EnumMap<>(DungeonRarity.class);
        for (DungeonRarity rarity : DungeonRarity.values()) {
            map.put(rarity, material);
        }
        return map;
    }

    public String getBaseName() { return baseName; }
    public PlayerClassType getClassType() { return classType; }
    public double getBaseDamage() { return baseDamage; }
}
