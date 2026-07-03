package com.skyblock.dungeon.classes;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * CSV-backed persistence for PlayerClassState, same simple-format
 * approach as the rest of the dungeon's storage classes. Skill ranks
 * are packed into the third CSV field as "id:rank|id:rank|..." so a
 * player's whole skill spread stays on one line.
 */
public final class PlayerClassStorage {

    private final File storageFile;
    private final Logger logger;
    private final Map<UUID, PlayerClassState> states = new ConcurrentHashMap<>();

    public PlayerClassStorage(File dataFolder, Logger logger) {
        this.logger = logger;
        File dungeonFolder = new File(dataFolder, "dungeon");
        if (!dungeonFolder.exists()) {
            dungeonFolder.mkdirs();
        }
        this.storageFile = new File(dungeonFolder, "player_classes.csv");
        load();
    }

    /** Resolves a player's class state, creating a fresh no-class-chosen entry if none exists yet. */
    public PlayerClassState get(UUID playerId) {
        return states.computeIfAbsent(playerId, PlayerClassState::new);
    }

    public void persist(UUID playerId, PlayerClassState state) {
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
                String[] parts = line.split(",", 3);
                if (parts.length < 2) continue;

                UUID id = UUID.fromString(parts[0]);
                PlayerClassState state = new PlayerClassState(id);

                PlayerClassType type = "NONE".equals(parts[1]) ? null : PlayerClassType.valueOf(parts[1]);
                Map<String, Integer> ranks = new HashMap<>();
                if (parts.length == 3 && !parts[2].isBlank()) {
                    for (String entry : parts[2].split("\\|")) {
                        String[] kv = entry.split(":");
                        if (kv.length != 2) continue;
                        ranks.put(kv[0], Integer.parseInt(kv[1]));
                    }
                }
                state.restore(type, ranks);
                states.put(id, state);
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warning("[Dungeon] Failed to load player class file: " + e.getMessage());
        }
    }

    private synchronized void saveAll() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile))) {
            for (PlayerClassState state : states.values()) {
                StringBuilder ranks = new StringBuilder();
                for (Map.Entry<String, Integer> entry : state.getSkillRanks().entrySet()) {
                    if (ranks.length() > 0) ranks.append('|');
                    ranks.append(entry.getKey()).append(':').append(entry.getValue());
                }
                writer.write(String.join(",",
                        state.getPlayerId().toString(),
                        state.getClassType() != null ? state.getClassType().name() : "NONE",
                        ranks.toString()
                ));
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to save player class file: " + e.getMessage());
        }
    }
}
