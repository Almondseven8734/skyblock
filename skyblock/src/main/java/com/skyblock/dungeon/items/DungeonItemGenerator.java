package com.skyblock.dungeon.items;

import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates actual weapon/armor ItemStacks on demand from archetype x
 * rarity x prefix x suffix, rather than drawing from a pre-baked
 * 1200/800-entry database - see DungeonItemAffixes for the
 * combinatorics. Every call with the same (archetype, rarity, prefix,
 * suffix) tuple produces an equivalent item, so the "database" is
 * really just this code plus the affix/rarity tables.
 *
 * Stat design note: the damage/armor bonus this generator applies is
 * an ADDITIVE AttributeModifier on top of the reskinned vanilla
 * material's own baked-in stats (a generated DIAMOND_SWORD still
 * carries vanilla diamond sword's base attack damage underneath).
 * True from-scratch stat control would mean stripping the vanilla
 * base attribute entirely, which isn't straightforward through the
 * public ItemMeta API - so treat rarity/affix bonuses as exactly that,
 * bonuses layered onto the material's vanilla baseline, not the
 * item's total combat stat.
 */
public final class DungeonItemGenerator {

    private static final Map<DungeonRarity, Double> RARITY_MULTIPLIER = new EnumMap<>(DungeonRarity.class);
    private static final Map<DungeonRarity, Integer> REQUIRED_LEVEL = new EnumMap<>(DungeonRarity.class);

    static {
        RARITY_MULTIPLIER.put(DungeonRarity.COMMON, 1.0);
        RARITY_MULTIPLIER.put(DungeonRarity.UNCOMMON, 1.3);
        RARITY_MULTIPLIER.put(DungeonRarity.RARE, 1.7);
        RARITY_MULTIPLIER.put(DungeonRarity.EPIC, 2.3);
        RARITY_MULTIPLIER.put(DungeonRarity.LEGENDARY, 3.0);
        RARITY_MULTIPLIER.put(DungeonRarity.MYTHICAL, 4.0);

        REQUIRED_LEVEL.put(DungeonRarity.COMMON, 1);
        REQUIRED_LEVEL.put(DungeonRarity.UNCOMMON, 10);
        REQUIRED_LEVEL.put(DungeonRarity.RARE, 20);
        REQUIRED_LEVEL.put(DungeonRarity.EPIC, 30);
        REQUIRED_LEVEL.put(DungeonRarity.LEGENDARY, 45);
        REQUIRED_LEVEL.put(DungeonRarity.MYTHICAL, 55);
    }

    /** Rarities at or above this get the enchant-glint sheen - reserved for the visually "special" tiers. */
    private static final DungeonRarity GLINT_FROM_RARITY = DungeonRarity.RARE;

    private final NamespacedKey rarityKey;
    private final NamespacedKey requiredLevelKey;
    private final NamespacedKey critChanceKey;
    private final NamespacedKey archetypeKey;
    private final NamespacedKey armorSlotKey;
    /**
     * Tracks kills-since-last-durability-roll on a generated weapon
     * ItemStack itself (not the player) - see
     * DungeonWeaponDurabilityListener, which increments this on every
     * dungeon mob kill and rolls a rarity-scaled snap chance every 50.
     * Lives on the item so it follows the weapon through trades/AH/
     * storage rather than being tied to whichever player is currently
     * holding it.
     */
    private final NamespacedKey weaponKillsKey;
    private final Random random;

    public NamespacedKey getWeaponKillsKey() { return weaponKillsKey; }
    public NamespacedKey getArchetypeKey() { return archetypeKey; }

    public DungeonItemGenerator(JavaPlugin plugin, Random random) {
        this.random = random;
        this.rarityKey = new NamespacedKey(plugin, "dungeon_item_rarity");
        this.requiredLevelKey = new NamespacedKey(plugin, "dungeon_item_required_level");
        this.critChanceKey = new NamespacedKey(plugin, "dungeon_item_crit_chance");
        this.archetypeKey = new NamespacedKey(plugin, "dungeon_item_archetype");
        this.armorSlotKey = new NamespacedKey(plugin, "dungeon_item_armor_slot");
        this.weaponKillsKey = new NamespacedKey(plugin, "dungeon_weapon_kills");
    }

    public NamespacedKey getRarityKey() { return rarityKey; }
    public NamespacedKey getRequiredLevelKey() { return requiredLevelKey; }
    public NamespacedKey getCritChanceKey() { return critChanceKey; }

    public static int requiredLevelFor(DungeonRarity rarity) {
        return REQUIRED_LEVEL.get(rarity);
    }

    /** Generates a random weapon of the given rarity, picking a random archetype/prefix/suffix. */
    public ItemStack generateRandomWeapon(DungeonRarity rarity) {
        DungeonWeaponArchetype archetype = DungeonWeaponArchetype.values()[random.nextInt(DungeonWeaponArchetype.values().length)];
        return generateWeapon(archetype, rarity);
    }

