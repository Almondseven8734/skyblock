package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.gen.DungeonRoom;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Persists everything DungeonFloorManager keeps purely in memory:
 * which floors are unlocked, where each floor's boss room is, which
 * chunk columns have already been carved, which XZ points already
 * have a staircase, and which floors' bosses are already dead.
 *
 * WHY THIS EXISTS: before this class, all of the above lived only in
 * ConcurrentHashMaps inside DungeonFloorManager/DungeonRoomPlanner/
 * StaircasePlacementValidator/BossKillTracker. The physical world
 * (blocks on disk) survives a server crash or restart just fine, but
 * that in-memory bookkeeping doesn't - it starts empty every time the
 * plugin loads. The consequences, all previously reported as separate
 * bugs, are actually one root cause:
 *   - "the dungeon is rebuilding previously built sections": with an
 *     empty carved-chunk set, DungeonRoomPlanner.planAndCarveNear()
 *     treats every already-carved chunk a player walks near as
 *     brand new and re-runs the (deterministic, seeded) noise carve
 *     over it - which also blindly overwrites staircases, placed
 *     chests, and anything else that isn't part of the raw noise
 *     pass, since the carve loop has no idea a staircase shaft was
 *     ever punched through that column.
 *   - staircases "disappearing"/never being found: the same
 *     re-carve silently fills ladder shafts back in with stone/theme
 *     blocks the next time a player passes through that chunk.
 *   - bosses re-spawning or floors resetting to locked: unlockedFloors
 *     and bossRooms both reset too, so a floor that was fully cleared
 *     before a restart goes back to "only floor 1 unlocked."
 *
 * File format: simple line-oriented text (mirrors the style of
 * DungeonPlayerStateStorage elsewhere in this codebase) rather than a
 * binary/JSON format, so it stays easy to inspect/hand-edit and needs
 * no extra dependency. Carved-chunk lists can get large (thousands of
 * entries per floor once a floor is heavily explored) so each chunk
 * key gets its own compact line rather than one giant CSV row.
 */
public final class DungeonFloorStateStorage {

    /** Immutable snapshot of everything persisted, handed to DungeonFloorManager.applySnapshot(). */
    public record FloorSnapshot(
            Set<Integer> unlockedFloors,
            Map<Integer, BossRoomRecord> bossRooms,
            Map<Integer, Set<Long>> carvedChunks,
            Map<Integer, List<double[]>> staircases,
            Set<Integer> clearedFloors
    ) {
        public static FloorSnapshot empty() {
            return new FloorSnapshot(Set.of(), Map.of(), Map.of(), Map.of(), Set.of());
        }
    }

    public record BossRoomRecord(int x, int z, int radiusX, int radiusZ) { }

    private final File storageFile;
    private final Logger logger;

    public DungeonFloorStateStorage(File dataFolder, Logger logger) {
        this.logger = logger;
        File dungeonFolder = new File(dataFolder, "dungeon");
        if (!dungeonFolder.exists()) {
            dungeonFolder.mkdirs();
        }
        this.storageFile = new File(dungeonFolder, "floor_state.dat");
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    /**
     * Writes a full snapshot of the given floor manager's state to disk,
     * atomically (write to a temp file, then rename) so a crash mid-save
     * can never leave a half-written, corrupt state file behind - that
     * would be strictly worse than the bug this class fixes.
     */
    public synchronized void save(DungeonFloorManager manager, BossKillTracker bossKillTracker) {
        File tmp = new File(storageFile.getParentFile(), storageFile.getName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tmp))) {
            StringBuilder unlocked = new StringBuilder("UNLOCKED");
            for (Integer floor : manager.unlockedFloors()) {
                unlocked.append(',').append(floor);
            }
            writer.write(unlocked.toString());
            writer.newLine();

            for (Integer floor : manager.bossRoomFloors()) {
                DungeonRoom room = manager.getBossRoom(floor);
                if (room == null) continue;
                writer.write("BOSSROOM," + floor + "," + room.centerX() + "," + room.centerZ()
                        + "," + room.radiusX() + "," + room.radiusZ());
                writer.newLine();
            }

            for (Integer floor : manager.activeFloorNumbers()) {
                for (Long key : manager.getOrCreatePlanner(floor).carvedChunkKeys()) {
                    writer.write("CHUNK," + floor + "," + key);
                    writer.newLine();
                }
            }

            for (Integer floor : manager.staircaseValidatorFloors()) {
                for (double[] coord : manager.getOrCreateStaircaseValidator(floor).getPlacedStaircases()) {
                    writer.write("STAIR," + floor + "," + coord[0] + "," + coord[1]);
                    writer.newLine();
                }
            }

            StringBuilder cleared = new StringBuilder("CLEARED");
            for (Integer floor : bossKillTracker.clearedFloorNumbers()) {
                cleared.append(',').append(floor);
            }
            writer.write(cleared.toString());
            writer.newLine();
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to write floor state snapshot: " + e.getMessage());
            return;
        }

