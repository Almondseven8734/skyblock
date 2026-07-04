package com.skyblock.dungeon.combat;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.List;

/**
 * Drives every ordinary (non-milestone-scripted) floor boss's
 * attack pattern, dispatched purely on BossArchetype.getAbilityId().
 * Runs on the same MilestoneBoss phase-threshold framework
 * (75%/40% health) that milestone floors already use, so every boss
 * on every floor now telegraphs and escalates instead of just being a
 * tankier melee mob with a name tag - that generic-mob-with-generic-
 * lore feel is exactly what named archetypes + this class replace.
 *
 * Kept deliberately conservative on world-destructive effects (no
 * TNT/block damage) since dungeon rooms are permanent for the week -
 * every ability here is potion effects, particles, sound, projectiles,
 * or small temporary minion summons.
 */
public final class GenericBossBehavior extends MilestoneBoss {

    private static final List<Double> PHASE_THRESHOLDS = Arrays.asList(0.75, 0.40);
    private static final long COOLDOWN_TICKS_NORMAL = 100L; // 5s
    private static final long COOLDOWN_TICKS_ENRAGE = 55L;  // ~2.75s
    private static final double ABILITY_RADIUS = 8.0;
    /** How many ticks this boss's update() is actually driven at - mirrors DungeonBossRoomTrigger's scheduler cadence. */
    private static final long DRIVE_PERIOD_TICKS = 10L;

    private final BossArchetype archetype;
    private long cooldownTicks = COOLDOWN_TICKS_NORMAL;
    private long ticksSinceLastAbility = 0;
    private boolean enraged = false;

    public GenericBossBehavior(JavaPlugin plugin, LivingEntity entity, int floorNumber, BossArchetype archetype) {
        super(plugin, entity, floorNumber, PHASE_THRESHOLDS);
        this.archetype = archetype;
    }

    @Override
    public void onTick() {
        ticksSinceLastAbility += DRIVE_PERIOD_TICKS;
        if (ticksSinceLastAbility < cooldownTicks) {
            return;
        }
        ticksSinceLastAbility = 0;
        castAbility();
    }

