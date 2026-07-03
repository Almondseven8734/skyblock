package com.skyblock.guild;

import com.skyblock.dungeon.drops.DungeonDropDefinition;
import com.skyblock.dungeon.drops.DungeonDropItemFactory;
import com.skyblock.shop.SellTrashSystem;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;

/**
 * /guild                       - shows your guild info (or a hint if you're not in one)
 * /guild create <name>         - creates a guild, you become the owner
 * /guild invite <player>       - invite an online player (owner only)
 * /guild accept                - accept your pending invite
 * /guild leave                 - leave your guild (owner leaving passes ownership on, or disbands if you were the last member)
 * /guild disband                - owner-only, deletes the guild outright
 * /guild info [name]           - shows a guild's roster
 * /guild list                  - lists every guild on the server
 * /guild sell                  - sells every recognized dungeon mob-drop item in your inventory to the guild merchant
 */
public final class GuildCommand implements CommandExecutor {

    private final GuildStorage guildStorage;
    private final GuildInviteManager inviteManager;
    private final DungeonDropItemFactory dropItemFactory;

    public GuildCommand(GuildStorage guildStorage, GuildInviteManager inviteManager,
                         DungeonDropItemFactory dropItemFactory) {
        this.guildStorage = guildStorage;
        this.inviteManager = inviteManager;
        this.dropItemFactory = dropItemFactory;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            showInfo(player, guildStorage.getByMember(player.getUniqueId()));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(player, args);
            case "invite" -> handleInvite(player, args);
            case "accept" -> handleAccept(player);
            case "leave" -> handleLeave(player);
            case "disband" -> handleDisband(player);
            case "info" -> handleInfoLookup(player, args);
            case "list" -> handleList(player);
            case "sell" -> handleSell(player);
            default -> player.sendMessage("§cUsage: /guild [create <name>|invite <player>|accept|leave|disband|info [name]|list|sell]");
        }
        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /guild create <name>");
            return;
        }
        if (guildStorage.getByMember(player.getUniqueId()) != null) {
            player.sendMessage("§cYou're already in a guild - leave it first with §6/guild leave§c.");
            return;
        }
        String name = args[1];
        if (name.contains(",") || name.length() > 24) {
            player.sendMessage("§cGuild names can't contain commas and must be 24 characters or fewer.");
            return;
        }
        if (guildStorage.getByName(name) != null) {
            player.sendMessage("§cA guild named §f" + name + " §calready exists.");
            return;
        }

        Guild guild = new Guild(UUID.randomUUID(), name, player.getUniqueId(), System.currentTimeMillis());
        guildStorage.save(guild);
        player.sendMessage("§aGuild §f" + name + " §acreated! You're the owner.");
    }

    private void handleInvite(Player player, String[] args) {
        Guild guild = requireOwnerGuild(player);
        if (guild == null) return;
        if (args.length < 2) {
            player.sendMessage("§cUsage: /guild invite <player>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage("§cThat player isn't online.");
            return;
        }
        if (guildStorage.getByMember(target.getUniqueId()) != null) {
            player.sendMessage("§c" + target.getName() + " is already in a guild.");
            return;
        }

        inviteManager.invite(target.getUniqueId(), guild.getId());
        player.sendMessage("§aInvited §f" + target.getName() + " §ato §f" + guild.getName() + "§a.");
        target.sendMessage("§6" + player.getName() + " §einvited you to join §f" + guild.getName()
            + "§e! Run §6/guild accept §ewithin 5 minutes to join.");
    }

    private void handleAccept(Player player) {
        if (guildStorage.getByMember(player.getUniqueId()) != null) {
            player.sendMessage("§cYou're already in a guild.");
            return;
        }
        UUID guildId = inviteManager.peekInvite(player.getUniqueId());
        if (guildId == null) {
            player.sendMessage("§cYou don't have a pending guild invite.");
            return;
        }
        Guild guild = guildStorage.getById(guildId);
        inviteManager.clearInvite(player.getUniqueId());
        if (guild == null) {
            player.sendMessage("§cThat guild no longer exists.");
            return;
        }

        guild.addMember(player.getUniqueId());
        guildStorage.save(guild);
        player.sendMessage("§aYou joined §f" + guild.getName() + "§a!");
    }

    private void handleLeave(Player player) {
        Guild guild = guildStorage.getByMember(player.getUniqueId());
        if (guild == null) {
            player.sendMessage("§cYou're not in a guild.");
            return;
        }

        UUID newOwner = guild.removeMember(player.getUniqueId());
        if (newOwner == null) {
            guildStorage.delete(guild);
            player.sendMessage("§7You left §f" + guild.getName() + "§7 - it had no members left, so it was disbanded.");
            return;
        }

        guildStorage.save(guild);
        player.sendMessage("§7You left §f" + guild.getName() + "§7.");
        if (newOwner.equals(guild.getOwnerId()) && !newOwner.equals(player.getUniqueId())) {
            Player newOwnerPlayer = Bukkit.getPlayer(newOwner);
            if (newOwnerPlayer != null) {
                newOwnerPlayer.sendMessage("§6You are now the owner of §f" + guild.getName() + "§6.");
            }
        }
    }

    private void handleDisband(Player player) {
        Guild guild = requireOwnerGuild(player);
        if (guild == null) return;

        guildStorage.delete(guild);
        for (UUID memberId : guild.getMembers()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null) {
                member.sendMessage("§c" + guild.getName() + " §7was disbanded by its owner.");
            }
        }
    }

    private void handleInfoLookup(Player player, String[] args) {
        Guild guild = args.length >= 2 ? guildStorage.getByName(args[1]) : guildStorage.getByMember(player.getUniqueId());
        showInfo(player, guild);
    }

    private void showInfo(Player player, Guild guild) {
        if (guild == null) {
            player.sendMessage("§eNo guild found. Run §6/guild create <name> §eor wait for an invite.");
            return;
        }
        player.sendMessage("§6§l" + guild.getName() + " §7(" + guild.getMembers().size() + " member"
            + (guild.getMembers().size() == 1 ? "" : "s") + ")");
        for (UUID memberId : guild.getMembers()) {
            String memberName = Bukkit.getOfflinePlayer(memberId).getName();
            String tag = memberId.equals(guild.getOwnerId()) ? " §6[Owner]" : "";
            player.sendMessage("§7- §f" + (memberName != null ? memberName : memberId) + tag);
        }
    }

    private void handleList(Player player) {
        if (guildStorage.all().isEmpty()) {
            player.sendMessage("§7No guilds exist yet. Be the first: §6/guild create <name>");
            return;
        }
        player.sendMessage("§6§lGuilds §7(" + guildStorage.all().size() + ")");
        for (Guild guild : guildStorage.all()) {
            player.sendMessage("§7- §f" + guild.getName() + " §7(" + guild.getMembers().size() + " members)");
        }
    }

    private void handleSell(Player player) {
        PlayerInventory inventory = player.getInventory();
        int totalEarned = 0;
        int itemsSold = 0;

        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            DungeonDropDefinition definition = dropItemFactory.resolve(stack);
            if (definition == null) continue;

            totalEarned += definition.getSellPrice() * stack.getAmount();
            itemsSold += stack.getAmount();
            contents[i] = null;
        }

        if (itemsSold == 0) {
            player.sendMessage("§7You don't have any mob drops the guild merchant will buy.");
            return;
        }

        inventory.setStorageContents(contents);
        SellTrashSystem.addPlayerMoney(player, totalEarned);
        player.sendMessage("§aSold §f" + itemsSold + " §adrop" + (itemsSold == 1 ? "" : "s") + " to the guild merchant for §6"
            + totalEarned + " coins§a. §7(Balance: §a$" + SellTrashSystem.getPlayerMoney(player) + "§7)");
    }

    private Guild requireOwnerGuild(Player player) {
        Guild guild = guildStorage.getByMember(player.getUniqueId());
        if (guild == null) {
            player.sendMessage("§cYou're not in a guild.");
            return null;
        }
        if (!guild.isOwner(player.getUniqueId())) {
            player.sendMessage("§cOnly the guild owner can do that.");
            return null;
        }
        return guild;
    }
}
