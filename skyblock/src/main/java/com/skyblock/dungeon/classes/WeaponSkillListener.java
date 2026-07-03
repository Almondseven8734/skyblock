package com.skyblock.dungeon.classes;

import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Triggers a player's class skills. Right-click while holding a
 * weapon your class is allowed to use (see PlayerClassType) fires
 * that class's next-available off-cooldown skill in registry order -
 * simple "one active button" model rather than a hotbar of skill
 * slots, since there's no UI framework for skill-slot binding yet.
 *
 * Per design only weapons trigger skills, and only the weapon family
 * that matches the player's chosen class - a Bowman holding a sword
 * gets nothing from right-click, same as a Swordsman holding a bow.
 */
public final class WeaponSkillListener implements Listener {

    private static final double WHIRLWIND_BASE_RADIUS = 3.0;
    private static final double WHIRLWIND_RADIUS_PER_RANK = 0.3;
    private static final double WHIRLWIND_BASE_DAMAGE = 4.0;
    private static final double WHIRLWIND_DAMAGE_PER_RANK = 1.5;
    private static final double WHIRLWIND_KNOCKBACK = 0.6;

    private static final double SECOND_WIND_BASE_HEAL = 4.0;
    private static final double SECOND_WIND_HEAL_PER_RANK = 3.0;
    private static final long SECOND_WIND_RESIST_TICKS_BASE = 60L;
    private static final long SECOND_WIND_RESIST_TICKS_PER_RANK = 40L;

    private static final double DASH_BASE_SPEED = 0.9;
    private static final double DASH_SPEED_PER_RANK = 0.15;
    private static final long DASH_SPEED_EFFECT_TICKS = 40L;

    private static final long EVASIVE_ROLL_DURATION_TICKS_BASE = 30L;
    private static final long EVASIVE_ROLL_DURATION_TICKS_PER_RANK = 15L;

    private static final int VOLLEY_BASE_ARROWS = 3;
    private static final double VOLLEY_SPREAD_DEGREES = 12.0;
    private static final double VOLLEY_SPEED = 2.6;

    private static final double POWER_SHOT_BASE_BONUS_DAMAGE = 3.0;
    private static final double POWER_SHOT_BONUS_PER_RANK = 2.0;
    private static final double POWER_SHOT_BLAST_RADIUS = 3.0;

    private final JavaPlugin plugin;
    private final PlayerClassStorage classStorage;
    private final ClassSkillRegistry skillRegistry;
    private final PlayerProgressionStorage progressionStorage;
    private final NamespacedKey powerShotKey;

    /** Player UUID -> skill id -> ms timestamp the skill is next usable at. */
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    /** Players whose next bow shot should be upgraded by Power Shot, mapped to the rank it was triggered at. */
    private final Map<UUID, Integer> pendingPowerShot = new ConcurrentHashMap<>();

    public WeaponSkillListener(JavaPlugin plugin, PlayerClassStorage classStorage,
                                ClassSkillRegistry skillRegistry, PlayerProgressionStorage progressionStorage) {
        this.plugin = plugin;
        this.classStorage = classStorage;
        this.skillRegistry = skillRegistry;
        this.progressionStorage = progressionStorage;
        this.powerShotKey = new NamespacedKey(plugin, "dungeon_power_shot_bonus");
    }

    // ─── Right-click trigger ─────────────────────────────────────────────────

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (held == null) return;

        PlayerClassState classState = classStorage.get(player.getUniqueId());
        PlayerClassType classType = classState.getClassType();
        if (classType == null || !classType.isAllowedWeapon(held.getType())) {
            return; // no class chosen yet, or wrong weapon for this class
        }

