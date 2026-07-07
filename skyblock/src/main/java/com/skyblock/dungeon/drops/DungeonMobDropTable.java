package com.skyblock.dungeon.drops;

import com.skyblock.dungeon.loot.DungeonRarity;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Mob-specific sellable drop catalog: exactly 5 unique, thematically
 * named drops per dungeon-eligible EntityType (20 types x 5 = 100
 * entries), on top of the existing 300-entry generic
 * DungeonDropRegistry catalog.
 *
 * Previously DungeonMobDropListener rolled every ambient/boss kill
 * against the same generic 300-entry pool regardless of what actually
 * died - a spider and a blaze had an identical drop table. This class
 * gives each mob type its own small themed pool; DungeonMobDropListener
 * prefers this table when the victim's EntityType has an entry here,
 * falling back to the generic DungeonDropRegistry pool for any type
 * not covered (so nothing ever fails to drop just because a type is
 * missing from this table).
 *
 * IDs are namespaced "mobdrop_<entitytype>_<slug>" so they can never
 * collide with the generic registry's "<rarity>_<noun>_<adjective>"
 * ids - both id spaces can be looked up through the same
 * DungeonDropItemFactory/guild-merchant sell path without ambiguity.
 */
public final class DungeonMobDropTable {

    private final Map<EntityType, List<DungeonDropDefinition>> byType = new EnumMap<>(EntityType.class);
    private final List<DungeonDropDefinition> all = new ArrayList<>();

