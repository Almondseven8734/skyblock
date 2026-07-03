package com.skyblock.dungeon.loot;

import com.skyblock.dungeon.items.DungeonItemGenerator;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Builds actual ItemStack loot for dungeon chest rooms and drops,
 * using DungeonRarityRoller to pick a tier and DungeonItemGenerator to
 * build a real procedurally-generated weapon or armor piece at that
 * tier (50/50 split) - see DungeonItemGenerator/DungeonItemAffixes for
 * how the 1200-weapon/800+-armor catalog is generated on demand.
 */
public final class DungeonLootTable {

    private static final double WEAPON_VS_ARMOR_SPLIT = 0.5;

    private final DungeonRarityRoller roller;
    private final Random random;
    private final DungeonItemGenerator itemGenerator;

    public DungeonLootTable(DungeonRarityRoller roller, Random random, DungeonItemGenerator itemGenerator) {
        this.roller = roller;
        this.random = random;
        this.itemGenerator = itemGenerator;
    }

    /**
     * Rolls and builds a single loot item appropriate for the given floor.
     */
    public ItemStack rollLoot(int floorNumber) {
        DungeonRarity rarity = roller.roll(floorNumber);
        if (random.nextDouble() < WEAPON_VS_ARMOR_SPLIT) {
            return itemGenerator.generateRandomWeapon(rarity);
        }
        return itemGenerator.generateRandomArmor(rarity);
    }

    /**
     * Rolls a fixed number of loot items at once, e.g. for a chest room
     * that should contain multiple drops.
     */
    public List<ItemStack> rollLoot(int floorNumber, int count) {
        List<ItemStack> results = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            results.add(rollLoot(floorNumber));
        }
        return results;
    }
}
