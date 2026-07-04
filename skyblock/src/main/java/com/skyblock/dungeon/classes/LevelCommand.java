package com.skyblock.dungeon.classes;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /level - opens the progression hub GUI (current level/XP/skill
 * points, with buttons into the skill tree and class menus). The
 * chest-GUI equivalent of the old /class status text output, but as
 * the primary entry point per design ("/level which takes you to a
 * skills menu similar to chest shop").
 */
public final class LevelCommand implements CommandExecutor {

    private final DungeonProgressionMenu menu;

    public LevelCommand(DungeonProgressionMenu menu) {
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        menu.openLevelHub(player);
        return true;
    }
}