    public DungeonMobDropTable() {
        register(EntityType.ZOMBIE,
            entry("zombie", "rotten_locket", "Rotten Locket", DungeonRarity.COMMON, 6, Material.IRON_NUGGET),
            entry("zombie", "graveyard_soil", "Clump of Graveyard Soil", DungeonRarity.COMMON, 5, Material.PODZOL),
            entry("zombie", "gnawed_bone", "Gnawed Bone", DungeonRarity.UNCOMMON, 16, Material.BONE),
            entry("zombie", "shambler_tooth", "Shambler's Tooth", DungeonRarity.RARE, 42, Material.BONE_MEAL),
            entry("zombie", "cursed_wedding_ring", "Cursed Wedding Ring", DungeonRarity.EPIC, 110, Material.GOLD_NUGGET)
        );

        register(EntityType.HUSK,
            entry("husk", "sunbaked_wrap", "Sunbaked Wrap", DungeonRarity.COMMON, 6, Material.LEATHER),
            entry("husk", "dry_husk_flesh", "Dried Husk Flesh", DungeonRarity.COMMON, 5, Material.ROTTEN_FLESH),
            entry("husk", "sand_crusted_fang", "Sand-Crusted Fang", DungeonRarity.UNCOMMON, 17, Material.BONE),
            entry("husk", "desiccated_heart", "Desiccated Heart", DungeonRarity.RARE, 44, Material.NETHER_WART),
            entry("husk", "mirage_shard", "Mirage Shard", DungeonRarity.EPIC, 115, Material.PRISMARINE_CRYSTALS)
        );

        register(EntityType.DROWNED,
            entry("drowned", "waterlogged_trident_bit", "Waterlogged Trident Bit", DungeonRarity.UNCOMMON, 18, Material.PRISMARINE_SHARD),
            entry("drowned", "coral_encrusted_rib", "Coral-Encrusted Rib", DungeonRarity.COMMON, 6, Material.BRAIN_CORAL_FAN),
            entry("drowned", "brine_gill", "Brine Gill", DungeonRarity.COMMON, 5, Material.KELP),
            entry("drowned", "abyssal_pearl", "Abyssal Pearl", DungeonRarity.RARE, 46, Material.PRISMARINE_CRYSTALS),
            entry("drowned", "sea_wraith_core", "Sea Wraith Core", DungeonRarity.EPIC, 118, Material.HEART_OF_THE_SEA)
        );

        register(EntityType.ZOMBIE_VILLAGER,
            entry("zombie_villager", "torn_apron_scrap", "Torn Apron Scrap", DungeonRarity.COMMON, 6, Material.LEATHER),
            entry("zombie_villager", "infected_trade_token", "Infected Trade Token", DungeonRarity.UNCOMMON, 17, Material.EMERALD),
            entry("zombie_villager", "plague_vial", "Plague Vial", DungeonRarity.RARE, 43, Material.GLASS_BOTTLE),
            entry("zombie_villager", "withered_bell_shard", "Withered Bell Shard", DungeonRarity.EPIC, 112, Material.BELL),
            entry("zombie_villager", "forgotten_ledger_page", "Forgotten Ledger Page", DungeonRarity.LEGENDARY, 270, Material.PAPER)
        );

        register(EntityType.SPIDER,
            entry("spider", "silk_gland", "Silk Gland", DungeonRarity.COMMON, 6, Material.STRING),
            entry("spider", "cracked_mandible", "Cracked Mandible", DungeonRarity.COMMON, 5, Material.SPIDER_EYE),
            entry("spider", "compound_eye", "Compound Eye Cluster", DungeonRarity.UNCOMMON, 17, Material.SPIDER_EYE),
            entry("spider", "webweaver_claw", "Webweaver Claw", DungeonRarity.RARE, 44, Material.PHANTOM_MEMBRANE),
            entry("spider", "broodmother_ichor", "Broodmother Ichor", DungeonRarity.EPIC, 116, Material.FERMENTED_SPIDER_EYE)
        );

        register(EntityType.CAVE_SPIDER,
            entry("cave_spider", "venom_sac", "Venom Sac", DungeonRarity.COMMON, 7, Material.FERMENTED_SPIDER_EYE),
            entry("cave_spider", "brittle_leg_joint", "Brittle Leg Joint", DungeonRarity.COMMON, 5, Material.BONE),
            entry("cave_spider", "tunnel_silk", "Tunnel Silk", DungeonRarity.UNCOMMON, 18, Material.STRING),
            entry("cave_spider", "paralytic_fang", "Paralytic Fang", DungeonRarity.RARE, 45, Material.SPIDER_EYE),
            entry("cave_spider", "burrower_carapace", "Burrower Carapace", DungeonRarity.EPIC, 117, Material.TURTLE_SCUTE)
        );

        register(EntityType.SILVERFISH,
            entry("silverfish", "stone_dust_pellet", "Stone Dust Pellet", DungeonRarity.COMMON, 5, Material.GRAY_DYE),
            entry("silverfish", "mineral_scale", "Mineral Scale", DungeonRarity.COMMON, 6, Material.CLAY_BALL),
            entry("silverfish", "burrow_husk", "Burrow Husk", DungeonRarity.UNCOMMON, 15, Material.QUARTZ),
            entry("silverfish", "swarm_core", "Swarm Core", DungeonRarity.RARE, 40, Material.PRISMARINE_CRYSTALS),
            entry("silverfish", "stoneform_relic", "Stoneform Relic", DungeonRarity.EPIC, 105, Material.CHISELED_STONE_BRICKS)
        );

        register(EntityType.BAT,
            entry("bat", "leathery_wing", "Leathery Wing Membrane", DungeonRarity.COMMON, 6, Material.PHANTOM_MEMBRANE),
            entry("bat", "sonar_bone", "Sonar Bone", DungeonRarity.COMMON, 5, Material.BONE),
            entry("bat", "echofang", "Echofang", DungeonRarity.UNCOMMON, 16, Material.BONE),
            entry("bat", "nightflyer_talon", "Nightflyer Talon", DungeonRarity.RARE, 41, Material.PHANTOM_MEMBRANE),
            entry("bat", "duskwing_relic", "Duskwing Relic", DungeonRarity.EPIC, 108, Material.GRAY_DYE)
        );

        register(EntityType.SKELETON,
            entry("skeleton", "splintered_arrow", "Splintered Arrow Shaft", DungeonRarity.COMMON, 5, Material.ARROW),
            entry("skeleton", "rib_fragment", "Rib Fragment", DungeonRarity.COMMON, 6, Material.BONE),
            entry("skeleton", "bowstring_remnant", "Bowstring Remnant", DungeonRarity.UNCOMMON, 17, Material.STRING),
            entry("skeleton", "marksman_finger_bone", "Marksman's Finger Bone", DungeonRarity.RARE, 44, Material.BONE),
            entry("skeleton", "boneyard_sigil", "Boneyard Sigil", DungeonRarity.EPIC, 114, Material.BONE_BLOCK)
        );

        register(EntityType.WITHER_SKELETON,
            entry("wither_skeleton", "charred_rib", "Charred Rib", DungeonRarity.UNCOMMON, 20, Material.BONE),
            entry("wither_skeleton", "coal_dusted_skull_shard", "Coal-Dusted Skull Shard", DungeonRarity.RARE, 50, Material.COAL),
            entry("wither_skeleton", "blackened_blade_sliver", "Blackened Blade Sliver", DungeonRarity.RARE, 52, Material.NETHERITE_SCRAP),
            entry("wither_skeleton", "withering_dust", "Withering Dust", DungeonRarity.EPIC, 125, Material.GUNPOWDER),
            entry("wither_skeleton", "soulfire_marrow", "Soulfire Marrow", DungeonRarity.LEGENDARY, 300, Material.SOUL_SAND)
        );

        register(EntityType.STRAY,
            entry("stray", "frost_arrow_tip", "Frost-Bitten Arrow Tip", DungeonRarity.COMMON, 7, Material.ARROW),
            entry("stray", "icebound_rib", "Icebound Rib", DungeonRarity.COMMON, 6, Material.BONE),
            entry("stray", "rime_dust", "Rime Dust", DungeonRarity.UNCOMMON, 18, Material.SNOWBALL),
            entry("stray", "frozen_marrow_shard", "Frozen Marrow Shard", DungeonRarity.RARE, 46, Material.BLUE_ICE),
            entry("stray", "glacial_quiver_relic", "Glacial Quiver Relic", DungeonRarity.EPIC, 119, Material.PACKED_ICE)
        );

        register(EntityType.BOGGED,
            entry("bogged", "moss_grown_arrow", "Moss-Grown Arrow", DungeonRarity.COMMON, 7, Material.ARROW),
            entry("bogged", "poison_pod", "Poison Pod", DungeonRarity.COMMON, 6, Material.FERMENTED_SPIDER_EYE),
            entry("bogged", "swamp_rib", "Swamp-Soaked Rib", DungeonRarity.UNCOMMON, 18, Material.BONE),
            entry("bogged", "mildewed_quiver_strap", "Mildewed Quiver Strap", DungeonRarity.RARE, 47, Material.LEATHER),
            entry("bogged", "bogheart_relic", "Bogheart Relic", DungeonRarity.EPIC, 120, Material.MOSS_BLOCK)
        );

        register(EntityType.ENDERMAN,
            entry("enderman", "warped_pupil", "Warped Pupil", DungeonRarity.UNCOMMON, 22, Material.ENDER_PEARL),
            entry("enderman", "void_touched_soil", "Void-Touched Soil", DungeonRarity.COMMON, 8, Material.END_STONE),
            entry("enderman", "teleport_residue", "Teleport Residue", DungeonRarity.RARE, 55, Material.PURPLE_DYE),
            entry("enderman", "shifting_limb_shard", "Shifting Limb Shard", DungeonRarity.EPIC, 130, Material.ENDER_EYE),
            entry("enderman", "endstride_relic", "Endstride Relic", DungeonRarity.LEGENDARY, 310, Material.CHORUS_FRUIT)
        );

        register(EntityType.CREEPER,
            entry("creeper", "unstable_powder", "Unstable Powder", DungeonRarity.COMMON, 6, Material.GUNPOWDER),
            entry("creeper", "scorched_hide_patch", "Scorched Hide Patch", DungeonRarity.COMMON, 5, Material.GREEN_DYE),
            entry("creeper", "fizzling_core", "Fizzling Core", DungeonRarity.UNCOMMON, 19, Material.GUNPOWDER),
            entry("creeper", "detonation_crystal", "Detonation Crystal", DungeonRarity.RARE, 48, Material.QUARTZ),
            entry("creeper", "primed_husk_relic", "Primed Husk Relic", DungeonRarity.EPIC, 122, Material.TNT)
        );

        register(EntityType.BLAZE,
            entry("blaze", "smoldering_rod_sliver", "Smoldering Rod Sliver", DungeonRarity.UNCOMMON, 24, Material.BLAZE_ROD),
            entry("blaze", "ember_dust", "Ember Dust", DungeonRarity.COMMON, 8, Material.BLAZE_POWDER),
            entry("blaze", "furnace_core_fragment", "Furnace Core Fragment", DungeonRarity.RARE, 58, Material.MAGMA_CREAM),
            entry("blaze", "infernal_flare", "Infernal Flare", DungeonRarity.EPIC, 135, Material.FIRE_CHARGE),
            entry("blaze", "blazeheart_relic", "Blazeheart Relic", DungeonRarity.LEGENDARY, 320, Material.BLAZE_POWDER)
        );

        register(EntityType.MAGMA_CUBE,
            entry("magma_cube", "gooey_ember_glob", "Gooey Ember Glob", DungeonRarity.COMMON, 7, Material.MAGMA_CREAM),
            entry("magma_cube", "molten_jelly", "Molten Jelly", DungeonRarity.COMMON, 6, Material.SLIME_BALL),
            entry("magma_cube", "cube_core_shard", "Cube Core Shard", DungeonRarity.UNCOMMON, 20, Material.MAGMA_CREAM),
            entry("magma_cube", "searing_bounce_gel", "Searing Bounce Gel", DungeonRarity.RARE, 50, Material.SLIME_BALL),
            entry("magma_cube", "infernal_ooze_relic", "Infernal Ooze Relic", DungeonRarity.EPIC, 128, Material.MAGMA_BLOCK)
        );

        register(EntityType.SLIME,
            entry("slime", "gel_droplet", "Gel Droplet", DungeonRarity.COMMON, 5, Material.SLIME_BALL),
            entry("slime", "bouncy_membrane", "Bouncy Membrane", DungeonRarity.COMMON, 5, Material.SLIME_BALL),
            entry("slime", "condensed_ooze", "Condensed Ooze", DungeonRarity.UNCOMMON, 14, Material.SLIME_BALL),
            entry("slime", "elastic_core", "Elastic Core", DungeonRarity.RARE, 38, Material.SLIME_BALL),
            entry("slime", "gelform_relic", "Gelform Relic", DungeonRarity.EPIC, 100, Material.SLIME_BLOCK)
        );

        register(EntityType.IRON_GOLEM,
            entry("iron_golem", "rusted_plating", "Rusted Plating", DungeonRarity.UNCOMMON, 21, Material.IRON_NUGGET),
            entry("iron_golem", "cracked_core_bolt", "Cracked Core Bolt", DungeonRarity.RARE, 52, Material.IRON_INGOT),
            entry("iron_golem", "poppy_dust", "Ground Poppy Dust", DungeonRarity.COMMON, 8, Material.POPPY),
            entry("iron_golem", "forged_knuckle", "Forged Knuckle", DungeonRarity.EPIC, 132, Material.IRON_BLOCK),
            entry("iron_golem", "guardian_heart_relic", "Guardian Heart Relic", DungeonRarity.LEGENDARY, 315, Material.IRON_BLOCK)
        );

        register(EntityType.VEX,
            entry("vex", "flickering_wing_dust", "Flickering Wing Dust", DungeonRarity.UNCOMMON, 23, Material.PHANTOM_MEMBRANE),
            entry("vex", "spectral_blade_sliver", "Spectral Blade Sliver", DungeonRarity.RARE, 54, Material.IRON_SWORD),
            entry("vex", "phase_residue", "Phase Residue", DungeonRarity.COMMON, 9, Material.GRAY_DYE),
            entry("vex", "harrier_core", "Harrier Core", DungeonRarity.EPIC, 133, Material.PRISMARINE_CRYSTALS),
            entry("vex", "vexbound_relic", "Vexbound Relic", DungeonRarity.LEGENDARY, 318, Material.NETHER_STAR)
        );

        register(EntityType.EVOKER,
            entry("evoker", "torn_grimoire_page", "Torn Grimoire Page", DungeonRarity.RARE, 60, Material.PAPER),
            entry("evoker", "fang_shard", "Fang Shard", DungeonRarity.UNCOMMON, 25, Material.FLINT),
            entry("evoker", "arcane_thread", "Arcane Thread", DungeonRarity.COMMON, 9, Material.STRING),
            entry("evoker", "conjurer_totem_fragment", "Conjurer's Totem Fragment", DungeonRarity.EPIC, 140, Material.TOTEM_OF_UNDYING),
            entry("evoker", "evocation_heart", "Evocation Heart", DungeonRarity.LEGENDARY, 330, Material.EMERALD)
        );
    }

    private void register(EntityType type, DungeonDropDefinition... defs) {
        List<DungeonDropDefinition> list = List.of(defs);
        byType.put(type, list);
        all.addAll(list);
    }

    private static DungeonDropDefinition entry(String typeSlug, String slug, String displayName,
                                                DungeonRarity rarity, int sellPrice, Material icon) {
        String id = "mobdrop_" + typeSlug + "_" + slug;
        return new DungeonDropDefinition(id, rarity.getColoredName() + " §f" + displayName, rarity, sellPrice, icon);
    }

    /** The 5-entry drop pool for a given mob type, or an empty list if that type has no mob-specific table. */
    public List<DungeonDropDefinition> forType(EntityType type) {
        return byType.getOrDefault(type, List.of());
    }

    public boolean hasType(EntityType type) {
        return byType.containsKey(type);
    }

    /** All 100 mob-specific entries across every registered type, for lookups by id (e.g. guild merchant sell). */
    public List<DungeonDropDefinition> all() {
        return all;
    }

    public int size() {
        return all.size();
    }
}
