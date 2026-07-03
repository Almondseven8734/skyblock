package com.skyblock.dungeon.combat;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.floor.DungeonFloorManager;
import com.skyblock.dungeon.gen.DungeonBossRoomGeometry;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seals and reopens a boss room's 4 gate doorways.
 *
 * Doorways are carved open by DungeonRoomPlanner as part of the
 * cylinder shape. This class owns everything about when they physically
 * close and reopen:
 *
 *   - The first hit landed on a boss registered to a room arms a
 *     30-second countdown for that room (further hits are no-ops -
 *     one timer per room, started by whichever hit lands first).
 *   - When the countdown elapses, all 4 doorway footprints are filled
 *     with solid blocks, sealing the room.
 *   - The moment the last living boss tied to that room dies, the
 *     doorways are cleared back to air again. Multi-boss milestone
 *     rooms stay sealed until every tied boss is dead.
 *
 * DungeonBossRoomTrigger calls registerBoss() for every boss entity it
 * spawns into a boss room; this class does nothing for any entity that
 * was never registered that way.
 */
public final class DungeonBossGateController implements Listener {

    private static final long SEAL_DELAY_TICKS = 30L * 20L; // 30 seconds

    private final JavaPlugin plugin;
    private final DungeonFloorManager floorManager;
    private final FloorThemeRegistry themeRegistry;

    /** Boss entity UUID -> the room it's tied to. */
    private final Map<UUID, DungeonRoom> bossRooms = new ConcurrentHashMap<>();
    /** Boss entity UUID -> which floor it's on (needed to look up the world/theme at seal time). */
    private final Map<UUID, Integer> bossFloors = new ConcurrentHashMap<>();
    /** Room ID -> count of still-alive bosses tied to it; the room only reopens once this hits 0. */
    private final Map<UUID, Integer> livingBossesByRoom = new ConcurrentHashMap<>();
    /** Room IDs whose seal countdown is already armed, so a second hit doesn't restart the timer. */
    private final Set<UUID> armedRooms = ConcurrentHashMap.newKeySet();
    /** Room IDs currently physically sealed. */
    private final Set<UUID> sealedRooms = ConcurrentHashMap.newKeySet();

    public DungeonBossGateController(JavaPlugin plugin, DungeonFloorManager floorManager,
                                      FloorThemeRegistry themeRegistry) {
        this.plugin = plugin;
        this.floorManager = floorManager;
        this.themeRegistry = themeRegistry;
    }

    /**
     * Call once per boss entity right after DungeonBossRoomTrigger spawns
     * it into a boss room. Must be called before the entity can take any
     * damage, or its first real hit won't arm the seal timer.
     */
    public void registerBoss(UUID bossEntityId, int floorNumber, DungeonRoom room) {
        bossRooms.put(bossEntityId, room);
        bossFloors.put(bossEntityId, floorNumber);
        livingBossesByRoom.merge(room.id(), 1, Integer::sum);
    }

    // ─── First blood → arm the seal timer ───────────────────────────────────

    @EventHandler
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;

        UUID id = event.getEntity().getUniqueId();
        DungeonRoom room = bossRooms.get(id);
        if (room == null) return; // not a tracked dungeon boss

        if (!armedRooms.add(room.id())) return; // already armed - only the first hit anywhere in the room counts

        Integer floorNumber = bossFloors.get(id);
        if (floorNumber == null) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> sealRoom(room, floorNumber), SEAL_DELAY_TICKS);
    }

    // ─── Boss death → reopen once the room's fight is fully over ───────────

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        DungeonRoom room = bossRooms.remove(id);
        Integer floorNumber = bossFloors.remove(id);
        if (room == null || floorNumber == null) return;

        int remaining = livingBossesByRoom.merge(room.id(), -1, Integer::sum);
        if (remaining <= 0) {
            livingBossesByRoom.remove(room.id());
            armedRooms.remove(room.id());
            unsealRoom(room, floorNumber);
        }
    }

    // ─── Physical seal / unseal ──────────────────────────────────────────────

    private void sealRoom(DungeonRoom room, int floorNumber) {
        // Defensive: the boss (or all bosses) may have already died during
        // the 30-second countdown, in which case onBossDeath already fired
        // and there's nothing to seal - don't lock players in an empty room.
        if (!livingBossesByRoom.containsKey(room.id())) return;
        if (!sealedRooms.add(room.id())) return; // already sealed

        World world = floorManager.dungeonWorld();
        if (world == null) return;
        FloorTheme theme = themeRegistry.getTheme(floorNumber);
        int floorY = floorManager.floorBounds().floorBottomY(floorNumber);
        setGateBlocks(world, room, floorY, sealMaterial(theme));
    }

    private void unsealRoom(DungeonRoom room, int floorNumber) {
        if (!sealedRooms.remove(room.id())) return; // wasn't sealed - nothing to do

        World world = floorManager.dungeonWorld();
        if (world == null) return;
        int floorY = floorManager.floorBounds().floorBottomY(floorNumber);
        setGateBlocks(world, room, floorY, Material.AIR);
    }

    /** Fills or clears all 4 gate doorway footprints for a boss room. */
    private void setGateBlocks(World world, DungeonRoom room, int floorY, Material material) {
        int minY = floorY + FloorBounds.SOLID_FLOOR_LAYERS;
        int maxY = floorY + DungeonBossRoomGeometry.HEIGHT - FloorBounds.SOLID_CEIL_LAYERS - 1;
        int r = DungeonBossRoomGeometry.RADIUS;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (!DungeonBossRoomGeometry.isInsideWallRing(dx, dz)) continue;
                if (DungeonBossRoomGeometry.gateAt(dx, dz) == null) continue; // solid wall, not a gate cell

                int wx = room.centerX() + dx;
                int wz = room.centerZ() + dz;
                for (int y = minY; y <= maxY; y++) {
                    world.getBlockAt(wx, y, wz).setType(material, false);
                }
            }
        }
        // Note: sealing uses a themed full block rather than the wall's
        // random primary/accent pick, so a closing gate reads as visually
        // distinct from the permanent drum wall (see sealMaterial()).
    }

    private Material sealMaterial(FloorTheme theme) {
        List<Material> primary = theme != null ? theme.getPrimaryBlocks() : null;
        if (primary == null || primary.isEmpty()) return Material.OBSIDIAN;
        // Iron bars would be the visually "correct" portcullis look, but
        // bars are non-full blocks and won't reliably stop players from
        // clipping/shooting through gaps at the seams - a themed full
        // block matching the wall keeps the seal airtight.
        return primary.get(0);
    }
}
