package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.drops.DungeonDropDefinition;
import com.skyblock.dungeon.drops.DungeonDropItemFactory;
import com.skyblock.dungeon.drops.DungeonDropRegistry;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.loot.DungeonLootTable;
import com.skyblock.dungeon.loot.DungeonRarity;
import com.skyblock.dungeon.loot.DungeonRarityRoller;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Places a physical chest with rolled loot in CHEST-type rooms the
 * moment they're carved, per "chest rooms scattered in generated
 * terrain." Intended to be called from a
 * DungeonRoomPlanner.RoomCarveListener.
 *
 * Each chest's contents are an independent roll per item between real
 * equippable gear (weapons/armor via DungeonLootTable, the same
 * generator ambient/boss mob kills can drop) and the sellable mob-drop
 * catalog (DungeonDropRegistry - fangs, cores, essences, etc., the
 * same items ambient mobs drop on kill). Previously chests only ever
 * rolled gear, so mob-drop items - despite being a whole 300-entry
 * catalog with their own sell prices at the guild merchant - could
 * only ever be obtained by grinding kills, never found in a chest.
 *
 * Looting state itself (room.markLooted()) is NOT set by this class -
 * that has to happen when a player actually empties the chest
 * (an InventoryCloseEvent/BlockBreakEvent listener elsewhere should
 * call room.markLooted() once the chest is emptied/destroyed), since
 * this class only runs once at carve time and has no way to observe
 * later player interaction. Once marked looted, the room stays empty
 * forever per the "permanent once explored" scarcity rule - this
 * class will never refill an already-placed chest.
 */
public final class DungeonChestRoomPlacer {

    private static final int MIN_ITEMS = 2;
    private static final int MAX_ITEMS = 5;
    /** Random tries at finding a verified-open column before falling back to a full scan. */
    private static final int MAX_LOCATE_ATTEMPTS = 20;
    /** Fraction of a chest's rolled items that come from the mob-drop catalog rather than equippable gear. */
    private static final double MOB_DROP_SHARE = 0.4;

    private final DungeonLootTable lootTable;
    private final DungeonDropRegistry dropRegistry;
    private final DungeonDropItemFactory dropItemFactory;
    private final DungeonRarityRoller rarityRoller;
    private final Random random;

    public DungeonChestRoomPlacer(DungeonLootTable lootTable, DungeonDropRegistry dropRegistry,
                                   DungeonDropItemFactory dropItemFactory, DungeonRarityRoller rarityRoller,
                                   Random random) {
        this.lootTable = lootTable;
        this.dropRegistry = dropRegistry;
        this.dropItemFactory = dropItemFactory;
        this.rarityRoller = rarityRoller;
        this.random = random;
    }

    public void placeForRoom(World world, int floorNumber, int floorBottomY, DungeonRoom room) {
        if (room.type() != DungeonRoom.Type.CHEST) {
            return;
        }
        if (room.isLooted()) {
            return; // already emptied earlier this week - permanent scarcity, never refill
        }

        // groundY is the first carvable cave-band layer (on TOP of the solid
        // floor slab), not floorBottomY+1 - that offset is still inside the
        // solid floor itself and was the source of chests spawning embedded
        // in the ground.
        int groundY = floorBottomY + com.skyblock.dungeon.util.FloorBounds.SOLID_FLOOR_LAYERS;

        // The room's centerX/centerZ is just the chunk's midpoint, with no
        // guarantee the noise carver actually opened that exact column -
        // roughly 60-65% of the time it's still solid stone. Search for a
        // verified-open column instead of blindly placing at the center.
        // findOpenColumn also probes a few layers above groundY now, since
        // the floor/ceiling taper can leave the exact groundY layer solid
        // even in an otherwise-open room - see DungeonSpawnLocator's doc.
        int[] spot = DungeonSpawnLocator.findOpenColumn(world, room, groundY, random, MAX_LOCATE_ATTEMPTS);
        if (spot == null) {
            return; // no verified-open column in this room (yet) - don't embed a chest in stone
        }

        int chestX = spot[0];
        int chestY = spot[1];
        int chestZ = spot[2];
        Block block = world.getBlockAt(chestX, chestY, chestZ);
        block.setType(Material.CHEST);

        if (!(block.getState() instanceof Chest chestState)) {
            return; // shouldn't happen given the setType above, but stay defensive
        }

        int itemCount = MIN_ITEMS + random.nextInt(MAX_ITEMS - MIN_ITEMS + 1);
        for (int i = 0; i < itemCount; i++) {
            ItemStack item = (random.nextDouble() < MOB_DROP_SHARE)
                    ? rollMobDrop(floorNumber)
                    : lootTable.rollLoot(floorNumber);
            chestState.getInventory().addItem(item);
        }
        chestState.update();
    }

    /** Rolls one sellable mob-drop item at floor-appropriate rarity, falling back to gear if the rarity pool is somehow empty. */
    private ItemStack rollMobDrop(int floorNumber) {
        DungeonRarity rarity = rarityRoller.roll(floorNumber);
        List<DungeonDropDefinition> pool = dropRegistry.forRarity(rarity);
        if (pool.isEmpty()) {
            return lootTable.rollLoot(floorNumber);
        }
        DungeonDropDefinition definition = pool.get(random.nextInt(pool.size()));
        return dropItemFactory.build(definition);
    }
}
