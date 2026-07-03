package com.skyblock.dungeon.classes;

import com.skyblock.dungeon.progression.PlayerProgressionState;
import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /class            - shows your class (or the pick menu if you have none)
 * /class choose <swordsman|scout|bowman> - one-time class pick
 * /class skills     - lists this class's skills, your rank, and cost
 * /class learn <skillId> - spends 1 unspent skill point to raise a skill by 1 rank
 */
public final class ClassCommand implements CommandExecutor {

    private final PlayerClassStorage classStorage;
    private final ClassSkillRegistry skillRegistry;
    private final PlayerProgressionStorage progressionStorage;

    public ClassCommand(PlayerClassStorage classStorage, ClassSkillRegistry skillRegistry,
                         PlayerProgressionStorage progressionStorage) {
        this.classStorage = classStorage;
        this.skillRegistry = skillRegistry;
        this.progressionStorage = progressionStorage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            showStatus(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "choose" -> handleChoose(player, args);
            case "skills" -> showSkills(player);
            case "learn" -> handleLearn(player, args);
            default -> sender.sendMessage("§cUsage: /class [choose <swordsman|scout|bowman>|skills|learn <skillId>]");
        }
        return true;
    }

    private void showStatus(Player player) {
        PlayerClassState state = classStorage.get(player.getUniqueId());
        if (!state.hasClass()) {
            player.sendMessage("§eYou haven't chosen a class yet. Run §6/class choose <swordsman|scout|bowman>§e.");
            return;
        }
        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());
        player.sendMessage("§7You are a " + state.getClassType().getColoredName()
            + " §7(§f" + progression.getUnspentSkillPoints() + " unspent skill point"
            + (progression.getUnspentSkillPoints() == 1 ? "" : "s") + "§7). Run §6/class skills §7to see your skills.");
    }

    private void handleChoose(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /class choose <swordsman|scout|bowman>");
            return;
        }
        PlayerClassType type;
        try {
            type = PlayerClassType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage("§cUnknown class. Choose one of: swordsman, scout, bowman.");
            return;
        }

        PlayerClassState state = classStorage.get(player.getUniqueId());
        if (state.hasClass()) {
            player.sendMessage("§cYou're already a " + state.getClassType().getColoredName()
                + "§c - class choice is permanent.");
            return;
        }

        state.chooseClass(type);
        classStorage.persist(player.getUniqueId(), state);
        player.sendMessage("§aYou are now a " + type.getColoredName()
            + "§a! Equip a matching weapon and right-click to use your skills once learned.");
    }

    private void showSkills(Player player) {
        PlayerClassState state = classStorage.get(player.getUniqueId());
        if (!state.hasClass()) {
            player.sendMessage("§eChoose a class first with §6/class choose <swordsman|scout|bowman>§e.");
            return;
        }
        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());
        List<ClassSkill> skills = skillRegistry.forClass(state.getClassType());

        player.sendMessage("§6§l" + state.getClassType().getDisplayName() + " Skills "
            + "§7(" + progression.getUnspentSkillPoints() + " points unspent)");
        for (ClassSkill skill : skills) {
            int rank = state.getRank(skill.getId());
            player.sendMessage(String.format("§e%s §7[%d/%d] §f- %s §7(§6%d pt/rank§7, id: §f%s§7)",
                skill.getDisplayName(), rank, skill.getMaxRank(), skill.getDescription(),
                skill.getPointCostPerRank(), skill.getId()));
        }
        player.sendMessage("§7Run §6/class learn <skillId> §7to spend a point.");
    }

    private void handleLearn(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /class learn <skillId>");
            return;
        }
        PlayerClassState state = classStorage.get(player.getUniqueId());
        if (!state.hasClass()) {
            player.sendMessage("§eChoose a class first with §6/class choose <swordsman|scout|bowman>§e.");
            return;
        }

        ClassSkill skill = skillRegistry.get(args[1]);
        if (skill == null || skill.getClassType() != state.getClassType()) {
            player.sendMessage("§cUnknown skill for your class. Run §6/class skills §cto see valid ids.");
            return;
        }

        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());
        int newRank = state.investPoint(skill, progression);
        if (newRank < 0) {
            player.sendMessage("§cCan't learn that right now - either it's maxed or you're out of skill points.");
            return;
        }

        classStorage.persist(player.getUniqueId(), state);
        progressionStorage.persist(player.getUniqueId(), progression);
        player.sendMessage("§a" + skill.getDisplayName() + " is now rank §6" + newRank
            + "§a/§6" + skill.getMaxRank() + "§a.");
    }
}
