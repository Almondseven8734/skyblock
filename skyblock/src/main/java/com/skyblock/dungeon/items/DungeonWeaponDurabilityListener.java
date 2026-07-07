package com.skyblock.dungeon.items;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Dungeon weapons never take vanilla durability damage (see
 * DungeonItemGenerator.generateWeapon's setUnbreakable(true)) - instead,
 * every 50 dungeon-mob kills landed with the same weapon rolls a
 * one-shot "snap" chance, scaled down as rarity goes up (COMMON is the
 * most fragile per-roll, MYTHICAL the least). A snapped weapon is
 * destroyed outright and replaced with a handful of Weapon Shards,
 * scaled by the broken weapon's own rarity.
 *
 * Kill count lives in the weapon ItemStack's own PDC (see
 * DungeonItemGenerator.getWeaponKillsKey()), not on the player, so it
 * follows the specific weapon instance through inventory moves/AH/
 * storage rather than resetting or being shared across every weapon a
 * player happens to be holding over time.
 *
 * Runs at MONITOR priority on EntityDeathEvent, after
 * DungeonMobDropListener has already handled the death's loot - this
 * listener only cares about the killer's held weapon, not the victim's
 * drops, so ordering relative to loot doesn't matter, but MONITOR
 * keeps it firmly last regardless.
 */
public final class DungeonWeaponDurabilityListener implements Listener {

    /** Kill count interval at which a snap roll happens. */
    private static final int KILLS_PER_ROLL = 50;

    /** Snap chance per roll, by rarity - COMMON is the highest risk, MYTHICAL the lowest, per design. */
    private static final Map<DungeonRarity, Double> SNAP_CHANCE = new EnumMap<>(DungeonRarity.class);
    /** Weapon Shard reward range [min, max] (inclusive) on a snap, by rarity - scales 0..20 across tiers. */
    private static final Map<DungeonRarity, int[]> SHARD_RANGE = new EnumMap<>(DungeonRarity.class);

    static {
        // Linear 50% -> 25% across the 6 tiers (5 percentage points per tier).
        SNAP_CHANCE.put(DungeonRarity.COMMON, 0.50);
        SNAP_CHANCE.put(DungeonRarity.UNCOMMON, 0.45);
        SNAP_CHANCE.put(DungeonRarity.RARE, 0.40);
        SNAP_CHANCE.put(DungeonRarity.EPIC, 0.35);
        SNAP_CHANCE.put(DungeonRarity.LEGENDARY, 0.30);
        SNAP_CHANCE.put(DungeonRarity.MYTHICAL, 0.25);

        SHARD_RANGE.put(DungeonRarity.COMMON, new int[]{0, 3});
        SHARD_RANGE.put(DungeonRarity.UNCOMMON, new int[]{2, 6});
        SHARD_RANGE.put(DungeonRarity.RARE, new int[]{5, 9});
        SHARD_RANGE.put(DungeonRarity.EPIC, new int[]{8, 13});
        SHARD_RANGE.put(DungeonRarity.LEGENDARY, new int[]{12, 17});
        SHARD_RANGE.put(DungeonRarity.MYTHICAL, new int[]{16, 20});
    }

    private final MobLevelApplicator levelApplicator;
    private final DungeonItemGenerator itemGenerator;
    private final DungeonWeaponShardItem shardItem;
    private final Random random;

    public DungeonWeaponDurabilityListener(MobLevelApplicator levelApplicator, DungeonItemGenerator itemGenerator,
                                            DungeonWeaponShardItem shardItem, Random random) {
        this.levelApplicator = levelApplicator;
        this.itemGenerator = itemGenerator;
        this.shardItem = shardItem;
        this.random = random;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDungeonMobDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (levelApplicator.readLevel(victim) < 0) {
            return; // not a dungeon mob - never touch weapon kill counts for non-dungeon kills
        }
        if (!(victim.getKiller() instanceof Player player)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        DungeonRarity rarity = readRarity(held);
        if (rarity == null || !isWeapon(held)) {
            return; // not a generated dungeon weapon - nothing to track
        }

        int kills = incrementKillCount(held);
        if (kills % KILLS_PER_ROLL != 0) {
            return; // not a roll checkpoint yet
        }

        double snapChance = SNAP_CHANCE.get(rarity);
        if (random.nextDouble() >= snapChance) {
            return; // survived the roll
        }

        breakWeapon(player, inventory, held, rarity);
    }

    /** True if this generated item is a weapon (has an archetype tag) rather than armor (which never gets one). */
    private boolean isWeapon(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(itemGenerator.getArchetypeKey(), PersistentDataType.STRING);
    }

    private DungeonRarity readRarity(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(itemGenerator.getRarityKey(), PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return DungeonRarity.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Increments and persists the weapon's kill counter, returning the new count. */
    private int incrementKillCount(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;

        Integer current = meta.getPersistentDataContainer().get(itemGenerator.getWeaponKillsKey(), PersistentDataType.INTEGER);
        int updated = (current == null ? 0 : current) + 1;
        meta.getPersistentDataContainer().set(itemGenerator.getWeaponKillsKey(), PersistentDataType.INTEGER, updated);
        item.setItemMeta(meta);
        return updated;
    }

    /**
     * Destroys the weapon in the player's main hand and hands them
     * Weapon Shards scaled by its rarity. Only removes exactly the one
     * weapon instance that snapped - if the main hand somehow holds a
     * stack of more than 1 (generated weapons aren't normally
     * stackable, but defends against it anyway), only decrements by 1
     * rather than clearing the whole stack.
     */
    private void breakWeapon(Player player, PlayerInventory inventory, ItemStack held, DungeonRarity rarity) {
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
        } else {
            inventory.setItemInMainHand(null);
        }

        int[] range = SHARD_RANGE.get(rarity);
        int min = range[0];
        int max = range[1];
        int shardCount = min + (max > min ? random.nextInt(max - min + 1) : 0);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
        player.sendMessage("§cYour " + rarity.getColoredName() + " §cweapon has snapped from wear!"
                + (shardCount > 0 ? " §7(+" + shardCount + " Weapon Shards)" : ""));

        ItemStack shards = shardItem.create(shardCount);
        if (shards == null) {
            return; // 0 shards rolled - nothing further to give
        }
        Map<Integer, ItemStack> overflow = inventory.addItem(shards);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
