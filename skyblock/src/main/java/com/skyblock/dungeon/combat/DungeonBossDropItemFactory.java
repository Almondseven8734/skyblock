package com.skyblock.dungeon.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Builds the actual ItemStack for a DungeonBossDropDefinition and reads
 * the trophy id back off one later. Mirrors DungeonDropItemFactory's
 * PDC-tagging pattern (its own namespaced key, "dungeon_boss_drop_id",
 * so it never collides with the ambient mob-drop id tag) but adds an
 * enchant-glint - boss trophies are meant to visually stand out from
 * both ordinary mob drops and equippable gear in a player's inventory.
 */
public final class DungeonBossDropItemFactory {

    private final NamespacedKey trophyIdKey;

    public DungeonBossDropItemFactory(JavaPlugin plugin) {
        this.trophyIdKey = new NamespacedKey(plugin, "dungeon_boss_drop_id");
    }

    public ItemStack build(DungeonBossDropDefinition definition) {
        ItemStack item = new ItemStack(definition.getIcon(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(definition.getDisplayName());
        meta.setLore(definition.getLoreLines());

        // Glint-only enchant: LUCK_OF_THE_SEA has no effect on a plain item
        // like this (it only matters on fishing rods), it's purely here for
        // the visual shimmer. HIDE_ENCHANTS keeps the tooltip from showing a
        // confusing "Luck of the Sea I" line under the flavor lore.
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        meta.getPersistentDataContainer().set(trophyIdKey, PersistentDataType.STRING, definition.getId());

        item.setItemMeta(meta);
        return item;
    }

    /** Reads the trophy id off an ItemStack, or null if it isn't one of ours. */
    public String readTrophyId(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(trophyIdKey, PersistentDataType.STRING);
    }
}