    @Override
    public void onPhaseTransition(double thresholdCrossed) {
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f,
                thresholdCrossed == 0.75 ? 1.3f : 0.8f);
        entity.getWorld().spawnParticle(Particle.CRIT, entity.getLocation().add(0, 1, 0), 40, 1, 1, 1, 0.1);
        if (thresholdCrossed <= 0.40) {
            enraged = true;
            cooldownTicks = COOLDOWN_TICKS_ENRAGE;
        }
    }

    @Override
    public void onDeath() {
        entity.getWorld().spawnParticle(Particle.EXPLOSION, entity.getLocation(), 8, 1.0, 1.0, 1.0, 0.0);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 1.1f);
    }

    private void castAbility() {
        switch (archetype.getAbilityId()) {
            case "slam_and_summon" -> slamAndSummon();
            case "hunger_pulse" -> debuffPulse(PotionEffectType.HUNGER, PotionEffectType.SLOWNESS, Sound.ENTITY_HUSK_AMBIENT);
            case "riptide_pulse" -> debuffPulse(PotionEffectType.MINING_FATIGUE, PotionEffectType.SLOWNESS, Sound.ITEM_TRIDENT_RIPTIDE_1);
            case "plague_pulse" -> debuffPulse(PotionEffectType.NAUSEA, PotionEffectType.POISON, Sound.ENTITY_ZOMBIE_VILLAGER_CURE);
            case "arrow_volley" -> arrowVolley();
            case "wither_volley" -> witherVolley();
            case "web_and_summon" -> webAndSummon();
            case "swarm_summon" -> swarmSummon();
            case "blast_pulse" -> blastPulse();
            case "blink_strike" -> blinkStrike();
            case "fire_volley" -> fireVolley();
            case "split_and_slam" -> splitAndSlam();
            case "knockback_slam" -> knockbackSlam();
            case "vex_swarm" -> vexSwarm();
            case "fang_line" -> fangLine();
            case "dash_and_blind" -> dashAndBlind();
            default -> genericSlam();
        }
    }

    // ─── Shared helpers ──────────────────────────────────────────────────────

    private List<Player> nearbyPlayers() {
        return entity.getNearbyEntities(ABILITY_RADIUS, ABILITY_RADIUS, ABILITY_RADIUS).stream()
                .filter(Player.class::isInstance).map(Player.class::cast).toList();
    }

    private Player nearestPlayer() {
        return nearbyPlayers().stream()
                .min((a, b) -> Double.compare(a.getLocation().distanceSquared(entity.getLocation()),
                        b.getLocation().distanceSquared(entity.getLocation())))
                .orElse(null);
    }

    private void telegraph(Particle particle, Sound sound) {
        Location loc = entity.getLocation().add(0, 1, 0);
        entity.getWorld().spawnParticle(particle, loc, 30, 1.2, 1.0, 1.2, 0.05);
        entity.getWorld().playSound(loc, sound, 1.0f, 1.0f);
    }

    // ─── Ability implementations ─────────────────────────────────────────────

    private void genericSlam() {
        telegraph(Particle.CRIT, Sound.ENTITY_GENERIC_EXPLODE);
        for (Player player : nearbyPlayers()) {
            Vector knockback = player.getLocation().toVector().subtract(entity.getLocation().toVector())
                    .normalize().multiply(1.4).setY(0.4);
            player.setVelocity(knockback);
        }
    }

    private void slamAndSummon() {
        genericSlam();
        if (enraged) {
            summonMinions(EntityType.ZOMBIE, 2);
        }
    }

    private void debuffPulse(PotionEffectType primary, PotionEffectType secondary, Sound sound) {
        telegraph(Particle.SNEEZE, sound);
        for (Player player : nearbyPlayers()) {
            player.addPotionEffect(new PotionEffect(primary, 100, 1));
            player.addPotionEffect(new PotionEffect(secondary, 100, 1));
        }
    }

    private void arrowVolley() {
        Player target = nearestPlayer();
        if (target == null) return;
        telegraph(Particle.CRIT, Sound.ENTITY_SKELETON_SHOOT);
        for (int i = -1; i <= 1; i++) {
            Location spawnAt = entity.getEyeLocation();
            Vector direction = target.getLocation().add(i * 1.5, 0, i * 1.5)
                    .subtract(spawnAt).toVector().normalize();
            Arrow arrow = entity.getWorld().spawnArrow(spawnAt, direction, 2.2f, 4.0f);
            arrow.setShooter(entity);
        }
    }

    private void witherVolley() {
        arrowVolley();
        for (Player player : nearbyPlayers()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
        }
    }

    private void webAndSummon() {
        telegraph(Particle.ITEM_SLIME, Sound.ENTITY_SPIDER_AMBIENT);
        for (Player player : nearbyPlayers()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2));
        }
        if (enraged) {
            summonMinions(EntityType.CAVE_SPIDER, 2);
        }
    }

    private void swarmSummon() {
        telegraph(Particle.CLOUD, Sound.ENTITY_SILVERFISH_AMBIENT);
        summonMinions(EntityType.SILVERFISH, enraged ? 5 : 3);
    }

    private void blastPulse() {
        telegraph(Particle.EXPLOSION, Sound.ENTITY_CREEPER_PRIMED);
        for (Player player : nearbyPlayers()) {
            player.damage(enraged ? 6.0 : 3.0, entity);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
        }
    }

    private void blinkStrike() {
        Player target = nearestPlayer();
        if (target == null) return;
        Location dest = target.getLocation().clone().add(
                target.getLocation().getDirection().multiply(-1.5));
        entity.getWorld().spawnParticle(Particle.PORTAL, entity.getLocation(), 40, 0.5, 1, 0.5, 0.3);
        entity.teleport(dest);
        entity.getWorld().spawnParticle(Particle.PORTAL, dest, 40, 0.5, 1, 0.5, 0.3);
        entity.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
    }

    private void fireVolley() {
        Player target = nearestPlayer();
        if (target == null) return;
        telegraph(Particle.FLAME, Sound.ENTITY_BLAZE_SHOOT);
        Vector direction = target.getEyeLocation().subtract(entity.getEyeLocation()).toVector().normalize();
        if (entity.getWorld().spawn(entity.getEyeLocation(), SmallFireball.class) instanceof SmallFireball fireball) {
            fireball.setShooter(entity);
            fireball.setDirection(direction);
        }
    }

    private void splitAndSlam() {
        genericSlam();
        EntityType minionType = entity.getType() == EntityType.SLIME ? EntityType.SLIME : EntityType.MAGMA_CUBE;
        if (enraged) {
            summonMinions(minionType, 2);
        }
    }

    private void knockbackSlam() {
        telegraph(Particle.BLOCK, Sound.ENTITY_IRON_GOLEM_ATTACK);
        for (Player player : nearbyPlayers()) {
            Vector knockback = player.getLocation().toVector().subtract(entity.getLocation().toVector())
                    .normalize().multiply(2.0).setY(0.8);
            player.setVelocity(knockback);
            player.damage(enraged ? 5.0 : 2.5, entity);
        }
    }

    private void vexSwarm() {
        telegraph(Particle.WITCH, Sound.ENTITY_VEX_CHARGE);
        summonMinions(EntityType.VEX, enraged ? 4 : 2);
    }

    private void fangLine() {
        Player target = nearestPlayer();
        if (target == null) return;
        telegraph(Particle.SMOKE, Sound.ENTITY_EVOKER_CAST_SPELL);
        Vector direction = target.getLocation().toVector().subtract(entity.getLocation().toVector()).normalize();
        Location cursor = entity.getLocation().clone();
        for (int i = 1; i <= 5; i++) {
            cursor.add(direction);
            entity.getWorld().spawn(cursor, EvokerFangs.class);
        }
    }

    private void dashAndBlind() {
        Player target = nearestPlayer();
        if (target == null) return;
        telegraph(Particle.CLOUD, Sound.ENTITY_BAT_TAKEOFF);
        Vector dash = target.getLocation().toVector().subtract(entity.getLocation().toVector())
                .normalize().multiply(1.8).setY(0.3);
        entity.setVelocity(dash);
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
    }

    /** Spawns a handful of unleveled, weak minions near the boss - flavor adds, not meant to be tough on their own. */
    private void summonMinions(EntityType type, int count) {
        for (int i = 0; i < count; i++) {
            double angle = (2 * Math.PI / count) * i;
            Location spawnAt = entity.getLocation().clone().add(Math.cos(angle) * 2.0, 0, Math.sin(angle) * 2.0);
            Entity spawned = entity.getWorld().spawnEntity(spawnAt, type);
            if (spawned instanceof Zombie zombie) {
                zombie.setBaby(false);
            }
        }
    }
}
