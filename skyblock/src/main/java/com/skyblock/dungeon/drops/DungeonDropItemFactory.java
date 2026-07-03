package com.skyblock.dungeon.drops;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Builds the actual ItemStack for a DungeonDropDefinition and reads
 * the drop id back off one later - the guild merchant (GuildCommand's
 * sell flow) uses readDropId() to recognize and price these out of a
 * player's inventory without caring about display name/lore text.
 */
public final class DungeonDropItemFactory {

    private final NamespacedKey dropIdKey;
    private final DungeonDropRegistry registry;

    public DungeonDropItemFactory(JavaPlugin plugin, DungeonDropRegistry registry) {
        this.dropIdKey = new NamespacedKey(plugin, "dungeon_drop_id");
        this.registry = registry;
    }

    public ItemStack build(DungeonDropDefinition definition) {
        return build(definition, 1);
    }

    public ItemStack build(DungeonDropDefinition definition, int amount) {
        ItemStack item = new ItemStack(definition.getIcon(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(definition.getDisplayName());
        meta.setLore(List.of(
            definition.getRarity().getColoredName() + " §7mob drop",
            "§7Sells for §6" + definition.getSellPrice() + " coins §7at the guild merchant."
        ));
        meta.getPersistentDataContainer().set(dropIdKey, PersistentDataType.STRING, definition.getId());

        item.setItemMeta(meta);
        return item;
    }

    /** Reads the drop id off an ItemStack, or null if it isn't one of ours. */
    public String readDropId(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(dropIdKey, PersistentDataType.STRING);
    }

    /** Resolves a full DungeonDropDefinition off an ItemStack, or null if it isn't a recognized drop. */
    public DungeonDropDefinition resolve(ItemStack item) {
        String id = readDropId(item);
        return id != null ? registry.get(id) : null;
    }
}
