package com.skyblock.dungeon.items;

import java.util.List;

/**
 * The affix pools that give the generator its combinatorial range.
 *
 * Catalog size math: 4 weapon archetypes x 6 rarities x 10 prefixes x
 * 5 suffixes = 1200 distinct named weapons. 4 armor slots x 6 rarities
 * x 10 prefixes x 4 suffixes = 960 distinct named armor pieces
 * (comfortably past the requested 800 - trimming to exactly 800 would
 * mean either dropping a real suffix or rarity tier for no gameplay
 * benefit, so this rounds up rather than forcing an exact number).
 *
 * Every combination is generated on demand by DungeonItemGenerator,
 * not pre-baked into memory - there's no 1200-entry item database to
 * maintain, just this pool plus the archetype/rarity tables.
 */
public final class DungeonItemAffixes {

    public static final List<ItemAffix> WEAPON_PREFIXES = List.of(
        new ItemAffix("Cruel", 0.06),
        new ItemAffix("Wicked", 0.09),
        new ItemAffix("Runic", 0.12),
        new ItemAffix("Ember", 0.08),
        new ItemAffix("Storm", 0.14),
        new ItemAffix("Frozen", 0.10),
        new ItemAffix("Venomous", 0.11),
        new ItemAffix("Sanctified", 0.16),
        new ItemAffix("Feral", 0.07),
        new ItemAffix("Ancient", 0.18)
    );

    /** Suffix value is flat crit chance, 0.0-1.0. */
    public static final List<ItemAffix> WEAPON_SUFFIXES = List.of(
        new ItemAffix("of Precision", 0.06),
        new ItemAffix("of the Bear", 0.04),
        new ItemAffix("of Ruin", 0.09),
        new ItemAffix("of the Void", 0.12),
        new ItemAffix("of Vengeance", 0.07)
    );

    public static final List<ItemAffix> ARMOR_PREFIXES = List.of(
        new ItemAffix("Sturdy", 0.06),
        new ItemAffix("Reinforced", 0.09),
        new ItemAffix("Warded", 0.12),
        new ItemAffix("Blessed", 0.08),
        new ItemAffix("Runed", 0.14),
        new ItemAffix("Fortified", 0.10),
        new ItemAffix("Phantom", 0.11),
        new ItemAffix("Titan's", 0.16),
        new ItemAffix("Ember-forged", 0.07),
        new ItemAffix("Guardian's", 0.18)
    );

    /** Suffix value is flat bonus max health, in half-heart attribute points. */
    public static final List<ItemAffix> ARMOR_SUFFIXES = List.of(
        new ItemAffix("of the Bulwark", 2.0),
        new ItemAffix("of Vitality", 4.0),
        new ItemAffix("of the Mountain", 6.0),
        new ItemAffix("of the Ancients", 8.0)
    );

    private DungeonItemAffixes() {
    }
}
