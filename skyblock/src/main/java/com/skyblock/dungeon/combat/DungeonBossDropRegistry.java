package com.skyblock.dungeon.combat;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog of unique "boss trophy" items, one per named boss identity
 * in BossArchetypeRegistry, keyed the same way (by the boss's base
 * EntityType). Every floor's boss - ordinary or milestone - drops its
 * matching trophy on death, handled by DungeonMobDropListener
 * alongside the existing gear/sellable boss drops.
 *
 * Kept as a 1:1 mirror of BossArchetypeRegistry's 20 entries so every
 * named boss identity has exactly one guaranteed, flavor-matched item;
 * add a new entry here whenever a new archetype is registered there.
 */
public final class DungeonBossDropRegistry {

    private final Map<EntityType, DungeonBossDropDefinition> byType = new EnumMap<>(EntityType.class);

    public DungeonBossDropRegistry() {
        register(EntityType.ZOMBIE, "undead_lord_signet", "§2§lUndead Lord's Signet", Material.ZOMBIE_HEAD,
            List.of("§7A cold, rotted signet ring", "§7torn from The Undead Lord."));
        register(EntityType.HUSK, "sand_reapers_hourglass", "§6§lSand Reaper's Hourglass", Material.CLOCK,
            List.of("§7Sand still trickles inside,", "§7though its owner is no more."));
        register(EntityType.DROWNED, "deep_tides_trident_shard", "§b§lDeep Tide's Trident Shard", Material.TRIDENT,
            List.of("§7A jagged shard, still humming", "§7with the current's memory."));
        register(EntityType.ZOMBIE_VILLAGER, "plague_doctors_vial", "§5§lPlague Doctor's Vial", Material.POTION,
            List.of("§7An unlabeled vial pried from", "§7the Plague Doctor's coat."));
        register(EntityType.SKELETON, "bone_marshals_baton", "§f§lBone Marshal's Baton", Material.BONE,
            List.of("§7Once used to command volleys -", "§7now still and silent."));
        register(EntityType.WITHER_SKELETON, "wither_heralds_skull", "§8§lWither Herald's Skull",
            Material.WITHER_SKELETON_SKULL,
            List.of("§7Radiates a faint, unpleasant chill."));
        register(EntityType.STRAY, "frost_marshals_icicle", "§b§lFrost Marshal's Icicle", Material.PACKED_ICE,
            List.of("§7Never quite melts, no matter", "§7how far from the Abyss it travels."));
        register(EntityType.BOGGED, "bog_wardens_reed", "§2§lBog Warden's Reed", Material.MUD,
            List.of("§7Still damp from the marsh", "§7the Bog Warden called home."));
        register(EntityType.SPIDER, "broodmothers_fang", "§4§lBroodmother's Fang", Material.SPIDER_EYE,
            List.of("§7Curved and venom-slicked -", "§7best handled carefully."));
        register(EntityType.CAVE_SPIDER, "venomqueens_stinger", "§2§lVenomqueen's Stinger",
            Material.FERMENTED_SPIDER_EYE,
            List.of("§7A single stinger, somehow", "§7still potent."));
        register(EntityType.SILVERFISH, "swarmqueens_carapace", "§7§lSwarmqueen's Carapace", Material.FLINT,
            List.of("§7A chitinous fragment from", "§7the heart of the swarm."));
        register(EntityType.CREEPER, "detonators_fuse", "§a§lDetonator's Fuse", Material.GUNPOWDER,
            List.of("§7Somehow unspent.", "§7Handle with care."));
        register(EntityType.ENDERMAN, "voidwalkers_eye", "§5§lVoidwalker's Eye", Material.ENDER_EYE,
            List.of("§7Still watching, even now."));
        register(EntityType.BLAZE, "cinder_kings_ember", "§6§lCinder King's Ember", Material.BLAZE_ROD,
            List.of("§7Warm to the touch, forever."));
        register(EntityType.MAGMA_CUBE, "ooze_colossuss_core", "§c§lOoze Colossus's Core", Material.MAGMA_CREAM,
            List.of("§7Pulses faintly, like it's", "§7still trying to split."));
        register(EntityType.SLIME, "gelatinous_horrors_core", "§a§lGelatinous Horror's Core", Material.SLIME_BALL,
            List.of("§7Denser and heavier than any", "§7ordinary slimeball."));
        register(EntityType.IRON_GOLEM, "iron_wardens_plate", "§7§lIron Warden's Plate", Material.IRON_INGOT,
            List.of("§7A single plate, dented but", "§7unbroken."));
        register(EntityType.VEX, "wraiths_charm", "§d§lWraith's Charm", Material.PHANTOM_MEMBRANE,
            List.of("§7Flickers between this world", "§7and the next."));
        register(EntityType.EVOKER, "warlocks_totem", "§4§lWarlock's Totem", Material.TOTEM_OF_UNDYING,
            List.of("§7The Warlock's own ward,", "§7claimed as a trophy."));
        register(EntityType.BAT, "night_terrors_wing", "§8§lNight Terror's Wing", Material.INK_SAC,
            List.of("§7Impossibly light,", "§7impossibly quiet."));
    }

    private void register(EntityType type, String id, String displayName, Material icon, List<String> lore) {
        byType.put(type, new DungeonBossDropDefinition(id, displayName, icon, lore));
    }

    /**
     * Resolves a boss trophy definition for the given base entity type,
     * or a generic fallback trophy if this type has no registered
     * identity yet - mirrors BossArchetypeRegistry.get()'s fallback so
     * a boss of an as-yet-unregistered type still always drops
     * something rather than silently dropping nothing.
     */
    public DungeonBossDropDefinition get(EntityType baseType) {
        DungeonBossDropDefinition def = byType.get(baseType);
        if (def != null) {
            return def;
        }
        return new DungeonBossDropDefinition(
            "generic_boss_trophy_" + baseType.name().toLowerCase(),
            "§c§lBoss Trophy",
            Material.NETHER_STAR,
            List.of("§7A trophy from a fallen dungeon boss.")
        );
    }
}
