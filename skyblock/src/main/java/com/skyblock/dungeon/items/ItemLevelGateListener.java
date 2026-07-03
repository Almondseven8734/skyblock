package com.skyblock.dungeon.items;

import com.skyblock.dungeon.progression.PlayerProgressionState;
import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the "better weapons/armor require a higher character
 * level" rule generated items are tagged with (see
 * DungeonItemGenerator.requiredLevelFor). Weapons: an under-leveled
 * weapon simply can't land a hit - the swing is cancelled outright
 * rather than just losing its bonus damage, so the gate is a hard
 * wall, not a soft nerf. Armor: equipping an under-leveled piece
 * immediately kicks it back to the inventory.
 */
public final class ItemLevelGateListener implements Listener {

    private static final long WARNING_THROTTLE_MS = 2000L;

    private final DungeonItemGenerator generator;
    private final PlayerProgressionStorage progressionStorage;
    private final Map<UUID, Long> lastWarnedAt = new ConcurrentHashMap<>();

    public ItemLevelGateListener(DungeonItemGenerator generator, PlayerProgressionStorage progressionStorage) {
        this.generator = generator;
        this.progressionStorage = progressionStorage;
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        Integer requiredLevel = readRequiredLevel(held);
        if (requiredLevel == null) return; // not a generated item, nothing to gate

        int characterLevel = progressionStorage.get(player.getUniqueId()).getCharacterLevel();
        if (characterLevel >= requiredLevel) return;

        event.setCancelled(true);
        warnThrottled(player, "§cYou need character level §6" + requiredLevel
            + " §cto wield this weapon (you're level §6" + characterLevel + "§c).");
    }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        ItemStack newItem = event.getNewItem();
        Integer requiredLevel = readRequiredLevel(newItem);
        if (requiredLevel == null) return; // not a generated item, nothing to gate

        Player player = event.getPlayer();
        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());
        if (progression.getCharacterLevel() >= requiredLevel) return;

        // Revert: restore whatever was in the slot before (matches
        // vanilla's own "can't equip this" feel instead of silently
        // no-opping) and hand the rejected piece back - to inventory
        // if there's room, dropped at their feet otherwise, never
        // just discarded.
        switch (event.getSlotType()) {
            case HEAD -> player.getInventory().setHelmet(event.getOldItem());
            case CHEST -> player.getInventory().setChestplate(event.getOldItem());
            case LEGS -> player.getInventory().setLeggings(event.getOldItem());
            case FEET -> player.getInventory().setBoots(event.getOldItem());
        }
        if (!player.getInventory().addItem(newItem).isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), newItem);
        }

        warnThrottled(player, "§cYou need character level §6" + requiredLevel
            + " §cto wear this armor (you're level §6" + progression.getCharacterLevel() + "§c).");
    }

    private Integer readRequiredLevel(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(generator.getRequiredLevelKey(), PersistentDataType.INTEGER);
    }

    private void warnThrottled(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastWarnedAt.get(player.getUniqueId());
        if (last != null && now - last < WARNING_THROTTLE_MS) return;
        lastWarnedAt.put(player.getUniqueId(), now);
        player.sendMessage(message);
    }
}
