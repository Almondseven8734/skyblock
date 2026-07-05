package com.skyblock;

import com.skyblock.admin.AdminSystem;
import com.skyblock.commands.AhCommand;
import com.skyblock.ah.AhStorage;
import com.skyblock.ah.AhSystem;
import com.skyblock.commands.FlyCommand;
import com.skyblock.commands.SpawnCommand;
import com.skyblock.commands.TeleportCommands;
import com.skyblock.commands.CrateCommand;
import com.skyblock.crates.CrateSystem;
import com.skyblock.dev.TestForms;
import com.skyblock.commands.BalCommand;
import com.skyblock.commands.PayCommand;
import com.skyblock.fishing.FishingSystemV4;
import com.skyblock.island.InviteSystem;
import com.skyblock.island.IslandCommands;
import com.skyblock.island.IslandGenerator;
import com.skyblock.island.IslandMenu;
import com.skyblock.island.IslandPortal;
import com.skyblock.island.IslandProtection;
import com.skyblock.items.SpecialItems;
import com.skyblock.kills.KillSystem;
import com.skyblock.mine.MineSystem;
import com.skyblock.minigames.MinigamesSystem;
import com.skyblock.protection.SpawnMobControl;
import com.skyblock.protection.SpawnProtection;
import com.skyblock.pwarp.PwarpSystem;
import com.skyblock.shop.AdminShopSystem;
import com.skyblock.shop.GemshopSystem;
import com.skyblock.shop.KillShopSystem;
import com.skyblock.commands.SellCommand;
import com.skyblock.shop.SellTrashSystem;
import com.skyblock.commands.ShopCommand;
import com.skyblock.shop.ShopSystem;
import com.skyblock.commands.TrashCommand;
import com.skyblock.sidebar.SidebarSystem;
import com.skyblock.island.IslandStorage;
import com.skyblock.tpa.TpaSystem;
import com.skyblock.util.GuiBuilder;
import com.skyblock.util.NameValidator;
import com.skyblock.vault.VaultSystem;

import com.skyblock.dungeon.combat.ExampleMilestoneBoss;
import com.skyblock.dungeon.combat.BossArchetypeRegistry;
import com.skyblock.dungeon.combat.DungeonBossGateController;
import com.skyblock.dungeon.combat.MobLevelApplicator;
import com.skyblock.dungeon.combat.MobLevelRoller;
import com.skyblock.dungeon.command.DungeonCommand;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.floor.DungeonFloorManager;
import com.skyblock.dungeon.floor.DungeonHubBuilder;
import com.skyblock.dungeon.floor.DungeonPlayerState;
import com.skyblock.dungeon.floor.DungeonPlayerStateStorage;
import com.skyblock.dungeon.floor.DungeonResetScheduler;
import com.skyblock.dungeon.floor.DungeonStaircaseOrchestrator;
import com.skyblock.dungeon.gen.DungeonCarveScheduler;
import com.skyblock.dungeon.gen.DungeonWorldGenerator;
import com.skyblock.dungeon.listener.DungeonBlockProtectionListener;
import com.skyblock.dungeon.listener.DungeonBossAnchorListener;
import com.skyblock.dungeon.listener.DungeonChestLootListener;
import com.skyblock.dungeon.listener.DungeonCommandLockdownListener;
import com.skyblock.dungeon.listener.DungeonDeathHandler;
import com.skyblock.dungeon.listener.DungeonFrontierListener;
import com.skyblock.dungeon.listener.DungeonJoinQuitListener;
import com.skyblock.dungeon.listener.DungeonMobSuffocationGuard;
import com.skyblock.dungeon.listener.DungeonPortalHandler;
import com.skyblock.dungeon.classes.ClassCommand;
import com.skyblock.dungeon.classes.ClassProgressionService;
import com.skyblock.dungeon.classes.ClassSkillRegistry;
import com.skyblock.dungeon.classes.DungeonProgressionMenu;
import com.skyblock.dungeon.classes.LevelCommand;
import com.skyblock.dungeon.classes.SkillCommand;
import com.skyblock.dungeon.classes.PlayerClassStorage;
import com.skyblock.dungeon.classes.WeaponSkillListener;
import com.skyblock.dungeon.drops.DungeonDropItemFactory;
import com.skyblock.dungeon.drops.DungeonDropRegistry;
import com.skyblock.dungeon.drops.DungeonMobDropListener;
import com.skyblock.dungeon.items.DungeonItemGenerator;
import com.skyblock.dungeon.items.ItemCombatStatsListener;
import com.skyblock.dungeon.items.ItemLevelGateListener;
import com.skyblock.dungeon.loot.DungeonLootTable;
import com.skyblock.dungeon.loot.DungeonRarityRoller;
import com.skyblock.dungeon.progression.DungeonXpListener;
import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import com.skyblock.dungeon.spawn.DungeonBossRoomTrigger;
import com.skyblock.guild.GuildCommand;
import com.skyblock.guild.GuildInviteManager;
import com.skyblock.guild.GuildStorage;
import com.skyblock.dungeon.spawn.DungeonChestRoomPlacer;
import com.skyblock.dungeon.spawn.DungeonRoomMobSpawner;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;


