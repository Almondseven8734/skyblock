package com.skyblock.dungeon.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.util.EnumSet;
import java.util.Set;

/**
 * Takes a freshly spawned mob and a rolled level (see MobLevelRoller)
 * and turns that single number into everything the level is supposed
 * to drive: MAX_HEALTH/ATTACK_DAMAGE/MOVEMENT_SPEED scaling, tier
 * potion buffs/debuffs, a name-tag showing the tier and level, a
 * PersistentDataContainer tag other systems (loot, XP) can read back
 * off the corpse, and a starter gear set for mob types that actually
 * render visible equipment.
 *
 * Health/damage/speed scale continuously with the raw level (not just
 * per-tier) so two mobs in the same tier still feel meaningfully
 * different at level 61 vs level 79, while the tier only gates the
 * discrete buff/debuff potion effects and the gear material.
 */
public final class MobLevelApplicator {

    /** Multiplier added per level above 1, on top of the tier's own baseline. */
    private static final double HEALTH_SCALE_PER_LEVEL = 0.06;
    private static final double DAMAGE_SCALE_PER_LEVEL = 0.035;
    private static final double SPEED_SCALE_PER_LEVEL = 0.003;
    private static final double MAX_SPEED_MULTIPLIER = 1.75;

    /** Mob types that render visible humanoid equipment slots - only these get gear on spawn. */
    private static final Set<EntityType> GEARABLE_TYPES = EnumSet.of(
        EntityType.ZOMBIE, EntityType.HUSK, EntityType.DROWNED, EntityType.ZOMBIE_VILLAGER,
        EntityType.SKELETON, EntityType.WITHER_SKELETON, EntityType.STRAY, EntityType.BOGGED,
        EntityType.PIGLIN, EntityType.PIGLIN_BRUTE
    );

    private final NamespacedKey levelKey;
    private final NamespacedKey bossKey;

    public MobLevelApplicator(JavaPlugin plugin) {
        this.levelKey = new NamespacedKey(plugin, "dungeon_mob_level");
        this.bossKey = new NamespacedKey(plugin, "dungeon_mob_is_boss");
    }

    /** Applies the full level treatment to an ambient (non-boss) mob. */
    public void applyLevel(LivingEntity entity, int level) {
        applyLevel(entity, level, 1.0);
    }

    /** Applies the full level treatment with an extra multiplier, used for boss-tier entities. */
    public void applyLevel(LivingEntity entity, int level, double extraMultiplier) {
        int clamped = Math.max(MobLevelRoller.MIN_LEVEL, Math.min(MobLevelRoller.MAX_LEVEL, level));
        MobLevelTier tier = MobLevelTier.forLevel(clamped);

        tagLevel(entity, clamped);
        scaleStats(entity, clamped, extraMultiplier);
        applyTierEffects(entity, tier);
        equipGear(entity, tier);
        nameEntity(entity, tier, clamped);
    }

    /** Reads the level previously tagged by applyLevel(), or -1 if this entity was never leveled. */
    public int readLevel(LivingEntity entity) {
        Integer value = entity.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return value != null ? value : -1;
    }

    /**
     * Marks an entity as a dungeon boss. Deliberately backed by its own
     * PDC tag on the entity rather than a lookup into
     * DungeonBossGateController/DungeonStaircaseOrchestrator's internal
     * maps - both of those clear their boss-tracking entries as part of
     * handling the same EntityDeathEvent, so any other EntityDeathEvent
     * listener querying them would race on handler registration order.
     * A tag on the entity itself is safe to read from any listener at
     * any priority.
     */
    public void tagBoss(LivingEntity entity) {
        entity.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
    }

    public boolean isBoss(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);
    }

    private void tagLevel(LivingEntity entity, int level) {
        entity.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
    }

    private void scaleStats(LivingEntity entity, int level, double extraMultiplier) {
        double levelFactor = (level - 1); // 0 at level 1

        double healthMultiplier = (1.0 + levelFactor * HEALTH_SCALE_PER_LEVEL) * extraMultiplier;
        double damageMultiplier = (1.0 + levelFactor * DAMAGE_SCALE_PER_LEVEL) * extraMultiplier;
        double speedMultiplier = Math.min(1.0 + levelFactor * SPEED_SCALE_PER_LEVEL, MAX_SPEED_MULTIPLIER);

        scaleAttribute(entity, Attribute.MAX_HEALTH, healthMultiplier);
        scaleAttribute(entity, Attribute.ATTACK_DAMAGE, damageMultiplier);
        scaleAttribute(entity, Attribute.MOVEMENT_SPEED, speedMultiplier);

        var maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            entity.setHealth(maxHealthAttr.getValue());
        }
    }

    private void scaleAttribute(LivingEntity entity, Attribute attribute, double multiplier) {
        var instance = entity.getAttribute(attribute);
        if (instance == null) return; // this entity type doesn't have this attribute, skip silently
        instance.setBaseValue(instance.getBaseValue() * multiplier);
    }

    private void applyTierEffects(LivingEntity entity, MobLevelTier tier) {
        for (PotionEffect effect : tier.getEffects()) {
            entity.addPotionEffect(effect);
        }
    }

    private void equipGear(LivingEntity entity, MobLevelTier tier) {
        if (!GEARABLE_TYPES.contains(entity.getType())) return;

        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;

        equipment.setHelmet(new ItemStack(tier.getHelmet()));
        equipment.setChestplate(new ItemStack(tier.getChestplate()));
        equipment.setLeggings(new ItemStack(tier.getLeggings()));
        equipment.setBoots(new ItemStack(tier.getBoots()));
        equipment.setItemInMainHand(new ItemStack(tier.getWeapon()));

        // Vanilla mobs default to a 8.5% chance of dropping held/worn
        // items on death - zero that out so gear doesn't spam the
        // ground; the real drop table (piece 5, mob drops) owns loot.
        equipment.setHelmetDropChance(0f);
        equipment.setChestplateDropChance(0f);
        equipment.setLeggingsDropChance(0f);
        equipment.setBootsDropChance(0f);
        equipment.setItemInMainHandDropChance(0f);
    }

    private void nameEntity(LivingEntity entity, MobLevelTier tier, int level) {
        String baseName = prettyEntityName(entity.getType());
        entity.setCustomName(tier.getDisplayName() + " §f" + baseName + " §7[Lv. " + level + "]");
        entity.setCustomNameVisible(true);
    }

    private String prettyEntityName(EntityType type) {
        String[] parts = type.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
