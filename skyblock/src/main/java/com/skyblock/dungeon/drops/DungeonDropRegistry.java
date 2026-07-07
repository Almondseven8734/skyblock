package com.skyblock.dungeon.drops;

import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 300-entry sellable mob-drop catalog: 6 rarities x 10 drop nouns
 * x 5 quality adjectives = 300, generated once at startup rather than
 * hand-authored - same combinatorial approach as
 * DungeonItemAffixes/DungeonItemGenerator for weapons and armor.
 *
 * These are flavor/currency drops, not equippable gear - no PDC
 * archetype tagging beyond an id, no attribute modifiers, just a name
 * and a sell price the guild merchant pays out.
 */
public final class DungeonDropRegistry {

    private record DropNoun(String name, Material icon) {}
    private record Adjective(String name, double priceMultiplier) {}

    private static final List<DropNoun> NOUNS = List.of(
        new DropNoun("Fang", Material.BONE),
        new DropNoun("Claw", Material.PHANTOM_MEMBRANE),
        new DropNoun("Hide", Material.LEATHER),
        new DropNoun("Sinew", Material.STRING),
        new DropNoun("Marrow", Material.BONE_MEAL),
        new DropNoun("Essence", Material.GLOWSTONE_DUST),
        new DropNoun("Core", Material.MAGMA_CREAM),
        new DropNoun("Shard", Material.QUARTZ),
        new DropNoun("Eye", Material.SPIDER_EYE),
        new DropNoun("Venom Gland", Material.FERMENTED_SPIDER_EYE)
    );

    private static final List<Adjective> ADJECTIVES = List.of(
        new Adjective("Weathered", 0.90),
        new Adjective("Pristine", 1.00),
        new Adjective("Gleaming", 1.10),
        new Adjective("Charged", 1.20),
        new Adjective("Ancient", 1.35)
    );

    private static final Map<DungeonRarity, Integer> BASE_PRICE = new EnumMap<>(DungeonRarity.class);
    static {
        BASE_PRICE.put(DungeonRarity.COMMON, 5);
        BASE_PRICE.put(DungeonRarity.UNCOMMON, 15);
        BASE_PRICE.put(DungeonRarity.RARE, 40);
        BASE_PRICE.put(DungeonRarity.EPIC, 100);
        BASE_PRICE.put(DungeonRarity.LEGENDARY, 250);
        BASE_PRICE.put(DungeonRarity.MYTHICAL, 600);
    }

    private final Map<String, DungeonDropDefinition> byId = new LinkedHashMap<>();
    private final Map<DungeonRarity, List<DungeonDropDefinition>> byRarity = new EnumMap<>(DungeonRarity.class);
    private final DungeonMobDropTable mobDropTable;

    public DungeonDropRegistry() {
        this(new DungeonMobDropTable());
    }

    /**
     * @param mobDropTable the 100-entry mob-specific catalog (20 types x 5
     *                     drops) folded into this registry's id lookup so
     *                     resolve()/get() work for both generic
     *                     ("<rarity>_<noun>_<adjective>") and mob-specific
     *                     ("mobdrop_<type>_<slug>") ids through the same
     *                     guild-merchant sell path - callers don't need to
     *                     know which catalog an id came from.
     */
    public DungeonDropRegistry(DungeonMobDropTable mobDropTable) {
        this.mobDropTable = mobDropTable;
        for (DungeonDropDefinition mobDrop : mobDropTable.all()) {
            byId.put(mobDrop.getId(), mobDrop);
            byRarity.computeIfAbsent(mobDrop.getRarity(), r -> new ArrayList<>()).add(mobDrop);
        }
        for (DungeonRarity rarity : DungeonRarity.values()) {
            List<DungeonDropDefinition> forRarity = new ArrayList<>();
            int basePrice = BASE_PRICE.get(rarity);

            for (DropNoun noun : NOUNS) {
                for (Adjective adjective : ADJECTIVES) {
                    String id = (rarity.name() + "_" + noun.name() + "_" + adjective.name())
                        .toLowerCase().replace(' ', '_');
                    String displayName = rarity.getColoredName() + " §f" + adjective.name() + " " + noun.name();
                    int price = Math.max(1, (int) Math.round(basePrice * adjective.priceMultiplier()));

                    DungeonDropDefinition def = new DungeonDropDefinition(id, displayName, rarity, price, noun.icon());
                    byId.put(id, def);
                    forRarity.add(def);
                }
            }
            byRarity.put(rarity, forRarity);
        }
    }

    public DungeonDropDefinition get(String id) {
        return byId.get(id);
    }

    /** Generic-catalog-only rarity pool (unchanged behavior) - used by DungeonMobDropListener's fallback roll. */
    public List<DungeonDropDefinition> forRarity(DungeonRarity rarity) {
        return byRarity.get(rarity);
    }

    /** The mob-specific drop table folded into this registry, exposed so DungeonMobDropListener can roll from it directly. */
    public DungeonMobDropTable getMobDropTable() {
        return mobDropTable;
    }

    public int size() {
        return byId.size();
    }
}
