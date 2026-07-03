package com.skyblock.dungeon.items;

/**
 * One affix: a name fragment plus the stat bonus it contributes.
 * Prefixes modify the item's primary stat (damage for weapons, armor
 * for armor pieces) as a percentage bonus; suffixes add a secondary
 * stat (crit chance for weapons, bonus max health for armor) as a
 * flat amount. Kept as one shared shape rather than 4 near-identical
 * classes since the generator already knows from context which stat
 * an affix's `value` applies to.
 */
public final class ItemAffix {

    private final String name;
    private final double value;

    public ItemAffix(String name, double value) {
        this.name = name;
        this.value = value;
    }

    public String getName() { return name; }
    public double getValue() { return value; }
}
