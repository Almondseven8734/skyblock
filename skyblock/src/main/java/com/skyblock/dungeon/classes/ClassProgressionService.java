package com.skyblock.dungeon.classes;

import com.skyblock.dungeon.progression.PlayerProgressionState;
import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import com.skyblock.guild.GuildStorage;

import java.util.UUID;

/**
 * Shared business logic for picking/switching a dungeon class, used by
 * both ClassCommand's text subcommands and DungeonProgressionMenu's
 * GUI so the two never drift out of sync on the actual rules:
 *
 *  - Exactly one class at a time. Switching to a different class wipes
 *    ALL levels/XP/skill points back to zero and clears every learned
 *    skill rank (see PlayerClassState.setClass /
 *    PlayerProgressionState.resetToZero) - a class is a full build,
 *    not a set of side-graded loadouts you can swap for free.
 *  - The FIRST-EVER class pick additionally requires character level
 *    10+ AND guild membership (create or join one first). Re-picking
 *    after a later switch does not re-check the level bar - the
 *    player is about to be reset to level 0 by the switch anyway, so
 *    testing their about-to-be-wiped level against the bar would be
 *    meaningless - but it still requires them to currently be in a
 *    guild every time.
 *  - Every successful pick (first or switch) resets the player to
 *    level 0, per design ("you start at lvl 0 again").
 */
public final class ClassProgressionService {

    public static final int MIN_LEVEL_FOR_FIRST_CLASS = 10;

    public enum PickResult {
        OK,
        ALREADY_THIS_CLASS,
        LEVEL_TOO_LOW,
        NO_GUILD
    }

    private final PlayerClassStorage classStorage;
    private final PlayerProgressionStorage progressionStorage;
    private final GuildStorage guildStorage;

    public ClassProgressionService(PlayerClassStorage classStorage, PlayerProgressionStorage progressionStorage,
                                    GuildStorage guildStorage) {
        this.classStorage = classStorage;
        this.progressionStorage = progressionStorage;
        this.guildStorage = guildStorage;
    }

    public boolean hasGuild(UUID playerId) {
        return guildStorage.getByMember(playerId) != null;
    }

    public boolean meetsFirstPickLevel(UUID playerId) {
        return progressionStorage.get(playerId).getCharacterLevel() >= MIN_LEVEL_FOR_FIRST_CLASS;
    }

    /** True if this would be this player's very first class pick (currently classless). */
    public boolean isFirstPick(UUID playerId) {
        return !classStorage.get(playerId).hasClass();
    }

    /**
     * Attempts to set (or switch to) a class, applying every gate
     * above. On PickResult.OK, the class state and progression state
     * have already been mutated and persisted - the caller only needs
     * to message the player and refresh any open UI.
     */
    public PickResult pick(UUID playerId, PlayerClassType type) {
        PlayerClassState classState = classStorage.get(playerId);
        if (classState.hasClass() && classState.getClassType() == type) {
            return PickResult.ALREADY_THIS_CLASS;
        }
        if (!hasGuild(playerId)) {
            return PickResult.NO_GUILD;
        }
        if (isFirstPick(playerId) && !meetsFirstPickLevel(playerId)) {
            return PickResult.LEVEL_TOO_LOW;
        }

        classState.setClass(type);
        classStorage.persist(playerId, classState);

        PlayerProgressionState progression = progressionStorage.get(playerId);
        progression.resetToZero();
        progressionStorage.persist(playerId, progression);

        return PickResult.OK;
    }
}
