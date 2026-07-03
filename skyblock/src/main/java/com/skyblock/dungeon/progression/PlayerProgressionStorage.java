package com.skyblock.dungeon.progression;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns every player's PlayerProgressionState in memory and persists it
 * to a flat CSV file, same simple-format approach as
 * DungeonPlayerStateStorage (no JSON dependency this project doesn't
 * already pull in).
 */
public final class PlayerProgressionStorage {

    private final File storageFile;
    private final Logger logger;
    private final Map<UUID, PlayerProgressionState> states = new ConcurrentHashMap<>();

    public PlayerProgressionStorage(File dataFolder, Logger logger) {
        this.logger = logger;
        File dungeonFolder = new File(dataFolder, "dungeon");
        if (!dungeonFolder.exists()) {
            dungeonFolder.mkdirs();
        }
        this.storageFile = new File(dungeonFolder, "player_progression.csv");
        load();
    }

    /** Resolves a player's progression, creating a fresh level-1 entry if none exists yet. */
    public PlayerProgressionState get(UUID playerId) {
        return states.computeIfAbsent(playerId, PlayerProgressionState::new);
    }

    /**
     * Persists one player's progression to disk immediately. Called
     * after every XP-granting kill, same "just rewrite the whole file"
     * approach as DungeonPlayerStateStorage - simple and correct for
     * this project's scale, revisit if profiling ever shows it's hot.
     */
    public void persist(UUID playerId, PlayerProgressionState state) {
        states.put(playerId, state);
        saveAll();
    }

    private void load() {
        if (!storageFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length != 5) continue;

                UUID id = UUID.fromString(parts[0]);
                PlayerProgressionState state = new PlayerProgressionState(id);
                state.restore(
                        Integer.parseInt(parts[1]),
                        Long.parseLong(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4])
                );
                states.put(id, state);
            }
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to load player progression file: " + e.getMessage());
        }
    }

    private synchronized void saveAll() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile))) {
            for (PlayerProgressionState state : states.values()) {
                writer.write(String.join(",",
                        state.getPlayerId().toString(),
                        String.valueOf(state.getCharacterLevel()),
                        String.valueOf(state.getCurrentXp()),
                        String.valueOf(state.getUnspentSkillPoints()),
                        String.valueOf(state.getTotalSkillPointsEarned())
                ));
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to save player progression file: " + e.getMessage());
        }
    }
}
