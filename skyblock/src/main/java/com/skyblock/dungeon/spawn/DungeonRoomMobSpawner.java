package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.combat.MobLevelRoller;
import com.skyblock.dungeon.combat.ai.BatSwoopAI;
import com.skyblock.dungeon.combat.ai.HostileGolemAI;
import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.gen.DungeonRoom;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Bat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Populates a newly carved room with ambient mobs drawn from that
 * floor's FloorTheme mob pool, leveled via MobLevelRoller/
 * MobLevelApplicator. Intended to be called from a
 * DungeonRoomPlanner.RoomCarveListener the moment a NORMAL or CHEST
 * room finishes carving - BOSS and BUFFER rooms are skipped here,
 * since boss rooms get their own dedicated trigger and buffer rooms
 * are meant to be plain connectors per design.
 *
 * Per design, each room only draws from a 1-3 type subset of the
 * floor's full mob pool (picked once per room) rather than mixing the
 * whole roster freely - so a given room reads as "the spider room" or
 * "the zombie/husk room" instead of a random grab-bag.
 */
public final class DungeonRoomMobSpawner {

    /** Roughly one ambient mob per this many blocks of room footprint area. */
    private static final int BLOCKS_PER_MOB = 18;
    private static final int MIN_MOBS_PER_ROOM = 1;
    private static final int MAX_MOBS_PER_ROOM = 6;
    /** Random tries at finding a verified-open column before falling back to a full scan. */
    private static final int MAX_LOCATE_ATTEMPTS = 12;

    private static final int MIN_ROOM_MOB_TYPES = 1;
    private static final int MAX_ROOM_MOB_TYPES = 3;

    private final JavaPlugin plugin;
    private final FloorThemeRegistry themeRegistry;
    private final MobLevelRoller levelRoller;
    private final MobLevelApplicator levelApplicator;
    private final Random random;

    public DungeonRoomMobSpawner(JavaPlugin plugin, FloorThemeRegistry themeRegistry,
                                  MobLevelRoller levelRoller, MobLevelApplicator levelApplicator,
                                  Random random) {
        this.plugin = plugin;
        this.themeRegistry = themeRegistry;
        this.levelRoller = levelRoller;
        this.levelApplicator = levelApplicator;
        this.random = random;
    }

    public void spawnForRoom(World world, int floorNumber, int floorBottomY, DungeonRoom room) {
        if (room.type() == DungeonRoom.Type.BOSS || room.type() == DungeonRoom.Type.BUFFER) {
            return;
        }

        FloorTheme theme = themeRegistry.getTheme(floorNumber);
        List<EntityType> roomPool = pickRoomMobTypes(theme.getMobPool());
        if (roomPool.isEmpty()) {
            return;
        }

        // groundY is the first carvable cave-band layer (on TOP of the solid
        // floor slab), not floorBottomY+1 - that offset is still inside the
        // solid floor itself and was the source of mobs spawning embedded
        // in the ground.
        int groundY = floorBottomY + com.skyblock.dungeon.util.FloorBounds.SOLID_FLOOR_LAYERS;

        int footprintArea = (room.radiusX() * 2 + 1) * (room.radiusZ() * 2 + 1);
        int mobCount = Math.min(MAX_MOBS_PER_ROOM,
                Math.max(MIN_MOBS_PER_ROOM, footprintArea / BLOCKS_PER_MOB));

        for (int i = 0; i < mobCount; i++) {
            // The room's radiusX/radiusZ describe a bounding box for graph
            // bookkeeping, not the real carved cave shape - only ~35-40% of
            // that box is actually open. Verify a real spot rather than
            // trusting the box, or mobs end up embedded in stone walls/floor.
            int[] spot = DungeonSpawnLocator.findOpenColumn(world, room, groundY, random, MAX_LOCATE_ATTEMPTS);
            if (spot == null) {
                continue; // this room has no verified-open column (yet) - skip this mob rather than embed it
            }

            EntityType type = roomPool.get(random.nextInt(roomPool.size()));
            Location spawnLoc = new Location(world, spot[0] + 0.5, groundY, spot[1] + 0.5);

            if (!(world.spawnEntity(spawnLoc, type) instanceof LivingEntity entity)) {
                continue;
            }

            int level = levelRoller.rollAmbientLevel(floorNumber);
            levelApplicator.applyLevel(entity, level);
            hookCustomAi(entity);
        }
    }

    /**
     * Picks a stable 1-3 type subset of a floor's full mob pool for one
     * room. Capped to the pool's own size in case a theme is configured
     * with fewer than 3 types.
     */
    private List<EntityType> pickRoomMobTypes(List<EntityType> fullPool) {
        if (fullPool.isEmpty()) {
            return List.of();
        }
        int max = Math.min(MAX_ROOM_MOB_TYPES, fullPool.size());
        int count = MIN_ROOM_MOB_TYPES + (max > MIN_ROOM_MOB_TYPES ? random.nextInt(max - MIN_ROOM_MOB_TYPES + 1) : 0);

        List<EntityType> shuffled = new ArrayList<>(fullPool);
        java.util.Collections.shuffle(shuffled, random);
        return shuffled.subList(0, count);
    }

    /** Bats and iron golems don't naturally attack players - bolt on custom AI for them. */
    private void hookCustomAi(LivingEntity entity) {
        if (entity instanceof Bat bat) {
            BatSwoopAI.start(plugin, bat);
        } else if (entity instanceof IronGolem golem) {
            HostileGolemAI.start(plugin, golem);
        }
    }
}
