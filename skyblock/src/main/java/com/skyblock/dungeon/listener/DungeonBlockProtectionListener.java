package com.skyblock.dungeon.listener;

import com.skyblock.protection.SpawnProtection;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Protects the procedurally generated blocks that make up the dungeon
 * (stone buffer, carved room/corridor walls, floors, ceilings, staircases,
 * etc.) from being broken or placed by regular players. Without this,
 * players could tunnel straight through the graph-based layout, bypass
 * the frontier/carving pipeline, or otherwise undermine the "permanent
 * generation for the week" design described in the project overview.
 *
 * Deliberately reuses SpawnProtection.BYPASS_TAG rather than a separate
 * tag: admins already have a "Spawn Build Bypass" toggle in /admin
 * (AdminSystem.toggleSpawnBypass), and per design that same toggle
 * should also let them freely edit dungeon terrain (e.g. to hand-place
 * milestone boss arenas or fix a bad carve) without a second toggle to
 * remember.
 *
 * Scoped purely to the dungeon world - the entire world is protected,
 * not just explored sections, since unexplored solid-stone buffer
 * blocks (see StoneBufferGenerator/DungeonWorldGenerator) are just as
 * much "dungeon blocks" as carved rooms and shouldn't be pre-minable.
 *
 * The dungeon world gets deleted and recreated as a brand-new World
 * object on every DungeonResetScheduler reset, so dungeonWorld here is
 * mutable - call setDungeonWorld(...) with the fresh World right after
 * a reset, or this listener keeps comparing against the old, unloaded
 * World forever and silently stops protecting anything.
 *
 * Loot barrel breaking is intentionally left alone here: DungeonChestLootListener
 * already listens to the same BlockBreakEvent to mark loot rooms as
 * looted, and still fires that bookkeeping even when this listener
 * cancels the physical break, so barrels remain track-able as "looted"
 * either by breaking or by emptying them via the inventory.
 */
public final class DungeonBlockProtectionListener implements Listener {

    private World dungeonWorld;

    public DungeonBlockProtectionListener(World dungeonWorld) {
        this.dungeonWorld = dungeonWorld;
    }

    /** Repoints this listener at a freshly (re)created dungeon World after a reset. */
    public void setDungeonWorld(World dungeonWorld) {
        this.dungeonWorld = dungeonWorld;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getScoreboardTags().contains(SpawnProtection.BYPASS_TAG)) return;
        if (!event.getBlock().getWorld().equals(dungeonWorld)) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "You can't break dungeon blocks.");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.getScoreboardTags().contains(SpawnProtection.BYPASS_TAG)) return;
        if (!event.getBlock().getWorld().equals(dungeonWorld)) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "You can't place blocks in the dungeon.");
    }
}