public class SkyblockPlugin extends JavaPlugin {

    private DungeonResetScheduler dungeonResetScheduler;
    private DungeonCarveScheduler dungeonCarveScheduler;
    private com.skyblock.dungeon.floor.DungeonFloorStateStorage dungeonFloorStateStorage;
    private com.skyblock.dungeon.floor.DungeonFloorManager dungeonFloorManagerRef;

    @Override
    public void onEnable() {
        GuiBuilder.register(this);
        PluginManager pm = getServer().getPluginManager();

        // ── Storage ──────────────────────────────────────────────────────────
        IslandStorage islandStorage = new IslandStorage(getDataFolder(), getLogger());

        // ── Island systems ───────────────────────────────────────────────────
        IslandGenerator  islandGenerator  = new IslandGenerator(getLogger());
        InviteSystem     inviteSystem      = new InviteSystem(getLogger());
        PwarpSystem      pwarpSystem       = new PwarpSystem(islandStorage, getLogger());
        IslandMenu       islandMenu        = new IslandMenu(this, islandStorage, islandGenerator, inviteSystem, new NameValidator());
        IslandCommands   islandCommands    = new IslandCommands(islandStorage, islandGenerator, inviteSystem, pwarpSystem, this, getLogger());
        islandCommands.setIslandMenu(islandMenu);
        IslandProtection islandProtection  = new IslandProtection(islandStorage, this, getLogger());
        IslandPortal     islandPortal      = new IslandPortal(islandStorage, this, getLogger());

        // ── Economy commands ─────────────────────────────────────────────────
        BalCommand balCommand = new BalCommand(getLogger());
        PayCommand payCommand = new PayCommand(getLogger());

        // ── Auction House ────────────────────────────────────────────────────
        AhStorage ahStorage = new AhStorage(getDataFolder(), getLogger());
        AhSystem  ahSystem  = new AhSystem(ahStorage, this, getLogger());
        AhCommand ahCommand = new AhCommand(ahSystem);

        // ── Shop / Sell / Trash ──────────────────────────────────────────────
        ShopSystem      shopSystem      = new ShopSystem(this, getLogger());
        SellTrashSystem sellTrashSystem = new SellTrashSystem(this, getLogger());

        // ── Crates ───────────────────────────────────────────────────────────
        com.skyblock.items.SpecialItems specialItems = new com.skyblock.items.SpecialItems(this);
        CrateSystem  crateSystem  = new CrateSystem(this, specialItems);
        CrateCommand crateCommand = new CrateCommand(this);

        // ── Fishing ──────────────────────────────────────────────────────────
        FishingSystemV4 fishingV4 = new FishingSystemV4(this, getLogger());

        // ── Gem Shop ─────────────────────────────────────────────────────────
        GemshopSystem gemshopSystem = new GemshopSystem(this, crateSystem);

        // ── Admin (must be before MineSystem) ────────────────────────────────
        AdminShopSystem adminShopSystem = new AdminShopSystem(this, getLogger(), crateSystem);
        AdminSystem     adminSystem     = new AdminSystem(this, islandStorage, getLogger(), adminShopSystem);

        // ── Mine ─────────────────────────────────────────────────────────────
        MineSystem mineSystem = new MineSystem(this, adminSystem, crateSystem);
        specialItems.setMineSystem(mineSystem);

        // ── Minigames ────────────────────────────────────────────────────────
        MinigamesSystem minigamesSystem = new MinigamesSystem(this, getLogger());

        // ── Spawn / Teleport ─────────────────────────────────────────────────
        SpawnCommand     spawnCommand     = new SpawnCommand(this, getLogger());
        SpawnProtection  spawnProtection  = new SpawnProtection(getLogger());
        SpawnMobControl  spawnMobControl  = new SpawnMobControl(this, getLogger());
        TeleportCommands teleportCommands = new TeleportCommands(getLogger());

        // ── Kill Tracking / Kill Shop ─────────────────────────────────────────
        KillSystem killSystem = new KillSystem(this, crateSystem, getLogger());
        KillShopSystem killShopSystem = new KillShopSystem(this, crateSystem, getLogger());

        // ── Sidebar Scoreboard ────────────────────────────────────────────────
        SidebarSystem sidebarSystem = new SidebarSystem(this, getLogger());

        // ── TPA System ────────────────────────────────────────────────────────
        TpaSystem tpaSystem = new TpaSystem(this, getLogger());

        // ── Fly Command ───────────────────────────────────────────────────────
        FlyCommand flyCommand = new FlyCommand(islandStorage, getLogger());

        // ── Player Vaults ────────────────────────────────────────────────────
        VaultSystem vaultSystem = new VaultSystem(this, getLogger());

        // ── Dev ──────────────────────────────────────────────────────────────
        TestForms testForms = new TestForms(this, getLogger());

        // ── Dungeon ──────────────────────────────────────────────────────────
        // The dungeon lives in its own private world - no offset needed.
        // Floor 1's origin is (0, 0) in that world. Floor 0 (entrance hub)
        // sits FLOOR_0_TO_FLOOR_1_OFFSET blocks east (+X) of that origin
        // (i.e. at world X=2000), on the east side of Floor 1.
        FloorBounds dungeonFloorBounds = FloorBounds.standardWorld();
        double floor1OriginX = 0;
        double floor1OriginZ = 0;

        // Reset cycles hand the dungeon a brand-new, seed+date-derived
        // folder name each time (see DungeonResetScheduler /
        // DungeonWorldNameStore) rather than reusing a fixed name - so
        // startup has to ask what the CURRENT name is instead of
        // hardcoding one, or a restart mid-cycle would silently create/
        // load the wrong folder. If no record exists yet (first-ever
        // startup), generate one now using the same scheme so it's
        // prunable by future resets like any other cycle.
        String currentDungeonWorldName = com.skyblock.dungeon.floor.DungeonWorldNameStore
                .load(getDataFolder(), getLogger())
                .orElseGet(() -> com.skyblock.dungeon.floor.DungeonWorldNameStore
                        .generateNextName(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
        World dungeonWorld = getServer().getWorld(currentDungeonWorldName);
        if (dungeonWorld == null) {
            dungeonWorld = new WorldCreator(currentDungeonWorldName)
                .generator(new DungeonWorldGenerator(dungeonFloorBounds, floor1OriginX, floor1OriginZ))
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();
            // First-ever startup, no record file yet - write one now so
            // a restart before the first reset still finds this name.
            com.skyblock.dungeon.floor.DungeonWorldNameStore.save(getDataFolder(), currentDungeonWorldName, getLogger());
        }
        if (dungeonWorld == null) {
            getLogger().severe("[Dungeon] Failed to create/load the dungeon world - dungeon system disabled.");
        } else {
            FloorThemeRegistry dungeonThemeRegistry = new FloorThemeRegistry();
            DungeonHubBuilder.buildHub(dungeonWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ,
                    dungeonThemeRegistry.getTheme(1));
            Location floor0Location = DungeonHubBuilder.entranceLocation(dungeonWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);
            Location spawnLocation = getServer().getWorlds().get(0).getSpawnLocation();
            Location portalCorner1 = DungeonHubBuilder.portalCorner1(dungeonWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);
            Location portalCorner2 = DungeonHubBuilder.portalCorner2(dungeonWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);

            DungeonPlayerStateStorage dungeonStateStorage = new DungeonPlayerStateStorage(getDataFolder(), getLogger());
            spawnCommand.setDungeonSkipCheck(p -> {
                var st = dungeonStateStorage.get(p.getUniqueId());
                return st != null && st.isInsideDungeon();
            });
            java.util.Random dungeonRandom = new java.util.Random();

            // Ticks a bounded number of chunk carves per server tick instead
            // of letting frontier/staircase events carve dozens of chunks
            // synchronously in one go - see DungeonCarveScheduler's class
            // doc for the watchdog-crash story this fixes. Must be started
            // before anything can call planAndCarveNear/registerBossRoom.
            DungeonCarveScheduler dungeonCarveSchedulerLocal = new DungeonCarveScheduler();
            dungeonCarveSchedulerLocal.start(this);
            this.dungeonCarveScheduler = dungeonCarveSchedulerLocal;

            // Load whatever floor state (unlocked floors, boss rooms, carved
            // chunks, staircases, boss-kill status) survived from before this
            // startup - empty on a genuinely fresh world. See
            // DungeonFloorStateStorage's class doc for why this exists: without
            // it, every restart/crash silently wiped all of this and caused
            // the dungeon to re-carve (and overwrite staircases/loot inside)
            // areas players had already cleared.
            com.skyblock.dungeon.floor.DungeonFloorStateStorage dungeonFloorStateStorageLocal =
                new com.skyblock.dungeon.floor.DungeonFloorStateStorage(getDataFolder(), getLogger());
            com.skyblock.dungeon.floor.DungeonFloorStateStorage.FloorSnapshot dungeonSnapshot =
                dungeonFloorStateStorageLocal.load();
            boolean dungeonFreshStart = dungeonSnapshot.unlockedFloors().isEmpty();
            this.dungeonFloorStateStorage = dungeonFloorStateStorageLocal;

            DungeonFloorManager dungeonFloorManager = new DungeonFloorManager(
                dungeonWorld, dungeonFloorBounds, dungeonThemeRegistry,
                floor1OriginX, floor1OriginZ, getLogger(), dungeonRandom, dungeonCarveSchedulerLocal,
                dungeonFreshStart
            );
            if (!dungeonFreshStart) {
                dungeonFloorManager.applySnapshot(dungeonSnapshot);
            }
            this.dungeonFloorManagerRef = dungeonFloorManager;

            DungeonStaircaseOrchestrator dungeonStaircaseOrchestrator =
                new DungeonStaircaseOrchestrator(dungeonFloorManager, getLogger(), dungeonRandom);

            MobLevelRoller dungeonMobLevelRoller =
                new MobLevelRoller(dungeonFloorBounds.maxFloorCount(), dungeonRandom);
            MobLevelApplicator dungeonMobLevelApplicator = new MobLevelApplicator(this);

            // Area Zero's slimes and portal flicker need a JavaPlugin +
            // MobLevelApplicator that don't exist yet at the buildHub()
            // call site above - wired here instead, same timing (once at
            // startup, once per weekly reset via setOnWorldRecreated below).
            DungeonHubBuilder.spawnSlimes(dungeonWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ,
                    this, dungeonMobLevelApplicator, dungeonRandom);
            org.bukkit.scheduler.BukkitTask[] dungeonPortalAnimationTask = {
                    com.skyblock.dungeon.floor.AreaZeroPortalAnimator.start(this, dungeonWorld,
                            DungeonHubBuilder.portalGlassCoordinates(dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ))
            };

            DungeonRoomMobSpawner dungeonMobSpawner = new DungeonRoomMobSpawner(
                this, dungeonThemeRegistry, dungeonMobLevelRoller, dungeonMobLevelApplicator, dungeonRandom,
                dungeonFloorManager
            );
            PlayerProgressionStorage dungeonProgressionStorage =
                new PlayerProgressionStorage(getDataFolder(), getLogger());
            DungeonXpListener dungeonXpListener =
                new DungeonXpListener(dungeonMobLevelApplicator, dungeonProgressionStorage);

            // Guild storage is built here (rather than further down, where
            // it used to live) because ClassProgressionService needs it -
            // the very first class pick requires guild membership.
            GuildStorage guildStorage = new GuildStorage(getDataFolder(), getLogger());
            GuildInviteManager guildInviteManager = new GuildInviteManager();

            ClassSkillRegistry dungeonSkillRegistry = new ClassSkillRegistry();
            PlayerClassStorage dungeonClassStorage = new PlayerClassStorage(getDataFolder(), getLogger());
            WeaponSkillListener dungeonWeaponSkillListener = new WeaponSkillListener(
                this, dungeonClassStorage, dungeonSkillRegistry, dungeonProgressionStorage
            );

            // Shared pick/switch rules (one class at a time, level 10 +
            // guild required for the first pick, reset to level 0 on any
            // pick) - both the text command and the GUI call into this so
            // they can never drift out of sync on the actual rules.
            ClassProgressionService dungeonClassProgressionService =
                new ClassProgressionService(dungeonClassStorage, dungeonProgressionStorage, guildStorage);
            DungeonProgressionMenu dungeonProgressionMenu = new DungeonProgressionMenu(
                dungeonClassStorage, dungeonSkillRegistry, dungeonProgressionStorage, dungeonClassProgressionService
            );
            ClassCommand dungeonClassCommand = new ClassCommand(
                dungeonClassStorage, dungeonSkillRegistry, dungeonProgressionStorage,
                dungeonClassProgressionService, dungeonProgressionMenu
            );
            LevelCommand dungeonLevelCommand = new LevelCommand(dungeonProgressionMenu);
            SkillCommand dungeonSkillCommand = new SkillCommand(dungeonProgressionMenu);

            DungeonItemGenerator dungeonItemGenerator = new DungeonItemGenerator(this, dungeonRandom);
            ItemLevelGateListener dungeonItemLevelGateListener =
                new ItemLevelGateListener(dungeonItemGenerator, dungeonProgressionStorage);
            ItemCombatStatsListener dungeonItemCombatStatsListener =
                new ItemCombatStatsListener(dungeonItemGenerator, dungeonRandom);

            DungeonLootTable dungeonLootTable =
                new DungeonLootTable(new DungeonRarityRoller(), dungeonRandom, dungeonItemGenerator);

            DungeonDropRegistry dungeonDropRegistry = new DungeonDropRegistry();
            DungeonDropItemFactory dungeonDropItemFactory = new DungeonDropItemFactory(this, dungeonDropRegistry);
            DungeonMobDropListener dungeonMobDropListener = new DungeonMobDropListener(
                dungeonMobLevelApplicator, dungeonDropRegistry, dungeonDropItemFactory,
                new DungeonRarityRoller(dungeonRandom), dungeonItemGenerator, dungeonFloorBounds, dungeonRandom
            );

            // Chests roll a mix of real gear (weapons/armor) AND sellable
            // mob-drop items (fangs, cores, essences...) - previously mob
            // drops could only ever come from kills, never a chest.
            DungeonChestRoomPlacer dungeonChestPlacer = new DungeonChestRoomPlacer(
                dungeonLootTable, dungeonDropRegistry, dungeonDropItemFactory,
                new DungeonRarityRoller(dungeonRandom), dungeonRandom
            );

            // Stops dungeon mobs from dying to suffocation-in-terrain while
            // async carving catches up to a spot they've spawned in.
            DungeonMobSuffocationGuard dungeonMobSuffocationGuard =
                new DungeonMobSuffocationGuard(dungeonMobLevelApplicator);

            // Blocks vanilla/other-plugin mob spawns anywhere in the
            // dungeon world (only this plugin's own dungeon spawn
            // machinery may place a mob there), enforces "nothing but
            // Area Zero's own slimes on floor 0", and stops dungeon
            // slimes from splitting on death.
            com.skyblock.dungeon.listener.DungeonNaturalSpawnGuard dungeonNaturalSpawnGuard =
                new com.skyblock.dungeon.listener.DungeonNaturalSpawnGuard(
                    dungeonFloorManager, dungeonFloorBounds, dungeonMobLevelApplicator);

            GuildCommand guildCommand = new GuildCommand(guildStorage, guildInviteManager, dungeonDropItemFactory);

            DungeonBossGateController dungeonBossGateController =
                new DungeonBossGateController(this, dungeonFloorManager, dungeonThemeRegistry);
            BossArchetypeRegistry dungeonBossArchetypeRegistry = new BossArchetypeRegistry(dungeonRandom);
            DungeonBossRoomTrigger dungeonBossRoomTrigger = new DungeonBossRoomTrigger(
                this, dungeonThemeRegistry, dungeonMobLevelRoller, dungeonMobLevelApplicator,
                dungeonStaircaseOrchestrator, dungeonBossGateController, dungeonBossArchetypeRegistry, dungeonRandom
            );
            DungeonBossAnchorListener dungeonBossAnchorListener =
                new DungeonBossAnchorListener(dungeonMobLevelApplicator);

            // Restored floors whose boss was already dead before this restart
            // must not let the boss room trigger spawn a brand new boss the
            // moment a player walks back in - triggeredRooms is transient and
            // has no memory of the old kill otherwise.
            if (!dungeonFreshStart) {
                for (Integer clearedFloor : dungeonFloorManager.bossKillTracker().clearedFloorNumbers()) {
                    com.skyblock.dungeon.gen.DungeonRoom clearedBossRoom = dungeonFloorManager.getBossRoom(clearedFloor);
                    if (clearedBossRoom != null) {
                        dungeonBossRoomTrigger.markAlreadyTriggered(clearedBossRoom.id());
                    }
                }
            }

            // Milestone floors (every 5th, per FloorThemeRegistry) get a real
            // scripted boss instead of silently falling back to buffed
            // vanilla. Swap in distinct boss classes per floor later by
            // adding more registerMilestoneBossFactory calls here.
            for (int floorNum = 1; floorNum <= dungeonFloorBounds.maxFloorCount(); floorNum++) {
                if (dungeonThemeRegistry.isMilestoneFloor(floorNum)) {
                    dungeonBossRoomTrigger.registerMilestoneBossFactory(floorNum, ExampleMilestoneBoss::new);
                }
            }

            // One combined carve listener: every newly carved room gets a
            // chance at ambient mobs and, if it's a CHEST room, loot.
            dungeonFloorManager.setGlobalRoomCarveListener((world, floorNumber, room) -> {
                int floorBottomY = dungeonFloorBounds.floorBottomY(floorNumber);
                dungeonMobSpawner.spawnForRoom(world, floorNumber, floorBottomY, room);
                dungeonChestPlacer.placeForRoom(world, floorNumber, floorBottomY, room);
            });

            // Built before DungeonCommand since /dungeon reset needs it -
            // moved up from where it's started further down.
            DungeonResetScheduler dungeonResetSchedulerLocal = new DungeonResetScheduler(
                this, dungeonFloorManager, getDataFolder(),
                () -> new DungeonWorldGenerator(dungeonFloorBounds, floor1OriginX, floor1OriginZ),
                () -> getServer().getWorlds().get(0).getSpawnLocation(),
                dungeonCarveSchedulerLocal,
                getLogger()
            );

            DungeonCommand dungeonCommand =
                new DungeonCommand(dungeonStateStorage::get, floor0Location, dungeonResetSchedulerLocal);
            DungeonBlockProtectionListener dungeonBlockProtectionListener =
                new DungeonBlockProtectionListener(dungeonWorld);
            DungeonDeathHandler dungeonDeathHandler =
                new DungeonDeathHandler(this, dungeonStateStorage::get, spawnLocation, getLogger());
            DungeonPortalHandler dungeonPortalHandler = new DungeonPortalHandler(
                dungeonStateStorage::get, spawnLocation, portalCorner1, portalCorner2, getLogger()
            );
            DungeonCommandLockdownListener dungeonLockdownListener =
                new DungeonCommandLockdownListener(dungeonStateStorage::get);
            DungeonJoinQuitListener dungeonJoinQuitListener = new DungeonJoinQuitListener(
                dungeonStateStorage::get,
                dungeonStateStorage::persist,
                floorNumber -> dungeonFloorManager.dungeonWorld()
            );
            DungeonFrontierListener dungeonFrontierListener = new DungeonFrontierListener(
                dungeonStateStorage::get, dungeonFloorManager, dungeonBossRoomTrigger
            );
            DungeonChestLootListener dungeonChestLootListener = new DungeonChestLootListener(
                dungeonFloorManager::getOrCreateRoomGraph,
                dungeonFloorBounds::floorForY
            );

            dungeonResetSchedulerLocal.setOnPlayerEjected(ejectedPlayer -> {
                DungeonPlayerState ejectedState = dungeonStateStorage.get(ejectedPlayer.getUniqueId());
                if (ejectedState != null) {
                    ejectedState.clearDungeonState();
                    dungeonStateStorage.persist(ejectedPlayer.getUniqueId(), ejectedState);
                }
            });

            // Rebuilds everything that's tied to a specific dungeon World
            // instance once DungeonResetScheduler deletes and recreates it.
            // Order matters: floorManager.setDungeonWorld() already happened
            // inside the scheduler before this fires, so generation is safe
            // to trigger by the time buildHub runs.
            dungeonResetSchedulerLocal.setOnWorldRecreated(newWorld -> {
                DungeonHubBuilder.buildHub(newWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ,
                        dungeonThemeRegistry.getTheme(1));
                Location newFloor0Location = DungeonHubBuilder.entranceLocation(
                        newWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);
                Location newPortalCorner1 = DungeonHubBuilder.portalCorner1(
                        newWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);
                Location newPortalCorner2 = DungeonHubBuilder.portalCorner2(
                        newWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);

                dungeonCommand.setFloor0Location(newFloor0Location);
                dungeonPortalHandler.updatePortalBounds(newPortalCorner1, newPortalCorner2);
                dungeonBlockProtectionListener.setDungeonWorld(newWorld);

                DungeonHubBuilder.spawnSlimes(newWorld, dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ,
                        this, dungeonMobLevelApplicator, dungeonRandom);
                dungeonPortalAnimationTask[0].cancel();
                dungeonPortalAnimationTask[0] = com.skyblock.dungeon.floor.AreaZeroPortalAnimator.start(
                        this, newWorld,
                        DungeonHubBuilder.portalGlassCoordinates(dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ));

                // The weekly wipe makes all previously persisted floor state
                // (carved chunks, staircases, boss rooms, cleared floors)
                // meaningless - the world it describes no longer exists.
                // Without this, the fresh floor 1 DungeonFloorManager.resetAll()
                // just created would get immediately clobbered by the next
                // autosave writing the OLD (pre-reset) snapshot back on top of it.
                dungeonFloorStateStorageLocal.clear();

                getLogger().info("[Dungeon] Hub rebuilt and entrance/portal/protection repointed at the new world.");
            });

            dungeonResetSchedulerLocal.start();
            this.dungeonResetScheduler = dungeonResetSchedulerLocal;

            // Periodic autosave of floor generation state, independent of a
            // clean shutdown - onDisable() never runs on a crash/kill, which
            // is exactly the scenario this whole persistence layer exists
            // for. Every 5 minutes caps how much progress a crash can lose.
            final DungeonFloorManager dungeonFloorManagerForAutosave = dungeonFloorManager;
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                dungeonFloorStateStorageLocal.save(dungeonFloorManagerForAutosave, dungeonFloorManagerForAutosave.bossKillTracker());
            }, 20L * 60L * 5L, 20L * 60L * 5L);

            getCommand("dungeon").setExecutor(dungeonCommand);
            getCommand("class").setExecutor(dungeonClassCommand);
            getCommand("level").setExecutor(dungeonLevelCommand);
            getCommand("skill").setExecutor(dungeonSkillCommand);
            getCommand("guild").setExecutor(guildCommand);

            pm.registerEvents(dungeonDeathHandler, this);
            pm.registerEvents(dungeonPortalHandler, this);
            pm.registerEvents(dungeonLockdownListener, this);
            pm.registerEvents(dungeonJoinQuitListener, this);
            pm.registerEvents(dungeonStaircaseOrchestrator, this);
            pm.registerEvents(dungeonBossGateController, this);
            pm.registerEvents(dungeonXpListener, this);
            pm.registerEvents(dungeonWeaponSkillListener, this);
            pm.registerEvents(dungeonItemLevelGateListener, this);
            pm.registerEvents(dungeonItemCombatStatsListener, this);
            pm.registerEvents(dungeonMobDropListener, this);
            pm.registerEvents(dungeonMobSuffocationGuard, this);
            pm.registerEvents(dungeonBossAnchorListener, this);
            pm.registerEvents(dungeonFrontierListener, this);
            pm.registerEvents(dungeonChestLootListener, this);
            pm.registerEvents(dungeonBlockProtectionListener, this);
            pm.registerEvents(dungeonNaturalSpawnGuard, this);

            // ── Admin dungeon controls ──────────────────────────────────────
            // "/admin dungeon start": kicks off generation at the Floor 1
            // entrance (proximity-driven generation otherwise only starts
            // once a real player walks there) and teleports the admin
            // straight to it, marking them as inside the dungeon so the
            // lockdown/death/portal listeners apply normally.
            //
            // Reads the world from finalDungeonFloorManager.dungeonWorld()
            // rather than capturing the World instance directly, since
            // that's kept up to date across resets (setDungeonWorld) - a
            // captured World local here would go stale exactly like
            // floor0Location/portalCorner1/2 did before those got setters.
            DungeonFloorManager finalDungeonFloorManager = dungeonFloorManager;
            adminSystem.setDungeonAdminHandler(adminPlayer -> {
                DungeonPlayerState adminState = dungeonStateStorage.get(adminPlayer.getUniqueId());
                adminState.setInsideDungeon(true);
                adminState.setCurrentFloor(1);

                // Teleport to just inside Floor 0's entrance hub — derived
                // from the hub builder so it stays correct if the hub layout
                // ever changes, not hardcoded.
                Location dungeonEntrance = DungeonHubBuilder.entranceLocation(
                        finalDungeonFloorManager.dungeonWorld(), dungeonFloorBounds, (int) floor1OriginX, (int) floor1OriginZ);
                adminPlayer.teleport(dungeonEntrance);
                finalDungeonFloorManager.onPlayerFrontier(1, floor1OriginX, floor1OriginZ);

                adminPlayer.sendMessage("§aDungeon generation initialized - teleported to the dungeon entrance.");
            });
        }

        // ── Register commands ─────────────────────────────────────────────────
        getCommand("admin").setExecutor(adminSystem);
        getCommand("ah").setExecutor(ahCommand);
        getCommand("bal").setExecutor(balCommand);
        getCommand("crates").setExecutor(crateCommand);
        getCommand("fish").setExecutor(fishingV4);
        getCommand("gemshop").setExecutor(gemshopSystem);
        getCommand("is").setExecutor(islandCommands);
        getCommand("mine").setExecutor(teleportCommands);
        getCommand("resetmine").setExecutor(mineSystem);
        getCommand("minigames").setExecutor(minigamesSystem);
        getCommand("pvp").setExecutor(teleportCommands);
        getCommand("pay").setExecutor(payCommand);
        getCommand("pwarp").setExecutor(pwarpSystem);
        getCommand("setwarp").setExecutor(islandCommands);
        getCommand("delwarp").setExecutor(islandCommands);
        getCommand("sell").setExecutor(new SellCommand(sellTrashSystem));
        getCommand("sellall").setExecutor(new SellCommand(sellTrashSystem));
        getCommand("shop").setExecutor(new ShopCommand(shopSystem));
        getCommand("spawn").setExecutor(spawnCommand);
        getCommand("hub").setExecutor(spawnCommand);
        getCommand("trash").setExecutor(new TrashCommand(sellTrashSystem));        getCommand("vault").setExecutor(vaultSystem);
        getCommand("testforms").setExecutor(testForms);
        getCommand("killshop").setExecutor(killShopSystem);
        getCommand("kills").setExecutor(killSystem);
        getCommand("tpa").setExecutor(tpaSystem);
        getCommand("tphere").setExecutor(tpaSystem);
        getCommand("tpaccept").setExecutor(tpaSystem);
        getCommand("tpdeny").setExecutor(tpaSystem);
        getCommand("fly").setExecutor(flyCommand);

        // ── Register listeners ────────────────────────────────────────────────
        pm.registerEvents(adminSystem, this);
        pm.registerEvents(adminShopSystem, this);
        pm.registerEvents(islandMenu, this);
        pm.registerEvents(islandProtection, this);
        pm.registerEvents(islandPortal, this);
        pm.registerEvents(inviteSystem, this);
        pm.registerEvents(spawnProtection, this);
        pm.registerEvents(spawnMobControl, this);
        pm.registerEvents(fishingV4, this);
        pm.registerEvents(crateSystem, this);
        pm.registerEvents(mineSystem, this);        pm.registerEvents(spawnCommand, this);
        pm.registerEvents(testForms, this);
        pm.registerEvents(gemshopSystem, this);
        pm.registerEvents(ahSystem, this);
        pm.registerEvents(sellTrashSystem, this);
        pm.registerEvents(shopSystem, this);
        pm.registerEvents(minigamesSystem, this);
        pm.registerEvents(vaultSystem, this);
        pm.registerEvents(killSystem, this);
        pm.registerEvents(killShopSystem, this);
        pm.registerEvents(sidebarSystem, this);
        pm.registerEvents(tpaSystem, this);
        pm.registerEvents(flyCommand, this);

        getLogger().info("[SkyblockPlugin] All systems enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (dungeonFloorStateStorage != null && dungeonFloorManagerRef != null) {
            dungeonFloorStateStorage.save(dungeonFloorManagerRef, dungeonFloorManagerRef.bossKillTracker());
        }
        if (dungeonResetScheduler != null) {
            dungeonResetScheduler.cancel();
        }
        if (dungeonCarveScheduler != null) {
            dungeonCarveScheduler.stop();
        }
        getLogger().info("[SkyblockPlugin] Plugin disabled.");
    }
}
