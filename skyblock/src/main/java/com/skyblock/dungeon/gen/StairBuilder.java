package com.skyblock.dungeon.gen;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a spiral staircase from floorY1 (top) down to floorY2 (bottom),
 * ported from an earlier standalone prototype's stairBuilder.js.
 *
 * Replaces the plain 2x2 ladder shaft DungeonStaircaseOrchestrator used
 * to bore between floors - a ladder shaft is functional but reads as a
 * mineshaft ladder hole, not a staircase. This instead produces a
 * proper 3x3 spiral: a solid center support post, with stone brick
 * stair treads winding clockwise (east -> south -> west -> north) around
 * it one block per Y level, plus a corner support block opposite each
 * tread so the spiral has a visually solid backbone rather than treads
 * floating in open air.
 *
 * Pure block-list generation, no world/Bukkit-side-effect calls here -
 * DungeonStaircaseOrchestrator is the only caller and owns actually
 * writing these blocks (plus clearing/theming everything around the
 * shaft exactly as it already does for the landing/border punch-through).
 */
public final class StairBuilder {

    private StairBuilder() {}

    // [dx, dz, treadFacingIndex, supportDx, supportDz]
    private static final int[][] SPIRAL = {
        { 1,  0, 0,  1, -1},   // east  tread
        { 0,  1, 1,  1,  1},   // south tread
        {-1,  0, 2, -1,  1},   // west  tread
        { 0, -1, 3, -1, -1},   // north tread
    };

    // Java Edition Stairs facing: the facing is where the "bottom" (wide
    // end) points. Low/open end points toward the centre post so the
    // stairs read as ascending toward the post, matching the spiral's
    // direction of travel.
    private static final BlockFace[] FACING = {
        BlockFace.WEST,   // east tread  → low end faces west (toward post)
        BlockFace.NORTH,  // south tread → low end faces north
        BlockFace.EAST,   // west tread  → low end faces east
        BlockFace.SOUTH,  // north tread → low end faces south
    };

    public record StairBlock(int x, int y, int z, Material material, BlockFace facing) {}

    /**
     * @param shaftX/shaftZ center post position
     * @param floorY1        top Y (inclusive) - where the shaft starts, on the upper floor
     * @param floorY2        bottom Y (inclusive) - where the shaft ends, on the lower floor
     * @param treadMaterial  stair block material (theme's primary stair-capable block)
     * @param supportMaterial center post / corner support material (theme's primary solid block)
     */
    public static List<StairBlock> buildSpiralStaircase(int shaftX, int shaftZ, int floorY1, int floorY2,
                                                          Material treadMaterial, Material supportMaterial) {
        List<StairBlock> blocks = new ArrayList<>();
        if (floorY2 >= floorY1) return blocks;

        // 1. Clear the 3x3 shaft.
        for (int y = floorY2; y <= floorY1; y++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    blocks.add(new StairBlock(shaftX + dx, y, shaftZ + dz, Material.AIR, null));

        // 2. Centre support post.
        for (int y = floorY2; y <= floorY1; y++)
            blocks.add(new StairBlock(shaftX, y, shaftZ, supportMaterial, null));

        // 3. Treads + corner supports, spiralling one quarter-turn per Y level.
        int si = 0;
        for (int y = floorY1; y >= floorY2; y--) {
            int[] s = SPIRAL[si % 4];
            blocks.add(new StairBlock(shaftX + s[0], y, shaftZ + s[1], treadMaterial, FACING[si % 4]));
            blocks.add(new StairBlock(shaftX + s[3], y, shaftZ + s[4], supportMaterial, null));
            si++;
        }
        return blocks;
    }

    /** Returns the 3x3 footprint offsets (dx,dz pairs) that the shaft occupies. */
    public static int[][] getStaircaseFootprint() {
        List<int[]> cells = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                cells.add(new int[]{dx, dz});
        return cells.toArray(new int[0][]);
    }

    /**
     * Resolves a theme's primary block to the closest matching *_STAIRS
     * variant, so a floor's staircase reads as built from that floor's
     * own material instead of always defaulting to plain stone brick.
     * Not every theme block has a stair variant (e.g. MAGMA_BLOCK,
     * PACKED_ICE, SNOW_BLOCK) - those fall back to STONE_BRICK_STAIRS/
     * STONE_BRICKS, which always exist and look reasonable regardless
     * of surrounding theme.
     */
    public static Material resolveStairMaterial(Material primary) {
        return switch (primary) {
            case STONE, COBBLESTONE -> Material.STONE_BRICK_STAIRS;
            case MOSSY_COBBLESTONE -> Material.MOSSY_STONE_BRICK_STAIRS;
            case DEEPSLATE, COBBLED_DEEPSLATE -> Material.COBBLED_DEEPSLATE_STAIRS;
            case DEEPSLATE_BRICKS -> Material.DEEPSLATE_BRICK_STAIRS;
            case DEEPSLATE_TILES -> Material.DEEPSLATE_TILE_STAIRS;
            case BLACKSTONE -> Material.BLACKSTONE_STAIRS;
            case GILDED_BLACKSTONE -> Material.BLACKSTONE_STAIRS;
            case TUFF -> Material.TUFF_STAIRS;
            case ANDESITE -> Material.ANDESITE_STAIRS;
            case GRANITE -> Material.GRANITE_STAIRS;
            case DIORITE -> Material.DIORITE_STAIRS;
            case BRICKS -> Material.BRICK_STAIRS;
            case NETHER_BRICKS -> Material.NETHER_BRICK_STAIRS;
            case QUARTZ_BLOCK -> Material.QUARTZ_STAIRS;
            case PURPUR_BLOCK -> Material.PURPUR_STAIRS;
            default -> Material.STONE_BRICK_STAIRS;
        };
    }

    /**
     * Resolves a theme's primary block to a matching solid support-post
     * material. For most themes this is just the primary block itself
     * (it's already a solid, structurally-sensible block); the few
     * exceptions are blocks that would look odd or behave oddly as a
     * load-bearing spiral post (e.g. gravity-affected or fragile blocks).
     */
    public static Material resolveSupportMaterial(Material primary) {
        return switch (primary) {
            case MAGMA_BLOCK -> Material.BLACKSTONE;
            default -> primary;
        };
    }
}
