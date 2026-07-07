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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places a physical loot container with rolled loot in CHEST-type rooms the
 * moment they're carved, per "chest rooms scattered in generated terrain."
 * Intended to be called from a DungeonRoomPlanner.RoomCarveListener.
 *
 * BARREL, not CHEST: this used to place a Material.CHEST and fill it
 * immediately via block.getState() right after setType(). On Paper,
 * getState() without arguments returns a *cached snapshot* - and for a
 * tile entity that was just created this tick (which is exactly what's
 * happening here, mid-carve, often off the normal world-tick cadence),
 * that snapshot can be stale/detached from the real tile entity. Writing
 * items into that detached snapshot and calling update() silently does
 * nothing to the block players actually see, so the chest looked placed
 * but was really empty forever - this was the root cause of "loot chests
 * aren't working." MineSystem's crate barrels sidestep this exact problem
 * two ways: using Material.BARREL (no double-container merge quirks) and,
 * more importantly, never filling at placement time at all - they defer
 * the actual inventory fill to the moment a player first opens it, using
 * block.getState(false) to force a live (non-cached) tile entity lookup.
 * This class now follows the same proven pattern: place an empty barrel
 * here, remember it as "pending," and let DungeonChestLootListener roll
 * and fill it the moment a player right-clicks it open.
 *
 * Each barrel's contents are an independent roll per item between real
 * equippable gear (weapons/armor via DungeonLootTable, the same
 * generator ambient/boss mob kills can drop) and the sellable mob-drop
 * catalog (DungeonDropRegistry - fangs, cores, essences, etc., the
 * same items ambient mobs drop on kill).
 *
 * Looting state itself (room.markLooted()) is NOT set by this class -
 * that has to happen when a player actually empties the barrel
 * (an InventoryCloseEvent/BlockBreakEvent listener elsewhere should
 * call room.markLooted() once the barrel is emptied/destroyed), since
 * this class only runs once at carve time and has no way to observe
 * later player interaction. Once marked looted, the room stays empty
 * forever per the "permanent once explored" scarcity rule - this
 * class will never refill an already-placed barrel.
 */
public final class DungeonChestRoomPlacer {

    private static final int MIN_ITEMS = 2;
    private static final int MAX_ITEMS = 5;
    /** Random tries at finding a verified-open column before falling back to a full scan. */
    private static final int MAX_LOCATE_ATTEMPTS = 20;
    /** Fraction of a barrel's rolled items that come from the mob-drop catalog rather than equippable gear. */
    private static final double MOB_DROP_SHARE = 0.4;

    private final DungeonLootTable lootTable;
    private final DungeonDropRegistry dropRegistry;
    private final DungeonDropItemFactory dropItemFactory;
    private final DungeonRarityRoller rarityRoller;
    private final Random random;

    /**
     * Barrels that have been physically placed but not yet rolled/filled,
     * keyed by "x,y,z" world-block coordinate. DungeonChestLootListener
     * polls this on first open via {@link #pollPendingLoot(int, int, int)}.
     * Plain ConcurrentHashMap since carving can happen off the main
     * thread while interaction handling is always on it.
     */
    private final Map<String, Integer> pendingFloorByLocation = new ConcurrentHashMap<>();

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
        int[] spot = DungeonSpawnLocator.findOpenColumn(world, room, groundY, random, MAX_LOCATE_ATTEMPTS);
        if (spot == null) {
            return; // no verified-open column in this room (yet) - don't embed a barrel in stone
        }

        int barrelX = spot[0];
        int barrelY = spot[1];
        int barrelZ = spot[2];
        Block block = world.getBlockAt(barrelX, barrelY, barrelZ);
        block.setType(Material.BARREL);

        // Don't fill here - see class javadoc. The block was just created
        // this tick, so block.getState() would very likely hand back a
        // stale cached snapshot; writing loot into that snapshot can
        // silently fail to persist. Instead mark it pending and let the
        // listener fill it lazily on first open, using getState(false)
        // to force a live lookup once the tile entity has definitely
        // settled.
        pendingFloorByLocation.put(key(barrelX, barrelY, barrelZ), floorNumber);
    }

    /**
     * Called by DungeonChestLootListener the moment a player right-clicks
     * an unfilled dungeon loot barrel open. Rolls this barrel's contents
     * and removes it from the pending set so it's never re-rolled (a
     * barrel is filled exactly once, then behaves like a normal container
     * for however many times it's reopened before being emptied).
     *
     * @return the rolled items, or null if this location isn't a pending
     *         (unfilled) dungeon loot barrel - e.g. a normal player-placed
     *         barrel, or one that's already been filled.
     */
    public List<ItemStack> pollPendingLoot(int x, int y, int z) {
        Integer floorNumber = pendingFloorByLocation.remove(key(x, y, z));
        if (floorNumber == null) {
            return null;
        }

        int itemCount = MIN_ITEMS + random.nextInt(MAX_ITEMS - MIN_ITEMS + 1);
        List<ItemStack> items = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            ItemStack item = (random.nextDouble() < MOB_DROP_SHARE)
                    ? rollMobDrop(floorNumber)
                    : lootTable.rollLoot(floorNumber);
            items.add(item);
        }
        return items;
    }

    /** True if this location is a placed-but-not-yet-opened dungeon loot barrel. */
    public boolean isPending(int x, int y, int z) {
        return pendingFloorByLocation.containsKey(key(x, y, z));
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
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
