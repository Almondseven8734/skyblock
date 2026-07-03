package com.skyblock.dungeon.progression;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Replaces vanilla mob-kill XP orbs with the dungeon's own character
 * XP system: any mob carrying a MobLevelApplicator level tag (i.e.
 * anything spawned by DungeonRoomMobSpawner or DungeonBossRoomTrigger)
 * drops zero vanilla XP, and its killer's PlayerProgressionState gets
 * XP instead, scaled off the mob's rolled level and boss status.
 *
 * Mobs with no level tag (anything outside the dungeon) are completely
 * untouched by this listener - vanilla XP orbs behave normally
 * everywhere else on the server.
 */
public final class DungeonXpListener implements Listener {

    /** Flat XP floor so even a level-1 mob is worth killing. */
    private static final long BASE_XP = 8L;
    /** XP added per mob level on top of the base. */
    private static final long XP_PER_LEVEL = 5L;
    /** Multiplier applied to the whole roll for boss kills. */
    private static final double BOSS_XP_MULTIPLIER = 4.0;

    private final MobLevelApplicator levelApplicator;
    private final PlayerProgressionStorage storage;

    public DungeonXpListener(MobLevelApplicator levelApplicator, PlayerProgressionStorage storage) {
        this.levelApplicator = levelApplicator;
        this.storage = storage;
    }

    @EventHandler
    public void onDungeonMobDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        int mobLevel = levelApplicator.readLevel(victim);
        if (mobLevel < 0) {
            return; // not a dungeon mob - leave vanilla XP orb behavior untouched
        }

        // Dungeon mobs never drop vanilla orbs, killed by a player or not
        // (environmental deaths - lava, fall, gate seal, etc. - shouldn't
        // litter the floor with orbs either).
        event.setDroppedExp(0);

        Player killer = victim.getKiller();
        if (killer == null) {
            return; // died to something other than a player - no one to credit XP to
        }

        boolean boss = levelApplicator.isBoss(victim);
        long xpAwarded = computeXp(mobLevel, boss);

        PlayerProgressionState state = storage.get(killer.getUniqueId());
        int previousUnspentPoints = state.getUnspentSkillPoints();
        int levelsGained = state.addXp(xpAwarded);
        storage.persist(killer.getUniqueId(), state);

        updateXpBar(killer, state);

        if (levelsGained > 0) {
            int pointsGained = state.getUnspentSkillPoints() - previousUnspentPoints;
            announceLevelUp(killer, state, pointsGained);
        }
    }

    private long computeXp(int mobLevel, boolean boss) {
        long xp = BASE_XP + XP_PER_LEVEL * mobLevel;
        if (boss) {
            xp = Math.round(xp * BOSS_XP_MULTIPLIER);
        }
        return xp;
    }

    /**
     * Repurposes the vanilla XP bar (level number + green progress bar)
     * as a visual for character level/progress while inside the
     * dungeon. This only ever runs off a dungeon-mob kill, so it never
     * touches a player's bar from overworld mining/mob XP - the moment
     * they gain real vanilla XP anywhere else, that naturally overwrites
     * this display again, which is fine since this system doesn't read
     * the vanilla bar back, only writes to it.
     */
    private void updateXpBar(Player player, PlayerProgressionState state) {
        player.setLevel(state.getCharacterLevel());
        player.setExp(state.progressFraction());
    }

    private void announceLevelUp(Player player, PlayerProgressionState state, int pointsGained) {
        player.sendMessage("§6§lLEVEL UP! §eYou are now level §6" + state.getCharacterLevel() + "§e.");
        if (pointsGained > 0) {
            player.sendMessage("§7+" + pointsGained + " skill point" + (pointsGained == 1 ? "" : "s")
                + " §7(§f" + state.getUnspentSkillPoints() + " unspent§7)");
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
    }
}
