package com.skyblock.dungeon.combat;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Catalog of named boss identities for every vanilla mob type that
 * appears in any FloorTheme's mob pool (see FloorThemeRegistry). This
 * is what makes a floor boss read as "the Undead Lord" with real
 * telegraphed abilities instead of "buffed vanilla mob with a tier
 * color and a level number" - every ordinary floor's boss (not just
 * milestone floors) now gets one of these identities, matched to
 * whichever base entity type DungeonBossRoomTrigger would otherwise
 * have picked from the floor's pool.
 *
 * Some entries share an abilityId - e.g. every skeleton-family variant
 * uses "arrow_volley" - because the *feel* of the attack pattern is
 * meant to be shared across that family even though the name/flavor
 * differs. GenericBossBehavior owns the actual ability implementations
 * and dispatches purely on abilityId string, so adding a new named
 * boss here never requires touching that class unless it's introducing
 * a genuinely new ability.
 */
public final class BossArchetypeRegistry {

    private final Map<EntityType, List<BossArchetype>> byType = new EnumMap<>(EntityType.class);
    private final Random random;

    public BossArchetypeRegistry(Random random) {
        this.random = random;
        register(EntityType.ZOMBIE, "§2§lThe Undead Lord", "slam_and_summon");
        register(EntityType.HUSK, "§6§lThe Sand Reaper", "hunger_pulse");
        register(EntityType.DROWNED, "§b§lThe Deep Tide", "riptide_pulse");
        register(EntityType.ZOMBIE_VILLAGER, "§5§lThe Plague Doctor", "plague_pulse");
        register(EntityType.SKELETON, "§f§lThe Bone Marshal", "arrow_volley");
        register(EntityType.WITHER_SKELETON, "§8§lThe Wither Herald", "wither_volley");
        register(EntityType.STRAY, "§b§lThe Frost Marshal", "arrow_volley");
        register(EntityType.BOGGED, "§2§lThe Bog Warden", "arrow_volley");
        register(EntityType.SPIDER, "§4§lThe Broodmother", "web_and_summon");
        register(EntityType.CAVE_SPIDER, "§2§lThe Venomqueen", "web_and_summon");
        register(EntityType.SILVERFISH, "§7§lThe Swarmqueen", "swarm_summon");
        register(EntityType.CREEPER, "§a§lThe Detonator", "blast_pulse");
        register(EntityType.ENDERMAN, "§5§lThe Voidwalker", "blink_strike");
        register(EntityType.BLAZE, "§6§lThe Cinder King", "fire_volley");
        register(EntityType.MAGMA_CUBE, "§c§lThe Ooze Colossus", "split_and_slam");
        register(EntityType.SLIME, "§a§lThe Gelatinous Horror", "split_and_slam");
        register(EntityType.IRON_GOLEM, "§7§lThe Iron Warden", "knockback_slam");
        register(EntityType.VEX, "§d§lThe Wraith", "vex_swarm");
        register(EntityType.EVOKER, "§4§lThe Warlock", "fang_line");
        register(EntityType.BAT, "§8§lThe Night Terror", "dash_and_blind");
    }

    private void register(EntityType type, String name, String abilityId) {
        byType.computeIfAbsent(type, t -> new ArrayList<>()).add(new BossArchetype(type, name, abilityId));
    }

    /**
     * Picks an archetype for the given base entity type, or a generic
     * fallback (keeps the mob's own vanilla name, generic slam ability)
     * if this type has no registered identity - keeps every boss spawn
     * safe even for mob types added to a theme's pool later without a
     * matching registry entry yet.
     */
    public BossArchetype get(EntityType baseType) {
        List<BossArchetype> options = byType.get(baseType);
        if (options == null || options.isEmpty()) {
            return new BossArchetype(baseType, "§c§lThe " + prettyName(baseType), "generic_slam");
        }
        return options.get(random.nextInt(options.size()));
    }

    private String prettyName(EntityType type) {
        String[] parts = type.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
