package com.skyblock.dungeon.config;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Builds and caches FloorTheme instances per floor number.
 *
 * Depth bands shift the block palette and mob pool every few floors;
 * every 5th floor is flagged as a milestone floor (multi-boss / full
 * custom boss script, per design). This registry is intentionally
 * simple and hand-authored for now - swap in data-driven config
 * (e.g. a YAML file) later without changing callers.
 */
public final class FloorThemeRegistry {

    private static final int MILESTONE_INTERVAL = 5;

    private final Map<Integer, FloorTheme> cache = new ConcurrentHashMap<>();

    public FloorTheme getTheme(int floorNumber) {
        if (floorNumber < 1) {
            throw new IllegalArgumentException("floorNumber must be >= 1");
        }
        return cache.computeIfAbsent(floorNumber, this::buildTheme);
    }

    public boolean isMilestoneFloor(int floorNumber) {
        return floorNumber % MILESTONE_INTERVAL == 0;
    }

    private FloorTheme buildTheme(int floorNumber) {
        boolean milestone = isMilestoneFloor(floorNumber);
        DepthBand band = DepthBand.forFloor(floorNumber);

        int bossCount = milestone ? 2 : 1; // mini-boss + main boss on milestone floors

        return new FloorTheme(
            floorNumber,
            band.displayName + " (Floor " + floorNumber + ")",
            band.primaryBlocks,
            band.accentBlocks,
            band.mobPool,
            band.bossPool,
            band.hazardTags,
            milestone,
            bossCount
        );
    }

    /**
     * Hand-authored depth bands controlling palette/mob shifts every
     * few floors. Bands repeat/extrapolate past their last defined
     * tier rather than throwing, so floor counts beyond what's
     * explicitly listed still resolve to something reasonable.
     */
    private enum DepthBand {
        // Per-band mobPool is the *eligible* ambient roster for that depth -
        // DungeonRoomMobSpawner draws a 1-3 type subset from this pool per
        // room, it doesn't spawn the whole pool at once. Rosters are
        // hand-picked per user spec (20 mob types total, spread so shallow
        // floors skew toward weaker/simpler mobs and deep floors introduce
        // the harder-hitting ones like blaze/wither skeleton/evoker/vex).
        //
        // bossPool is a separate, curated subset of that same roster -
        // "trash" mobs that make for an anticlimactic or silly-looking boss
        // (bats, silverfish, cave spiders) are deliberately left out. A
        // floor's boss room trigger rolls randomly from its band's bossPool
        // every time it fires, instead of deterministically always being
        // the same single mob type every reset.
        STONE_CAVES(1, 4, "Stone Caverns",
            List.of(Material.STONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE),
            List.of(Material.MOSS_BLOCK, Material.GRAVEL),
            List.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.CAVE_SPIDER,
                    EntityType.SILVERFISH, EntityType.BAT, EntityType.ZOMBIE_VILLAGER),
            List.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.ZOMBIE_VILLAGER),
            List.of("none")),

        DEEPSLATE_HALLS(5, 9, "Deepslate Halls",
            List.of(Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.TUFF),
            List.of(Material.DEEPSLATE_BRICKS, Material.DEEPSLATE_TILES),
            List.of(EntityType.SKELETON, EntityType.HUSK, EntityType.SILVERFISH,
                    EntityType.DROWNED, EntityType.ENDERMAN, EntityType.CREEPER),
            List.of(EntityType.SKELETON, EntityType.HUSK, EntityType.DROWNED,
                    EntityType.ENDERMAN, EntityType.CREEPER),
            List.of("darkness")),

        SCORCHED_DEPTHS(10, 14, "Scorched Depths",
            List.of(Material.BLACKSTONE, Material.BASALT, Material.MAGMA_BLOCK),
            List.of(Material.NETHERRACK, Material.GILDED_BLACKSTONE),
            List.of(EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.MAGMA_CUBE,
                    EntityType.SLIME, EntityType.HUSK, EntityType.IRON_GOLEM),
            List.of(EntityType.BLAZE, EntityType.WITHER_SKELETON, EntityType.MAGMA_CUBE,
                    EntityType.SLIME, EntityType.IRON_GOLEM),
            List.of("fire", "lava_pits")),

        FROZEN_ABYSS(15, 18, "Frozen Abyss",
            List.of(Material.PACKED_ICE, Material.BLUE_ICE, Material.SNOW_BLOCK),
            List.of(Material.ICE, Material.POWDER_SNOW),
            List.of(EntityType.STRAY, EntityType.BOGGED, EntityType.VEX,
                    EntityType.EVOKER, EntityType.ENDERMAN, EntityType.IRON_GOLEM),
            List.of(EntityType.STRAY, EntityType.BOGGED, EntityType.VEX,
                    EntityType.EVOKER, EntityType.ENDERMAN, EntityType.IRON_GOLEM),
            List.of("freezing", "low_visibility"));

        final int minFloor;
        final int maxFloor;
        final String displayName;
        final List<Material> primaryBlocks;
        final List<Material> accentBlocks;
        final List<EntityType> mobPool;
        final List<EntityType> bossPool;
        final List<String> hazardTags;

        DepthBand(int minFloor, int maxFloor, String displayName,
                  List<Material> primaryBlocks, List<Material> accentBlocks,
                  List<EntityType> mobPool, List<EntityType> bossPool, List<String> hazardTags) {
            this.minFloor = minFloor;
            this.maxFloor = maxFloor;
            this.displayName = displayName;
            this.primaryBlocks = primaryBlocks;
            this.accentBlocks = accentBlocks;
            this.mobPool = mobPool;
            this.bossPool = bossPool;
            this.hazardTags = hazardTags;
        }

        static DepthBand forFloor(int floorNumber) {
            for (DepthBand band : values()) {
                if (floorNumber >= band.minFloor && floorNumber <= band.maxFloor) {
                    return band;
                }
            }
            // Past the last defined band: keep using the deepest band's theme.
            return FROZEN_ABYSS;
        }
    }
}