        if (!tmp.renameTo(storageFile)) {
            // renameTo can fail cross-filesystem on some setups - fall back to copy+delete.
            try (InputStream in = new FileInputStream(tmp); OutputStream out = new FileOutputStream(storageFile)) {
                in.transferTo(out);
                tmp.delete();
            } catch (IOException e) {
                logger.warning("[Dungeon] Failed to finalize floor state snapshot: " + e.getMessage());
            }
        }
    }

    // ─── Load ────────────────────────────────────────────────────────────────

    /** Reads the persisted snapshot from disk, or an empty snapshot if none exists yet (first-ever startup). */
    public synchronized FloorSnapshot load() {
        if (!storageFile.exists()) {
            return FloorSnapshot.empty();
        }

        Set<Integer> unlockedFloors = new HashSet<>();
        Map<Integer, BossRoomRecord> bossRooms = new HashMap<>();
        Map<Integer, Set<Long>> carvedChunks = new HashMap<>();
        Map<Integer, List<double[]>> staircases = new HashMap<>();
        Set<Integer> clearedFloors = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                switch (parts[0]) {
                    case "UNLOCKED" -> {
                        for (int i = 1; i < parts.length; i++) {
                            unlockedFloors.add(Integer.parseInt(parts[i]));
                        }
                    }
                    case "BOSSROOM" -> {
                        if (parts.length != 6) continue;
                        int floor = Integer.parseInt(parts[1]);
                        bossRooms.put(floor, new BossRoomRecord(
                                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                                Integer.parseInt(parts[4]), Integer.parseInt(parts[5])));
                    }
                    case "CHUNK" -> {
                        if (parts.length != 3) continue;
                        int floor = Integer.parseInt(parts[1]);
                        carvedChunks.computeIfAbsent(floor, f -> new HashSet<>()).add(Long.parseLong(parts[2]));
                    }
                    case "STAIR" -> {
                        if (parts.length != 4) continue;
                        int floor = Integer.parseInt(parts[1]);
                        staircases.computeIfAbsent(floor, f -> new ArrayList<>())
                                .add(new double[]{Double.parseDouble(parts[2]), Double.parseDouble(parts[3])});
                    }
                    case "CLEARED" -> {
                        for (int i = 1; i < parts.length; i++) {
                            clearedFloors.add(Integer.parseInt(parts[i]));
                        }
                    }
                    default -> logger.warning("[Dungeon] Unrecognized floor_state.dat line type: " + parts[0]);
                }
            }
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to read floor state snapshot, starting fresh: " + e.getMessage());
            return FloorSnapshot.empty();
        }

        return new FloorSnapshot(unlockedFloors, bossRooms, carvedChunks, staircases, clearedFloors);
    }

    /** Deletes the persisted state file - called on the weekly dungeon wipe, since old floor state is meaningless once the world is regenerated. */
    public synchronized void clear() {
        if (storageFile.exists() && !storageFile.delete()) {
            logger.warning("[Dungeon] Failed to delete floor_state.dat on reset.");
        }
    }
}
