package com.skyblock.dungeon.classes;

import com.skyblock.dungeon.progression.PlayerProgressionState;
import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /class                 - opens the class menu GUI directly (pick
 *                          screen if classless, info/switch screen if
 *                          you already have one).
 * /class choose <type>   - text-command equivalent of picking a class
 *                          from the GUI; goes through the same
 *                          ClassProgressionService rules (one class at
 *                          a time, level 10 + guild required for your
 *                          first pick, reset to level 0 on any pick).
 * /class skills          - text listing of your class's skills (the
 *                          GUI equivalent is /skill or /skills).
 * /class learn <skillId> - spends 1 unspent skill point to raise a
 *                          skill by 1 rank.
 */
public final class ClassCommand implements CommandExecutor {

    private final PlayerClassStorage classStorage;
    private final ClassSkillRegistry skillRegistry;
    private final PlayerProgressionStorage progressionStorage;
    private final ClassProgressionService progressionService;
    private final DungeonProgressionMenu menu;

    public ClassCommand(PlayerClassStorage classStorage, ClassSkillRegistry skillRegistry,
                         PlayerProgressionStorage progressionStorage, ClassProgressionService progressionService,
                         DungeonProgressionMenu menu) {
        this.classStorage = classStorage;
        this.skillRegistry = skillRegistry;
        this.progressionStorage = progressionStorage;
        this.progressionService = progressionService;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            menu.openClassMenu(player);
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

        ClassProgressionService.PickResult result = progressionService.pick(player.getUniqueId(), type);
        switch (result) {
            case OK -> player.sendMessage("§aYou are now a " + type.getColoredName()
                    + "§a! You've been reset to level §f0§a - equip a matching weapon and start grinding.");
            case ALREADY_THIS_CLASS -> player.sendMessage("§cYou're already a " + type.getColoredName() + "§c.");
            case LEVEL_TOO_LOW -> player.sendMessage("§cYou need character level §f"
                    + ClassProgressionService.MIN_LEVEL_FOR_FIRST_CLASS + "+ §cbefore picking your first class.");
            case NO_GUILD -> player.sendMessage("§cYou need to join or create a guild first - run §6/guild§c.");
        }
    }

    private void showSkills(Player player) {
        PlayerClassState state = classStorage.get(player.getUniqueId());
        if (!state.hasClass()) {
            player.sendMessage("§eChoose a class first with §6/class§e or §6/class choose <swordsman|scout|bowman>§e.");
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
        player.sendMessage("§7Run §6/class learn <skillId> §7to spend a point, or §6/skill §7for the GUI.");
    }

    private void handleLearn(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /class learn <skillId>");
            return;
        }
        PlayerClassState state = classStorage.get(player.getUniqueId());
        if (!state.hasClass()) {
            player.sendMessage("§eChoose a class first with §6/class§e.");
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
