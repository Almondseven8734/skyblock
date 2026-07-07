package com.skyblock.party;

import com.skyblock.util.GuiBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Party System — 4-slot parties with a leader, invite-by-chat-prompt
 * menu, leader transfer, and slot-level add/remove for the current
 * leader.
 *
 * Commands:
 *   /party                         — opens the party panel GUI
 *   /party invite <player>         — invite a player (alias: /party add)
 *   /party kick <player>           — remove a player (alias: /party remove)
 *   /party leave                   — leave your current party (aliases: exit, escape)
 *
 * GUI:
 *   - 4 member slots. An empty slot shows a plain "Empty Slot - click
 *     to invite" item; clicking it prompts the player in chat to type
 *     a username, same pattern as IslandMenu's chat-prompt flow
 *     (SET_WARP/RENAME) - AsyncPlayerChatEvent + a per-player input-mode
 *     map, 'cancel' to abort.
 *   - A claimed slot shows that member's real player head (SkullMeta
 *     setOwningPlayer, actual skin - not a plain PLAYER_HEAD icon) and
 *     their username as the display name.
 *   - Clicking a claimed slot as the party leader opens a submenu with
 *     "Make Leader" and "Remove from Party" buttons. Non-leaders
 *     clicking a claimed slot just see member info (no action) except
 *     for their own slot, which offers "Leave Party".
 *   - Only the leader gets add/remove actions; a plain member clicking
 *     an empty slot is told only the leader can invite.
 *
 * State is purely in-memory (Map<UUID, Party>), matching TpaSystem's
 * pattern elsewhere in this codebase - parties don't need to survive
 * a server restart per the existing precedent for ephemeral
 * multiplayer state (TPA requests, GUI sessions).
 */
public final class PartySystem implements CommandExecutor, Listener {

    public static final int MAX_MEMBERS = 4;

    private static final String GUI_TITLE = "§l§bParty";
    private static final String GUI_TITLE_MANAGE_PREFIX = "§l§6Manage: ";

    private final JavaPlugin plugin;

    public PartySystem(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** One party. members[0] is always the leader's UUID; other slots may be null (empty). */
    public static final class Party {
        final UUID[] members = new UUID[MAX_MEMBERS];

        UUID leader() { return members[0]; }

        int size() {
            int count = 0;
            for (UUID id : members) if (id != null) count++;
            return count;
        }

        boolean contains(UUID id) {
            for (UUID m : members) if (id.equals(m)) return true;
            return false;
        }

        int firstEmptySlot() {
            for (int i = 0; i < members.length; i++) if (members[i] == null) return i;
            return -1;
        }

        int slotOf(UUID id) {
            for (int i = 0; i < members.length; i++) if (id.equals(members[i])) return i;
            return -1;
        }
    }

    private enum InputMode { NONE, INVITE }

    /** playerId -> the Party they currently belong to. Every member (including the leader) has an entry pointing at the same Party object. */
    private final Map<UUID, Party> partyOf = new HashMap<>();
    /** playerId -> chat input mode, mirrors IslandMenu's inputMode map. */
    private final Map<UUID, InputMode> inputMode = new HashMap<>();

    // =========================================================================
    // COMMAND
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /party.");
            return true;
        }

