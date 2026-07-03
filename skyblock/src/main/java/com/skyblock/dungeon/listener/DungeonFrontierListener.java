package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.floor.DungeonFloorManager;
import com.skyblock.dungeon.floor.DungeonPlayerState;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.spawn.DungeonBossRoomTrigger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;
import java.util.function.Function;

/**
 * Drives dungeon cave generation ahead of moving players.
 *
 * On every whole-block XZ move for a player inside the dungeon, this
 * fires generation at TWO points:
 *
 *   1. The player's current position — keeps content carved right at
 *      their feet so they're never standing on uncarved stone.
 *
 *   2. A look-ahead point: LOOK_AHEAD_DISTANCE blocks forward along
 *      the player's current yaw — so the cave is already carved by
 *      the time the player walks there. This is the key difference
 *      from the old model where the player's own footstep literally
 *      created the path. The cave carver runs ahead; the player just
 *      discovers it.
 *
 * Both calls go into DungeonFloorManager.onPlayerFrontier() which
 * dispatches to DungeonRoomPlanner.planAndCarveNear(). That method is
 * idempotent (skips already-carved chunks), so the double-call is cheap
 * once an area is established.
 */
public final class DungeonFrontierListener implements Listener {

    /**
     * How far ahead (in blocks) to project generation along the player's
     * facing direction. Sized to comfortably pre-carve 2-3 chunk columns
     * before the player reaches them at normal walking speed.
     */
    private static final int LOOK_AHEAD_DISTANCE = 80;

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final DungeonFloorManager floorManager;
    private final DungeonBossRoomTrigger bossRoomTrigger;

    public DungeonFrontierListener(Function<UUID, DungeonPlayerState> stateLookup,
                                    DungeonFloorManager floorManager,
                                    DungeonBossRoomTrigger bossRoomTrigger) {
        this.stateLookup    = stateLookup;
        this.floorManager   = floorManager;
        this.bossRoomTrigger = bossRoomTrigger;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to   = event.getTo();
        Location from = event.getFrom();
        if (to == null) return;

        // Only fire on whole-block XZ movement — sub-block head rotation
        // would otherwise spam the generator for no benefit.
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) return;

        Player player = event.getPlayer();
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());
        if (state == null || !state.isInsideDungeon()) return;

        // Dimension-identity guard: isInsideDungeon() is a persisted flag
        // and can go stale (relog after a code change, a bypassed exit,
        // etc.) independently of which World the player is actually
        // standing in. Without this check, a flagged player walking
        // around in ANY world - including the overworld hub, whose
        // spawn XZ happens to sit right on top of the dungeon's own
        // Floor 1 origin - would silently drive real, synchronous cave
        // generation inside the dungeon dimension every time they take
        // a step. Terrain generation must only ever be triggered by
        // players who are physically inside the dungeon world.
        if (!player.getWorld().equals(floorManager.dungeonWorld())) {
            state.clearDungeonState(); // self-heal: they're not really in the dungeon
            return;
        }

        int floorNumber = state.getCurrentFloor();
        if (floorNumber < 1) {
            // Floor 0 is the static entrance hub - nothing generates there.
            // But DungeonCommand only ever sets currentFloor to 0 on entry;
            // nothing else was ever promoting a player to Floor 1 once they
            // physically walked out through the hub's west doorway, which
            // meant floorNumber stayed 0 forever and this method returned
            // every single tick - real players never reached the frontier
            // carve call below, only ever seeing DungeonWorldGenerator's
            // static stone blockout. Detect the crossing here: once the
            // player's XZ falls inside Floor 1's own generation leash
            // (they've stepped past the doorway into real floor space),
            // promote them and fall through to carve immediately instead
            // of waiting for another move event.
            boolean enteredFloor1 = floorManager.floorBounds().isWithinGenerationRadius(
                    floorManager.floor1OriginX(), floorManager.floor1OriginZ(), to.getX(), to.getZ());
            if (!enteredFloor1) return;
            state.setCurrentFloor(1);
            floorNumber = 1;
        }

        // Reconcile against the player's REAL vertical position rather
        // than blindly trusting the persisted currentFloor flag.
        //
        // currentFloor used to only ever get written in two places:
        // DungeonCommand (entering via the hub, always floor 1) and the
        // Floor-0-crossing promotion right above. Nothing ever updated
        // it when a player descended a staircase to floor 2+ - so a
        // player physically standing on floor 2 still had currentFloor
        // == 1 forever, and onPlayerFrontier(1, ...) kept firing every
        // move, carving floor 1's cave band - which sits directly above
        // floor 2 - instead of floor 2's. That's the exact "floor 1
        // still generates above me while I'm on floor 2" symptom.
        //
        // FloorBounds.floorForY is the single source of truth for which
        // floor's walkable band a Y coordinate falls in, so resolve the
        // player's actual floor from their Y every move and correct the
        // stored flag whenever it disagrees. A resolved value of -1
        // (mid-shaft in a border, or standing on a ladder between
        // floors) is intentionally ignored - we keep going with the
        // last known-good floor rather than stopping generation while
        // the player is transiently between bands.
        int resolvedFloor = floorManager.floorBounds().floorForY((int) Math.floor(to.getY()));
        if (resolvedFloor >= 1 && resolvedFloor != floorNumber) {
            state.setCurrentFloor(resolvedFloor);
            floorNumber = resolvedFloor;
        }

        // 1. Generate at player's current position.
        floorManager.onPlayerFrontier(floorNumber, to.getX(), to.getZ());

        // 2. Project LOOK_AHEAD_DISTANCE blocks along the player's yaw and
        //    generate there too, so the cave exists before the player arrives.
        double yawRad = Math.toRadians(to.getYaw());
        double lookX  = to.getX() - Math.sin(yawRad) * LOOK_AHEAD_DISTANCE;
        double lookZ  = to.getZ() + Math.cos(yawRad) * LOOK_AHEAD_DISTANCE;
        floorManager.onPlayerFrontier(floorNumber, lookX, lookZ);

        // Boss room proximity check — only needs the player's actual position.
        DungeonRoom bossRoom = floorManager.getBossRoom(floorNumber);
        if (bossRoom != null) {
            int floorBottomY = floorManager.floorBounds().floorBottomY(floorNumber);
            bossRoomTrigger.onPlayerPosition(floorManager.dungeonWorld(), floorNumber,
                    floorBottomY, bossRoom, to.getX(), to.getZ());
        }
    }
}
