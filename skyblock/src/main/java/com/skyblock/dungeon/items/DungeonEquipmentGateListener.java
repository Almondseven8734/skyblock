package com.skyblock.dungeon.items;

import com.skyblock.dungeon.floor.DungeonPlayerState;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps external (non-dungeon-generated) weapons and armor out of the
 * dungeon entirely:
 *
 *   1. Melee damage: a player inside the dungeon swinging anything
 *      other than a genuine DungeonItemGenerator weapon (or their bare
 *      hand) has the hit cancelled outright - no bonus damage, no
 *      damage at all, full stop. Bare-handed punching is intentionally
 *      still allowed (no free extra restriction beyond "no outside
 *      gear"), it just does vanilla fist damage.
 *
 *   2. Armor: equipping any armor piece that isn't a genuine
 *      DungeonItemGenerator armor piece while inside the dungeon is
 *      reverted immediately, same revert-and-return pattern as
 *      ItemLevelGateListener's under-leveled-armor handling.
 *
 *   3. Entry sweep: the moment a player's world changes to the dungeon
 *      world while their persisted state says they're inside the
 *      dungeon (covers both the initial /dungeon teleport and
 *      resuming after a relog), any already-worn external armor is
 *      stripped back to their inventory (or dropped at their feet if
 *      the inventory has no room) - so gearing up beforehand and
 *      walking in with vanilla/other-system armor already equipped
 *      doesn't bypass rule 2, which only fires on a NEW equip action.
 *
 * A "genuine dungeon item" is identified the same way
 * ItemLevelGateListener does: the presence of DungeonItemGenerator's
 * rarityKey PDC tag, which only this generator's own weapon/armor
 * output ever carries.
 */
public final class DungeonEquipmentGateListener implements Listener {

    private final DungeonItemGenerator itemGenerator;
    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final Supplier<World> dungeonWorldSupplier;

    public DungeonEquipmentGateListener(DungeonItemGenerator itemGenerator,
                                         Function<UUID, DungeonPlayerState> stateLookup,
                                         Supplier<World> dungeonWorldSupplier) {
        this.itemGenerator = itemGenerator;
        this.stateLookup = stateLookup;
        this.dungeonWorldSupplier = dungeonWorldSupplier;
    }

    /** True for any ItemStack this plugin's DungeonItemGenerator actually produced (weapon or armor alike). */
    private boolean isDungeonItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(itemGenerator.getRarityKey(), PersistentDataType.STRING);
    }

    private boolean isInsideDungeon(Player player) {
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());
        return state != null && state.isInsideDungeon();
    }

    /** Rule 1: cancel melee damage dealt with anything except a genuine dungeon weapon or bare hands. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getDamager() instanceof Player player)) return;
        if (!isInsideDungeon(player)) return;

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return; // bare-handed - always allowed
        }
        if (isDungeonItem(held)) {
            return; // genuine dungeon weapon - allowed
        }

        event.setCancelled(true);
        player.sendMessage("§cOutside weapons don't work in the dungeon - find one down here instead.");
    }

    /** Rule 2: revert equipping any armor piece that isn't a genuine dungeon armor piece while inside the dungeon. */
    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        if (!isInsideDungeon(player)) return;

        ItemStack newItem = event.getNewItem();
        if (newItem == null || newItem.getType().isAir()) {
            return; // unequipping - always allowed
        }
        if (isDungeonItem(newItem)) {
            return; // genuine dungeon armor - allowed
        }

        PlayerInventory inventory = player.getInventory();
        switch (event.getSlotType()) {
            case HEAD -> inventory.setHelmet(event.getOldItem());
            case CHEST -> inventory.setChestplate(event.getOldItem());
            case LEGS -> inventory.setLeggings(event.getOldItem());
            case FEET -> inventory.setBoots(event.getOldItem());
        }
        if (!inventory.addItem(newItem).isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), newItem);
        }

        player.sendMessage("§cOutside armor doesn't work in the dungeon - find some down here instead.");
    }

    /** Rule 3: strip already-worn external armor the moment a dungeon-flagged player's world becomes the dungeon world. */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World dungeonWorld = dungeonWorldSupplier.get();
        if (dungeonWorld == null || !player.getWorld().equals(dungeonWorld)) {
            return;
        }
        if (!isInsideDungeon(player)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        stripIfExternal(player, inventory.getHelmet(), inventory::setHelmet);
        stripIfExternal(player, inventory.getChestplate(), inventory::setChestplate);
        stripIfExternal(player, inventory.getLeggings(), inventory::setLeggings);
        stripIfExternal(player, inventory.getBoots(), inventory::setBoots);
    }

    private void stripIfExternal(Player player, ItemStack worn, java.util.function.Consumer<ItemStack> slotSetter) {
        if (worn == null || worn.getType().isAir() || isDungeonItem(worn)) {
            return;
        }
        slotSetter.accept(null);
        if (!player.getInventory().addItem(worn).isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), worn);
        }
    }
}
