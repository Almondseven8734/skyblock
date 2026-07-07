package com.skyblock.dungeon.combat;

import org.bukkit.Material;

import java.util.List;

/**
 * One unique "boss trophy" item - the special, guaranteed drop tied to
 * a specific named boss identity (see BossArchetypeRegistry). Plain
 * data - DungeonBossDropRegistry owns the full catalog (one entry per
 * registered BossArchetype, keyed by the archetype's base EntityType)
 * and DungeonBossDropItemFactory turns a definition into the actual
 * ItemStack a player receives.
 *
 * Unlike DungeonDropDefinition (the ambient sellable-drop catalog),
 * these aren't meant to be a currency sink - they're a keepsake/proof-
 * of-kill tied to that specific boss identity, so the lore references
 * the boss by name rather than being a generic "mob drop" line.
 */
public final class DungeonBossDropDefinition {

    private final String id;
    private final String displayName;
    private final Material icon;
    private final List<String> loreLines;

    public DungeonBossDropDefinition(String id, String displayName, Material icon, List<String> loreLines) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.loreLines = List.copyOf(loreLines);
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public Material getIcon() { return icon; }
    public List<String> getLoreLines() { return loreLines; }
}