        for (ClassSkill skill : skillRegistry.forClass(classType)) {
            int rank = classState.getRank(skill.getId());
            if (rank <= 0) continue; // not learned
            if (!isOffCooldown(player.getUniqueId(), skill)) continue;

            triggerSkill(player, skill, rank);
            startCooldown(player.getUniqueId(), skill, rank);
            return; // one skill per click, first learned+ready match wins
        }
    }

    private boolean isOffCooldown(UUID playerId, ClassSkill skill) {
        Long readyAt = cooldowns.getOrDefault(playerId, Map.of()).get(skill.getId());
        return readyAt == null || System.currentTimeMillis() >= readyAt;
    }

    private void startCooldown(UUID playerId, ClassSkill skill, int rank) {
        long readyAt = System.currentTimeMillis() + skill.cooldownTicksAtRank(rank) * 50L;
        cooldowns.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>()).put(skill.getId(), readyAt);
    }

    // ─── Skill effects ────────────────────────────────────────────────────────

    private void triggerSkill(Player player, ClassSkill skill, int rank) {
        switch (skill.getId()) {
            case ClassSkillRegistry.SWORDSMAN_WHIRLWIND -> whirlwindStrike(player, rank);
            case ClassSkillRegistry.SWORDSMAN_SECOND_WIND -> secondWind(player, rank);
            case ClassSkillRegistry.SCOUT_SPRINT_DASH -> sprintDash(player, rank);
            case ClassSkillRegistry.SCOUT_EVASIVE_ROLL -> evasiveRoll(player, rank);
            case ClassSkillRegistry.BOWMAN_VOLLEY -> volley(player, rank);
            case ClassSkillRegistry.BOWMAN_POWER_SHOT -> armPowerShot(player, rank);
            default -> { /* skill registered but no effect wired yet - silently no-op */ }
        }
    }

    private void whirlwindStrike(Player player, int rank) {
        double radius = WHIRLWIND_BASE_RADIUS + rank * WHIRLWIND_RADIUS_PER_RANK;
        double damage = WHIRLWIND_BASE_DAMAGE + rank * WHIRLWIND_DAMAGE_PER_RANK;
        Location center = player.getLocation();

        for (Entity nearby : player.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity target) || nearby instanceof Player || nearby == player) continue;
            target.damage(damage, player);
            Vector knockback = target.getLocation().toVector().subtract(center.toVector())
                .setY(0.15).normalize().multiply(WHIRLWIND_KNOCKBACK);
            target.setVelocity(target.getVelocity().add(knockback));
        }
        player.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f);
    }

    private void secondWind(Player player, int rank) {
        double heal = SECOND_WIND_BASE_HEAL + rank * SECOND_WIND_HEAL_PER_RANK;
        var maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
        player.setHealth(Math.min(maxHealth, player.getHealth() + heal));

        long duration = SECOND_WIND_RESIST_TICKS_BASE + rank * SECOND_WIND_RESIST_TICKS_PER_RANK;
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) duration, 0, false, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    private void sprintDash(Player player, int rank) {
        double speed = DASH_BASE_SPEED + rank * DASH_SPEED_PER_RANK;
        Vector direction = player.getLocation().getDirection().setY(0.25).normalize().multiply(speed);
        player.setVelocity(player.getVelocity().add(direction));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) DASH_SPEED_EFFECT_TICKS,
            Math.min(rank, 3), false, true));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.6f);
    }

    private void evasiveRoll(Player player, int rank) {
        long duration = EVASIVE_ROLL_DURATION_TICKS_BASE + rank * EVASIVE_ROLL_DURATION_TICKS_PER_RANK;
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, (int) duration, 1, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) duration, 2, false, true));
        Vector direction = player.getLocation().getDirection().setY(0.2).normalize().multiply(0.6);
        player.setVelocity(player.getVelocity().add(direction));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1.0f, 1.5f);
    }

    private void volley(Player player, int rank) {
        int arrowCount = VOLLEY_BASE_ARROWS + rank;
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection();

        double startAngle = -VOLLEY_SPREAD_DEGREES * (arrowCount - 1) / 2.0;
        for (int i = 0; i < arrowCount; i++) {
            double angleDeg = startAngle + i * VOLLEY_SPREAD_DEGREES;
            Vector shot = rotateAroundY(forward, Math.toRadians(angleDeg)).normalize().multiply(VOLLEY_SPEED);

            Arrow arrow = player.getWorld().spawn(eye, Arrow.class);
            arrow.setVelocity(shot);
            arrow.setShooter(player);
        }
        player.getWorld().playSound(eye, Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.2f);
    }

    private Vector rotateAroundY(Vector v, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = v.getX() * cos + v.getZ() * sin;
        double z = -v.getX() * sin + v.getZ() * cos;
        return new Vector(x, v.getY(), z);
    }

    private void armPowerShot(Player player, int rank) {
        pendingPowerShot.put(player.getUniqueId(), rank);
        player.sendMessage("§eYour next shot is empowered.");
    }

    // ─── Power Shot: tag the fired arrow, resolve the blast on impact ────────

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Integer rank = pendingPowerShot.remove(player.getUniqueId());
        if (rank == null) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        arrow.getPersistentDataContainer().set(powerShotKey, PersistentDataType.INTEGER, rank);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        Integer rank = arrow.getPersistentDataContainer().get(powerShotKey, PersistentDataType.INTEGER);
        if (rank == null) return;

        double bonusDamage = POWER_SHOT_BASE_BONUS_DAMAGE + rank * POWER_SHOT_BONUS_PER_RANK;
        Location impact = arrow.getLocation();
        Entity shooter = arrow.getShooter() instanceof Entity e ? e : null;

        for (Entity nearby : arrow.getWorld().getNearbyEntities(impact, POWER_SHOT_BLAST_RADIUS,
                POWER_SHOT_BLAST_RADIUS, POWER_SHOT_BLAST_RADIUS)) {
            if (!(nearby instanceof LivingEntity target) || nearby == shooter) continue;
            if (shooter instanceof LivingEntity livingShooter) {
                target.damage(bonusDamage, livingShooter);
            } else {
                target.damage(bonusDamage);
            }
        }
        arrow.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.3f);
    }
}
