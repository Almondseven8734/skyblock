package com.skyblock.dungeon.items;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Random;

/**
 * Rolls a generated weapon's crit chance (from its suffix affix, see
 * DungeonItemAffixes.WEAPON_SUFFIXES) on every hit and applies the
 * crit damage multiplier when it lands. Runs at HIGH priority so it
 * reads event.isCancelled() after ItemLevelGateListener has already
 * had a chance to cancel an under-leveled swing - no point rolling a
 * crit for a hit that was never going to land.
 */
public final class ItemCombatStatsListener implements Listener {

    private static final double CRIT_DAMAGE_MULTIPLIER = 1.5;

    private final DungeonItemGenerator generator;
    private final Random random;

    public ItemCombatStatsListener(DungeonItemGenerator generator, Random random) {
        this.generator = generator;
        this.random = random;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        ItemMeta meta = held.getItemMeta();
        if (meta == null) return;

        Double critChance = meta.getPersistentDataContainer().get(generator.getCritChanceKey(), PersistentDataType.DOUBLE);
        if (critChance == null || critChance <= 0.0) return;

        if (random.nextDouble() < critChance) {
            event.setDamage(event.getDamage() * CRIT_DAMAGE_MULTIPLIER);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.0f);
        }
    }
}
