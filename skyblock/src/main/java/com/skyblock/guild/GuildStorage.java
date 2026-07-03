package com.skyblock.guild;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns every Guild in memory and persists them to a flat CSV file,
 * same simple-format approach as the rest of this project's storage
 * classes. Also maintains a member -> guild index so "what guild is
 * this player in" is an O(1) lookup instead of a scan.
 *
 * A player can only be in one guild at a time - GuildCommand enforces
 * that at the join/create call sites, this class just stores whatever
 * state it's given.
 */
public final class GuildStorage {

    private final File storageFile;
    private final Logger logger;
    private final Map<UUID, Guild> byId = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> guildIdByMember = new ConcurrentHashMap<>();

    public GuildStorage(File dataFolder, Logger logger) {
        this.logger = logger;
        File guildFolder = new File(dataFolder, "guilds");
        if (!guildFolder.exists()) {
            guildFolder.mkdirs();
        }
        this.storageFile = new File(guildFolder, "guilds.csv");
        load();
    }

    public Guild getById(UUID guildId) {
        return byId.get(guildId);
    }

    public Guild getByMember(UUID playerId) {
        UUID guildId = guildIdByMember.get(playerId);
        return guildId != null ? byId.get(guildId) : null;
    }

    /** Case-insensitive name lookup, since /guild join takes a typed name. */
    public Guild getByName(String name) {
        for (Guild guild : byId.values()) {
            if (guild.getName().equalsIgnoreCase(name)) {
                return guild;
            }
        }
        return null;
    }

    public Collection<Guild> all() {
        return byId.values();
    }

    public void save(Guild guild) {
        byId.put(guild.getId(), guild);
        reindexMembers(guild);
        saveAll();
    }

    public void delete(Guild guild) {
        byId.remove(guild.getId());
        for (UUID member : guild.getMembers()) {
            guildIdByMember.remove(member);
        }
        saveAll();
    }

    private void reindexMembers(Guild guild) {
        // Clear any stale entries pointing at this guild before
        // re-adding the current roster, in case someone left since the
        // last save.
        guildIdByMember.entrySet().removeIf(entry -> entry.getValue().equals(guild.getId()));
        for (UUID member : guild.getMembers()) {
            guildIdByMember.put(member, guild.getId());
        }
    }

    private void load() {
        if (!storageFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 5);
                if (parts.length < 5) continue;

                UUID id = UUID.fromString(parts[0]);
                String name = parts[1];
                UUID owner = UUID.fromString(parts[2]);
                long createdAt = Long.parseLong(parts[3]);

                Guild guild = new Guild(id, name, owner, createdAt);
                if (!parts[4].isBlank()) {
                    for (String memberStr : parts[4].split("\\|")) {
                        guild.addMember(UUID.fromString(memberStr));
                    }
                }
                byId.put(id, guild);
                reindexMembers(guild);
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warning("[Guild] Failed to load guilds file: " + e.getMessage());
        }
    }

    private synchronized void saveAll() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile))) {
            for (Guild guild : byId.values()) {
                StringBuilder members = new StringBuilder();
                for (UUID member : guild.getMembers()) {
                    if (members.length() > 0) members.append('|');
                    members.append(member);
                }
                writer.write(String.join(",",
                        guild.getId().toString(),
                        guild.getName(),
                        guild.getOwnerId().toString(),
                        String.valueOf(guild.getCreatedAt()),
                        members.toString()
                ));
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warning("[Guild] Failed to save guilds file: " + e.getMessage());
        }
    }
}