    public ItemStack generateWeapon(DungeonWeaponArchetype archetype, DungeonRarity rarity) {
        ItemAffix prefix = DungeonItemAffixes.WEAPON_PREFIXES.get(random.nextInt(DungeonItemAffixes.WEAPON_PREFIXES.size()));
        ItemAffix suffix = DungeonItemAffixes.WEAPON_SUFFIXES.get(random.nextInt(DungeonItemAffixes.WEAPON_SUFFIXES.size()));

        double rarityMultiplier = RARITY_MULTIPLIER.get(rarity);
        double bonusDamage = archetype.getBaseDamage() * rarityMultiplier * prefix.getValue();
        double critChance = suffix.getValue();
        int requiredLevel = REQUIRED_LEVEL.get(rarity);

        ItemStack item = new ItemStack(archetype.materialFor(rarity));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(rarity.getColoredName() + " §f" + prefix.getName() + " " + archetype.getBaseName()
            + " " + suffix.getName());

        meta.setLore(List.of(
            rarity.getColoredName(),
            "§7" + archetype.getClassType().getColoredName() + " §7weapon",
            "§c+" + round1(bonusDamage) + " Damage §7(bonus)",
            "§b" + Math.round(critChance * 100) + "% §7Crit Chance",
            "§8Requires character level " + requiredLevel
        ));

        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
            new NamespacedKey(rarityKey.getNamespace(), "weapon_bonus_damage"),
            bonusDamage, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (rarity.ordinal() >= GLINT_FROM_RARITY.ordinal()) {
            meta.setEnchantmentGlintOverride(true);
        }

        // Dungeon weapons never break from ordinary durability damage -
        // see DungeonWeaponDurabilityListener for the separate
        // kill-count-based snap mechanic that replaces vanilla
        // durability entirely for these items.
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);

        meta.getPersistentDataContainer().set(rarityKey, PersistentDataType.STRING, rarity.name());
        meta.getPersistentDataContainer().set(requiredLevelKey, PersistentDataType.INTEGER, requiredLevel);
        meta.getPersistentDataContainer().set(critChanceKey, PersistentDataType.DOUBLE, critChance);
        meta.getPersistentDataContainer().set(archetypeKey, PersistentDataType.STRING, archetype.name());
        meta.getPersistentDataContainer().set(weaponKillsKey, PersistentDataType.INTEGER, 0);

        item.setItemMeta(meta);
        return item;
    }

    /** Generates a random armor piece of the given rarity, picking a random slot/prefix/suffix. */
    public ItemStack generateRandomArmor(DungeonRarity rarity) {
        DungeonArmorSlot slot = DungeonArmorSlot.values()[random.nextInt(DungeonArmorSlot.values().length)];
        return generateArmor(slot, rarity);
    }

    public ItemStack generateArmor(DungeonArmorSlot slot, DungeonRarity rarity) {
        ItemAffix prefix = DungeonItemAffixes.ARMOR_PREFIXES.get(random.nextInt(DungeonItemAffixes.ARMOR_PREFIXES.size()));
        ItemAffix suffix = DungeonItemAffixes.ARMOR_SUFFIXES.get(random.nextInt(DungeonItemAffixes.ARMOR_SUFFIXES.size()));

        double rarityMultiplier = RARITY_MULTIPLIER.get(rarity);
        double bonusArmor = slot.getBaseArmor() * rarityMultiplier * prefix.getValue();
        double bonusHealth = suffix.getValue();
        int requiredLevel = REQUIRED_LEVEL.get(rarity);

        ItemStack item = new ItemStack(slot.materialFor(rarity));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(rarity.getColoredName() + " §f" + prefix.getName() + " " + slot.getBaseName()
            + " " + suffix.getName());

        meta.setLore(List.of(
            rarity.getColoredName(),
            "§a+" + round1(bonusArmor) + " Armor §7(bonus)",
            "§d+" + round1(bonusHealth) + " Max Health",
            "§8Requires character level " + requiredLevel
        ));

        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
            new NamespacedKey(rarityKey.getNamespace(), "armor_bonus_armor"),
            bonusArmor, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroupFor(slot)));
        meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(
            new NamespacedKey(rarityKey.getNamespace(), "armor_bonus_health"),
            bonusHealth, AttributeModifier.Operation.ADD_NUMBER, equipmentSlotGroupFor(slot)));

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (rarity.ordinal() >= GLINT_FROM_RARITY.ordinal()) {
            meta.setEnchantmentGlintOverride(true);
        }

        meta.getPersistentDataContainer().set(rarityKey, PersistentDataType.STRING, rarity.name());
        meta.getPersistentDataContainer().set(requiredLevelKey, PersistentDataType.INTEGER, requiredLevel);
        meta.getPersistentDataContainer().set(armorSlotKey, PersistentDataType.STRING, slot.name());

        item.setItemMeta(meta);
        return item;
    }

    private EquipmentSlotGroup equipmentSlotGroupFor(DungeonArmorSlot slot) {
        return switch (slot) {
            case HELMET -> EquipmentSlotGroup.HEAD;
            case CHESTPLATE -> EquipmentSlotGroup.CHEST;
            case LEGGINGS -> EquipmentSlotGroup.LEGS;
            case BOOTS -> EquipmentSlotGroup.FEET;
        };
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
