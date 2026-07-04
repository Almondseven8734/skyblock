package com.skyblock.dungeon.classes;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /skill (alias /skills) - opens the skill tree GUI directly, skipping
 * the level hub. Shows a "no class chosen" screen with a shortcut into
 * the class menu if the player doesn't have a class yet.
 */
public final class SkillCommand implements CommandExecutor {

    private final DungeonProgressionMenu menu;

    public SkillCommand(DungeonProgressionMenu menu) {
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        menu.openSkillTree(player);
        return true;
    }
}
