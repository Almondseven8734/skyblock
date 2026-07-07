package com.skyblock.dungeon.drops;

import com.skyblock.dungeon.combat.DungeonBossDropDefinition;
import com.skyblock.dungeon.combat.DungeonBossDropItemFactory;
import com.skyblock.dungeon.combat.DungeonBossDropRegistry;
import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.items.DungeonItemGenerator;
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
 * Rolls a custom sellable drop (see DungeonDropRegistry) AND a chance
 * at real equippable gear (via DungeonItemGenerator, the same
 * generator DungeonChestRoomPlacer uses for chest loot) on dungeon mob
 * kills, dropped at the corpse's feet same as any vanilla drop.
 * Ambient mobs have a flat chance to drop nothing at all; bosses
 * always drop a small handful of sellables plus a guaranteed piece of
 * gear.
 *
 * Previously this class only ever rolled DungeonDropRegistry entries -
 * flavor/currency items with no attribute modifiers, explicitly "not
 * equippable gear" per that class's own doc comment. There was no code
 * path anywhere that gave a dungeon mob kill a chance at a real
 * weapon/armor drop - chests were the only source of gear. That's the
 * root cause of "no observed playtest of mobs dropping gear": there
 * was nothing to observe, the feature didn't exist yet.
 */
public final class DungeonMobDropListener implements Listener {

    private static final double AMBIENT_DROP_CHANCE = 0.35;
    private static final int BOSS_DROP_COUNT = 3;

    /** Chance an ambient mob kill additionally rolls a gear drop, independent of the sellable-drop roll. */
    private static final double AMBIENT_GEAR_DROP_CHANCE = 0.12;
    /** How many guaranteed gear pieces a boss kill drops. */
    private static final int BOSS_GEAR_DROP_COUNT = 1;

    private final MobLevelApplicator levelApplicator;
    private final DungeonDropRegistry dropRegistry;
    private final DungeonDropItemFactory dropItemFactory;
    private final DungeonRarityRoller rarityRoller;
    private final DungeonItemGenerator itemGenerator;
    private final FloorBounds floorBounds;
    private final Random random;
    private final DungeonBossDropRegistry bossDropRegistry;
    private final DungeonBossDropItemFactory bossDropItemFactory;

    public DungeonMobDropListener(MobLevelApplicator levelApplicator, DungeonDropRegistry dropRegistry,
                                   DungeonDropItemFactory dropItemFactory, DungeonRarityRoller rarityRoller,
                                   DungeonItemGenerator itemGenerator, FloorBounds floorBounds, Random random,
                                   DungeonBossDropRegistry bossDropRegistry,
                                   DungeonBossDropItemFactory bossDropItemFactory) {
        this.levelApplicator = levelApplicator;
        this.dropRegistry = dropRegistry;
        this.dropItemFactory = dropItemFactory;
        this.rarityRoller = rarityRoller;
        this.itemGenerator = itemGenerator;
        this.floorBounds = floorBounds;
        this.random = random;
        this.bossDropRegistry = bossDropRegistry;
        this.bossDropItemFactory = bossDropItemFactory;
    }

    @EventHandler
    public void onDungeonMobDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        int mobLevel = levelApplicator.readLevel(victim);
        if (mobLevel < 0) {
            return; // not a dungeon mob
        }

        // Dungeon mobs never drop vanilla loot (rotten flesh, string, bones,
        // etc.) - only the custom sellable/gear tables below - regardless
        // of what killed them or whether this death rolls custom loot at all.
        event.getDrops().clear();
        event.setDroppedExp(0);

        if (!(victim.getKiller() instanceof Player)) {
            return; // only player kills drop sellable loot
        }

        boolean boss = levelApplicator.isBoss(victim);
        int dropCount = boss ? BOSS_DROP_COUNT : (random.nextDouble() < AMBIENT_DROP_CHANCE ? 1 : 0);
        int gearDropCount = boss ? BOSS_GEAR_DROP_COUNT
                : (random.nextDouble() < AMBIENT_GEAR_DROP_CHANCE ? 1 : 0);
        if (dropCount <= 0 && gearDropCount <= 0) {
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

        // Gear drops (real equippable weapons/armor via the same
        // DungeonItemGenerator DungeonChestRoomPlacer uses for chest
        // loot) - rolled independently of the sellable drops above.
        // Bosses always drop BOSS_GEAR_DROP_COUNT pieces; ambient mobs
        // have a flat independent chance per kill. gearDropCount was
        // already rolled above (before the early-return) specifically
        // so a miss on the sellable-drop roll can never suppress an
        // otherwise-successful gear roll or vice versa - the two are
        // fully independent chances, not one gating the other.
        for (int i = 0; i < gearDropCount; i++) {
            DungeonRarity gearRarity = rarityRoller.roll(floorNumber);
            ItemStack gear = (random.nextDouble() < 0.5)
                    ? itemGenerator.generateRandomWeapon(gearRarity)
                    : itemGenerator.generateRandomArmor(gearRarity);
            victim.getWorld().dropItemNaturally(victim.getLocation(), gear);
        }

        // Every boss drops its own unique, flavor-matched trophy item on
        // top of the gear/sellable rolls above - guaranteed, not chance-
        // based, since it's meant to be a "you definitely beat this boss"
        // keepsake rather than another loot roll. Ambient (non-boss) mobs
        // never drop a trophy.
        if (boss) {
            DungeonBossDropDefinition trophyDef = bossDropRegistry.get(victim.getType());
            ItemStack trophy = bossDropItemFactory.build(trophyDef);
            victim.getWorld().dropItemNaturally(victim.getLocation(), trophy);
        }
    }
}