        if (args.length == 0) {
            openPartyMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "invite", "add" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /party invite <player>");
                    return true;
                }
                invitePlayer(player, args[1]);
            }
            case "kick", "remove" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /party kick <player>");
                    return true;
                }
                kickPlayer(player, args[1]);
            }
            case "leave", "exit", "escape" -> leaveParty(player);
            default -> player.sendMessage("§cUnknown /party subcommand. Try /party, /party invite <player>, /party kick <player>, or /party leave.");
        }
        return true;
    }

    // =========================================================================
    // PARTY MUTATIONS (shared by both command and GUI paths)
    // =========================================================================

    private Party getOrCreateParty(Player leader) {
        Party party = partyOf.get(leader.getUniqueId());
        if (party != null) return party;
        party = new Party();
        party.members[0] = leader.getUniqueId();
        partyOf.put(leader.getUniqueId(), party);
        return party;
    }

    private void invitePlayer(Player inviter, String targetName) {
        Party party = getOrCreateParty(inviter);
        if (!inviter.getUniqueId().equals(party.leader())) {
            inviter.sendMessage("§cOnly the party leader can invite players.");
            return;
        }
        if (party.size() >= MAX_MEMBERS) {
            inviter.sendMessage("§cYour party is full (" + MAX_MEMBERS + "/" + MAX_MEMBERS + ").");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            inviter.sendMessage("§cPlayer '" + targetName + "' is not online.");
            return;
        }
        if (target.getUniqueId().equals(inviter.getUniqueId())) {
            inviter.sendMessage("§cYou can't invite yourself.");
            return;
        }
        if (party.contains(target.getUniqueId())) {
            inviter.sendMessage("§cThat player is already in your party.");
            return;
        }
        Party targetParty = partyOf.get(target.getUniqueId());
        if (targetParty != null) {
            inviter.sendMessage("§c" + target.getName() + " is already in a party.");
            return;
        }

        int slot = party.firstEmptySlot();
        if (slot < 0) {
            inviter.sendMessage("§cYour party is full.");
            return;
        }
        party.members[slot] = target.getUniqueId();
        partyOf.put(target.getUniqueId(), party);

        inviter.sendMessage("§a" + target.getName() + " has joined your party!");
        target.sendMessage("§aYou've been added to " + inviter.getName() + "'s party!");
    }

    private void kickPlayer(Player leaderPlayer, String targetName) {
        Party party = partyOf.get(leaderPlayer.getUniqueId());
        if (party == null || !leaderPlayer.getUniqueId().equals(party.leader())) {
            leaderPlayer.sendMessage("§cYou must be a party leader to kick someone.");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetId = target.getUniqueId();
        int slot = party.slotOf(targetId);
        if (slot <= 0) { // 0 is the leader's own slot - can't kick self via kick
            leaderPlayer.sendMessage("§cThat player isn't in your party.");
            return;
        }
        removeFromSlot(party, slot, leaderPlayer);
    }

    private void removeFromSlot(Party party, int slot, Player actingLeader) {
        UUID removedId = party.members[slot];
        if (removedId == null) return;
        party.members[slot] = null;
        partyOf.remove(removedId);

        OfflinePlayer removed = Bukkit.getOfflinePlayer(removedId);
        actingLeader.sendMessage("§c" + removed.getName() + " has been removed from the party.");
        Player removedOnline = Bukkit.getPlayer(removedId);
        if (removedOnline != null) {
            removedOnline.sendMessage("§cYou've been removed from " + actingLeader.getName() + "'s party.");
        }
    }

    private void leaveParty(Player player) {
        Party party = partyOf.get(player.getUniqueId());
        if (party == null) {
            player.sendMessage("§cYou're not in a party.");
            return;
        }
        int slot = party.slotOf(player.getUniqueId());
        boolean wasLeader = slot == 0;
        party.members[slot] = null;
        partyOf.remove(player.getUniqueId());
        player.sendMessage("§7You left the party.");

        if (wasLeader) {
            // Promote the next filled slot (if any) to leader.
            for (int i = 1; i < party.members.length; i++) {
                if (party.members[i] != null) {
                    promoteToLeader(party, i, null);
                    break;
                }
            }
        }
    }

    /** Swaps the member at `slot` into slot 0 (leader). Announces to online members; announcer may be null (e.g. auto-promotion on leave). */
    private void promoteToLeader(Party party, int slot, Player announcer) {
        UUID oldLeader = party.members[0];
        UUID newLeader = party.members[slot];
        party.members[0] = newLeader;
        party.members[slot] = oldLeader; // old leader (if still present) takes the vacated slot; if null, slot stays empty

        OfflinePlayer newLeaderOffline = Bukkit.getOfflinePlayer(newLeader);
        for (UUID memberId : party.members) {
            if (memberId == null) continue;
            Player online = Bukkit.getPlayer(memberId);
            if (online != null) {
                online.sendMessage("§e" + newLeaderOffline.getName() + " is now the party leader.");
            }
        }
    }

    // =========================================================================
    // GUI - MAIN PANEL
    // =========================================================================

    private void openPartyMenu(Player player) {
        Party party = partyOf.get(player.getUniqueId());
        boolean inParty = party != null;
        boolean isLeader = inParty && player.getUniqueId().equals(party.leader());

        GuiBuilder gui = GuiBuilder.row(GUI_TITLE);
        gui.fillEmpty(Material.BLACK_STAINED_GLASS_PANE);

        // Slots 2,3,4,5 are the 4 party-member slots in a 9-slot row (slots 0/1 and 6/7/8 are decorative border/leave button).
        int[] memberSlots = {2, 3, 4, 5};

        if (!inParty) {
            // Not in a party yet - clicking any member slot starts a fresh party led by you and opens the invite prompt.
            for (int slotIndex : memberSlots) {
                gui.button(slotIndex, Material.LIME_STAINED_GLASS_PANE, "§aEmpty Slot",
                        List.of("§7Click to invite a player", "§7(this will start a new party)"),
                        clicked -> {
                            getOrCreateParty(player);
                            promptInvite(player);
                        });
            }
        } else {
            for (int i = 0; i < memberSlots.length; i++) {
                UUID memberId = party.members[i];
                int slotNum = i;
                if (memberId == null) {
                    if (isLeader) {
                        gui.button(memberSlots[i], Material.LIME_STAINED_GLASS_PANE, "§aEmpty Slot",
                                List.of("§7Click to invite a player"),
                                clicked -> promptInvite(player));
                    } else {
                        gui.set(memberSlots[i], Material.GRAY_STAINED_GLASS_PANE, "§7Empty Slot",
                                List.of("§7Only the leader can invite"));
                    }
                } else {
                    OfflinePlayer member = Bukkit.getOfflinePlayer(memberId);
                    boolean memberIsLeader = slotNum == 0;
                    boolean isSelf = memberId.equals(player.getUniqueId());
                    List<String> lore = new ArrayList<>();
                    lore.add(memberIsLeader ? "§6★ Party Leader" : "§7Member");
                    if (isLeader && !isSelf) {
                        lore.add("");
                        lore.add("§eClick to manage");
                    } else if (isSelf && !memberIsLeader) {
                        lore.add("");
                        lore.add("§cClick to leave party");
                    }
                    ItemStack head = buildPlayerHead(member, (memberIsLeader ? "§6" : "§f") + member.getName(), lore);
                    gui.button(memberSlots[i], head, clicked -> {
                        if (isLeader && !isSelf) {
                            openManageSubmenu(player, memberId);
                        } else if (isSelf && !memberIsLeader) {
                            player.closeInventory();
                            leaveParty(player);
                        }
                        // Leader clicking their own slot, or a non-leader clicking someone else's: no action.
                    });
                }
            }
        }

        if (inParty) {
            gui.button(7, Material.BARRIER, "§cLeave Party", List.of("§7Exit your current party"),
                    clicked -> { player.closeInventory(); leaveParty(player); });
        }
        gui.button(8, Material.OAK_DOOR, "§7Close", List.of(), clicked -> player.closeInventory());

        gui.open(player, "party_main");
    }

    private ItemStack buildPlayerHead(OfflinePlayer owner, String displayName, List<String> lore) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD, 1);
        if (skull.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            meta.setDisplayName(displayName);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            skull.setItemMeta(meta);
        }
        return skull;
    }

    // =========================================================================
    // GUI - MANAGE SUBMENU (leader only, for a specific non-self member)
    // =========================================================================

    private void openManageSubmenu(Player leaderPlayer, UUID targetId) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);

        GuiBuilder gui = GuiBuilder.row(GUI_TITLE_MANAGE_PREFIX + target.getName());
        gui.fillEmpty(Material.BLACK_STAINED_GLASS_PANE);

        gui.set(2, buildPlayerHead(target, "§f" + target.getName(), List.of()));
        gui.button(4, Material.NETHER_STAR, "§6Make Leader",
                List.of("§7Transfer party leadership", "§7to " + target.getName()),
                clicked -> {
                    // Re-resolve the party/slot at click time in case state shifted between menu open and click.
                    Party current = partyOf.get(leaderPlayer.getUniqueId());
                    if (current == null || !leaderPlayer.getUniqueId().equals(current.leader())) {
                        leaderPlayer.closeInventory();
                        leaderPlayer.sendMessage("§cYou're no longer the party leader.");
                        return;
                    }
                    int slot = current.slotOf(targetId);
                    if (slot <= 0) {
                        leaderPlayer.closeInventory();
                        leaderPlayer.sendMessage("§cThat player is no longer in your party.");
                        return;
                    }
                    promoteToLeader(current, slot, leaderPlayer);
                    leaderPlayer.closeInventory();
                    openPartyMenu(leaderPlayer);
                });
        gui.button(6, Material.BARRIER, "§cRemove from Party",
                List.of("§7Kick " + target.getName() + " from the party"),
                clicked -> {
                    Party current = partyOf.get(leaderPlayer.getUniqueId());
                    if (current == null || !leaderPlayer.getUniqueId().equals(current.leader())) {
                        leaderPlayer.closeInventory();
                        leaderPlayer.sendMessage("§cYou're no longer the party leader.");
                        return;
                    }
                    int slot = current.slotOf(targetId);
                    if (slot <= 0) {
                        leaderPlayer.closeInventory();
                        leaderPlayer.sendMessage("§cThat player is no longer in your party.");
                        return;
                    }
                    removeFromSlot(current, slot, leaderPlayer);
                    leaderPlayer.closeInventory();
                    openPartyMenu(leaderPlayer);
                });
        gui.button(8, Material.ARROW, "§7Back", List.of(), clicked -> {
            leaderPlayer.closeInventory();
            openPartyMenu(leaderPlayer);
        });

        gui.open(leaderPlayer, "party_manage");
    }

    // =========================================================================
    // CHAT PROMPT (invite by typed username)
    // =========================================================================

    private void promptInvite(Player player) {
        player.closeInventory();
        player.sendMessage("§aType the username of the player you want to invite in chat (or 'cancel' to abort):");
        inputMode.put(player.getUniqueId(), InputMode.INVITE);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        InputMode mode = inputMode.get(player.getUniqueId());
        if (mode == null || mode == InputMode.NONE) return;

        event.setCancelled(true);
        inputMode.remove(player.getUniqueId());
        String input = event.getMessage().trim();

        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7Cancelled.");
            return;
        }

        if (mode == InputMode.INVITE) {
            plugin.getServer().getScheduler().runTask(plugin, () -> invitePlayer(player, input));
        }
    }

    // =========================================================================
    // CLEANUP
    // =========================================================================

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        inputMode.remove(player.getUniqueId());
        // Deliberately NOT removing the player from their party here - a
        // disconnect shouldn't silently kick someone out of their party
        // (they may just be reconnecting); party membership only changes
        // via explicit /party leave, /party kick, or being replaced by a
        // fresh invite once their slot is intentionally freed.
    }

    /** Exposes a player's current party, or null - for other systems (e.g. dungeon party-join gating) to query membership. */
    public Party getParty(UUID playerId) {
        return partyOf.get(playerId);
    }
}
