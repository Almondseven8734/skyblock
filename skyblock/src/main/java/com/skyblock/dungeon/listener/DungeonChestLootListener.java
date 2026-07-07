package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.gen.RoomGraph;
import com.skyblock.dungeon.spawn.DungeonChestRoomPlacer;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Handles dungeon loot barrels end to end: lazily rolling/filling a
 * barrel's contents the moment a player first opens it, and marking a
 * CHEST-type room's DungeonRoom as looted once a player empties the
 * barrel (inventory contains no items when closed) or breaks it outright.
 *
 * NOTE: the physical container is a BARREL, not a CHEST - see
 * DungeonChestRoomPlacer's javadoc for why (Paper's getState() cache
 * returning a stale snapshot for a tile entity created earlier that same
 * tick, which silently ate any items written into a chest at carve time).
 * The class name is kept as-is to avoid churning every other place that
 * references it; "chest" here means "dungeon loot container" generically.
 *
 * Per design, once a room is looted it stays looted forever for the
 * whole server this week - DungeonChestRoomPlacer already checks
 * room.isLooted() and refuses to place a new barrel, so this listener
 * is the other half of that contract: actually flipping the flag.
 *
 * Resolves which floor's RoomGraph to check via a supplied
 * IntFunction<RoomGraph> rather than owning floor state directly,
 * keeping this decoupled from DungeonFloorManager's internals -
 * wire it as floorNumber -> floorManager.getOrCreateRoomGraph(floorNumber).
 *
 * Floor number + room lookup both happen by world coordinate since
 * barrels don't carry floor metadata themselves; the caller's
 * floorForY function maps a block's Y coordinate back to a floor
 * number using FloorBounds.
 */
public final class DungeonChestLootListener implements Listener {

    @FunctionalInterface
    public interface FloorForY {
        int floorForY(int y);
    }

    private final IntFunction<RoomGraph> roomGraphForFloor;
    private final FloorForY floorForY;
    private final DungeonChestRoomPlacer chestRoomPlacer;

    public DungeonChestLootListener(IntFunction<RoomGraph> roomGraphForFloor, FloorForY floorForY,
                                     DungeonChestRoomPlacer chestRoomPlacer) {
        this.roomGraphForFloor = roomGraphForFloor;
        this.floorForY = floorForY;
        this.chestRoomPlacer = chestRoomPlacer;
    }

    /**
     * Lazily fills a dungeon loot barrel the first time it's opened.
     * Uses getState(false) to bypass Paper's cached-snapshot BlockState
     * and force a live tile entity lookup - by interaction time the
     * barrel has definitely ticked at least once, so this always sees
     * the real container (unlike filling eagerly at carve time).
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.BARREL) {
            return;
        }
        if (!chestRoomPlacer.isPending(block.getX(), block.getY(), block.getZ())) {
            return; // not a dungeon loot barrel, or already filled - let vanilla handle it
        }

        List<ItemStack> loot = chestRoomPlacer.pollPendingLoot(block.getX(), block.getY(), block.getZ());
        if (loot == null) {
            return; // raced with another poll and lost - nothing to fill
        }

        BlockState state = block.getState(false);
        if (!(state instanceof Barrel barrel)) {
            return; // shouldn't happen given the type check above, but stay defensive
        }
        Inventory inv = barrel.getInventory();
        for (ItemStack item : loot) {
            inv.addItem(item);
        }
        // force=true writes the inventory NBT to the world immediately.
        barrel.update(true, false);
        // Allow vanilla open - player sees the now-filled inventory normally.
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (inv.getHolder() == null) {
            return;
        }
        if (!(inv.getHolder() instanceof Barrel barrelHolder)) {
            return;
        }
        if (!isEmpty(inv)) {
            return; // still has items - not fully looted yet
        }
        markLootedIfDungeonBarrel(barrelHolder.getBlock());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BARREL) {
            return;
        }
        markLootedIfDungeonBarrel(block);
    }

    private boolean isEmpty(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    private void markLootedIfDungeonBarrel(Block barrelBlock) {
        int floorNumber = floorForY.floorForY(barrelBlock.getY());
        if (floorNumber < 1) {
            return; // not inside any dungeon floor's vertical band
        }

        RoomGraph graph = roomGraphForFloor.apply(floorNumber);
        if (graph == null) {
            return;
        }

        DungeonRoom room = graph.roomContaining(barrelBlock.getX(), barrelBlock.getZ());
        if (room == null || room.type() != DungeonRoom.Type.CHEST) {
            return; // not a dungeon loot room - leave normal barrels alone
        }

        room.markLooted();
    }
}
