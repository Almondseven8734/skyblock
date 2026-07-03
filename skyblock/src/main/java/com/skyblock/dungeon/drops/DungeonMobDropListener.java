package com.skyblock.dungeon.drops;

import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.loot.DungeonRarity;
import com.skyblock.dungeon.loot.DungeonRarityRoller;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Rolls a custom sellable drop (see DungeonDropRegistry) on dungeon
 * mob kills, dropped at the corpse's feet same as any vanilla drop.
 * Ambient mobs have a flat chance to drop nothing at all; bosses
 * always drop a small handful.
 */
public final class DungeonMobDropListener implements Listener {

    private static final double AMBIENT_DROP_CHANCE = 0.35;
    private static final int BOSS_DROP_COUNT = 3;

    private final MobLevelApplicator levelApplicator;
    private final DungeonDropRegistry dropRegistry;
    private final DungeonDropItemFactory dropItemFactory;
    private final DungeonRarityRoller rarityRoller;
    private final FloorBounds floorBounds;
    private final Random random;

    public DungeonMobDropListener(MobLevelApplicator levelApplicator, DungeonDropRegistry dropRegistry,
                                   DungeonDropItemFactory dropItemFactory, DungeonRarityRoller rarityRoller,
                                   FloorBounds floorBounds, Random random) {
        this.levelApplicator = levelApplicator;
        this.dropRegistry = dropRegistry;
        this.dropItemFactory = dropItemFactory;
        this.rarityRoller = rarityRoller;
        this.floorBounds = floorBounds;
        this.random = random;
    }

    @EventHandler
    public void onDungeonMobDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        int mobLevel = levelApplicator.readLevel(victim);
        if (mobLevel < 0) {
            return; // not a dungeon mob
        }
        if (!(victim.getKiller() instanceof Player)) {
            return; // only player kills drop sellable loot
        }

        boolean boss = levelApplicator.isBoss(victim);
        int dropCount = boss ? BOSS_DROP_COUNT : (random.nextDouble() < AMBIENT_DROP_CHANCE ? 1 : 0);
        if (dropCount <= 0) {
            return;
        }

        int floorNumber = floorBounds.floorForY(victim.getLocation().getBlockY());

        for (int i = 0; i < dropCount; i++) {
            DungeonRarity rarity = rarityRoller.roll(floorNumber);
            List<DungeonDropDefinition> pool = dropRegistry.forRarity(rarity);
            if (pool.isEmpty()) continue;

            DungeonDropDefinition definition = pool.get(random.nextInt(pool.size()));
            ItemStack drop = dropItemFactory.build(definition);
            victim.getWorld().dropItemNaturally(victim.getLocation(), drop);
        }
    }
}
