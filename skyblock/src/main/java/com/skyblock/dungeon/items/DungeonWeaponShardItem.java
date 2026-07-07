package com.skyblock.dungeon.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * "Weapon Shard" - the salvage currency awarded when a dungeon
 * weapon snaps (see DungeonWeaponDurabilityListener). Reskinned off
 * PRISMARINE_SHARD via a display name + lore, same pattern as
 * MineSystem's Void Shard (a reskinned Echo Shard identified by its
 * own PDC tag rather than a distinct vanilla material) - stamped with
 * a PDC tag so any future shop/crafting system can identify a genuine
 * Weapon Shard stack distinctly from a plain vanilla Prismarine Shard.
 */
public final class DungeonWeaponShardItem {

    private static final Material BASE_MATERIAL = Material.PRISMARINE_SHARD;
    private static final String DISPLAY_NAME = "§bWeapon Shard";
    private static final List<String> LORE = List.of(
        "§7Salvaged from a broken dungeon weapon.",
        "§7Used to reforge or upgrade dungeon gear."
    );

    private final NamespacedKey weaponShardKey;

    public DungeonWeaponShardItem(JavaPlugin plugin) {
        this.weaponShardKey = new NamespacedKey(plugin, "dungeon_weapon_shard");
    }

    public NamespacedKey getWeaponShardKey() {
        return weaponShardKey;
    }

    /** True if the given stack is a genuine Weapon Shard (PDC-tagged), not just a vanilla Prismarine Shard. */
    public boolean isWeaponShard(ItemStack item) {
        if (item == null || item.getType() != BASE_MATERIAL) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(weaponShardKey, PersistentDataType.BYTE);
    }

    /** Builds a stack of `amount` Weapon Shards. Returns null (nothing to give) if amount <= 0. */
    public ItemStack create(int amount) {
        if (amount <= 0) {
            return null;
        }
        ItemStack item = new ItemStack(BASE_MATERIAL, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(DISPLAY_NAME);
        meta.setLore(LORE);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(weaponShardKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }
}
