package com.skyblock.dungeon.drops;

import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Material;

/**
 * One sellable custom mob drop. Plain data - DungeonDropRegistry owns
 * the full 300-entry catalog, DungeonMobDropListener rolls which one
 * drops, and the guild merchant (GuildCommand's "sell" subcommand)
 * reads the id back off a dropped ItemStack's PDC tag to know what
 * to pay for it.
 */
public final class DungeonDropDefinition {

    private final String id;
    private final String displayName;
    private final DungeonRarity rarity;
    private final int sellPrice;
    private final Material icon;

    public DungeonDropDefinition(String id, String displayName, DungeonRarity rarity, int sellPrice, Material icon) {
        this.id = id;
        this.displayName = displayName;
        this.rarity = rarity;
        this.sellPrice = sellPrice;
        this.icon = icon;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public DungeonRarity getRarity() { return rarity; }
    public int getSellPrice() { return sellPrice; }
    public Material getIcon() { return icon; }
}
