package com.skyblock.dungeon.classes;

import com.skyblock.dungeon.progression.CharacterLevelCurve;
import com.skyblock.dungeon.progression.PlayerProgressionState;
import com.skyblock.dungeon.progression.PlayerProgressionStorage;
import com.skyblock.util.GuiBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Chest-GUI front end for the level/skill/class system, built on the
 * shared GuiBuilder framework the same way ShopSystem/SellTrashSystem/
 * IslandMenu are - three linked screens:
 *
 *   LEVEL HUB   - /level. Shows current level/XP/skill points, with
 *                 buttons into the skill tree and the class screen.
 *   SKILL TREE  - /skill, /skills, or the hub's "Skill Tree" button.
 *                 Lists the player's class skills; click a skill to
 *                 spend a point raising its rank.
 *   CLASS MENU  - /class, or the hub's "Class" button. Shows the
 *                 player's current class (if any) with a switch-class
 *                 flow, or the pick screen (with level/guild gates)
 *                 if they don't have one yet.
 *
 * All the actual pick/switch rules live in ClassProgressionService -
 * this class only renders state and calls into it.
 */
public final class DungeonProgressionMenu {

    private static final String TITLE_HUB = "§b§lYour Progression";
    private static final String TITLE_SKILLS = "§d§lSkill Tree";
    private static final String TITLE_CLASS_PICK = "§6§lChoose Your Class";
    private static final String TITLE_CLASS_INFO = "§6§lYour Class";
    private static final String TITLE_SWITCH_CONFIRM = "§c§lSwitch Class?";

    private final PlayerClassStorage classStorage;
    private final ClassSkillRegistry skillRegistry;
    private final PlayerProgressionStorage progressionStorage;
    private final ClassProgressionService progressionService;

    public DungeonProgressionMenu(PlayerClassStorage classStorage, ClassSkillRegistry skillRegistry,
                                   PlayerProgressionStorage progressionStorage,
                                   ClassProgressionService progressionService) {
        this.classStorage = classStorage;
        this.skillRegistry = skillRegistry;
        this.progressionStorage = progressionStorage;
        this.progressionService = progressionService;
    }

    // =========================================================================
    // LEVEL HUB
    // =========================================================================

    public void openLevelHub(Player player) {
        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());
        PlayerClassState classState = classStorage.get(player.getUniqueId());

        long xpToNext = CharacterLevelCurve.xpToNextLevel(progression.getCharacterLevel());
        int percent = (int) Math.round(progression.progressFraction() * 100);

        List<String> levelLore = new ArrayList<>();
        levelLore.add("");
        if (progression.getCharacterLevel() >= CharacterLevelCurve.MAX_LEVEL) {
            levelLore.add("§7Max level reached!");
        } else {
            levelLore.add("§7XP: §f" + progression.getCurrentXp() + " §7/ §f" + xpToNext + " §7(§a" + percent + "%§7)");
        }
        levelLore.add("§7Unspent skill points: §e" + progression.getUnspentSkillPoints());
        levelLore.add("§7Lifetime skill points earned: §e" + progression.getTotalSkillPointsEarned());
        levelLore.add("");
        levelLore.add(classState.hasClass()
                ? "§7Class: " + classState.getClassType().getColoredName()
                : "§7Class: §8None chosen yet");

        GuiBuilder.large(TITLE_HUB)
                .fill(Material.BLACK_STAINED_GLASS_PANE)
                .set(13, Material.NETHER_STAR, "§b§lLevel " + progression.getCharacterLevel(), levelLore)
                .button(29, Material.ENCHANTED_BOOK, "§d§lSkill Tree",
                        List.of("", "§7View and spend skill points", "§7on your class's skills.", "",
                                "§eClick to open!"),
                        slot -> openSkillTree(player))
                .button(33, classIcon(classState), "§6§lClass",
                        classState.hasClass()
                                ? List.of("", "§7You are a " + classState.getClassType().getColoredName() + "§7.",
                                    "", "§eClick to manage your class!")
                                : List.of("", "§7You haven't chosen a class yet.", "", "§eClick to choose one!"),
                        slot -> openClassMenu(player))
                .closeButton(slot -> player.closeInventory())
                .open(player);
    }

    private Material classIcon(PlayerClassState classState) {
        if (!classState.hasClass()) {
            return Material.BOOK;
        }
        return switch (classState.getClassType()) {
            case SWORDSMAN -> Material.IRON_SWORD;
            case SCOUT -> Material.IRON_AXE;
            case BOWMAN -> Material.BOW;
        };
    }

    // =========================================================================
    // SKILL TREE
    // =========================================================================

    public void openSkillTree(Player player) {
        PlayerClassState classState = classStorage.get(player.getUniqueId());

        if (!classState.hasClass()) {
            GuiBuilder.large(TITLE_SKILLS)
                    .fill(Material.BLACK_STAINED_GLASS_PANE)
                    .set(22, Material.BARRIER, "§cNo class chosen",
                            List.of("", "§7Choose a class first to unlock", "§7its skill tree.", "",
                                    "§eClick to open the class menu!"))
                    .handle(22, slot -> openClassMenu(player))
                    .button(45, Material.NETHER_STAR, "§b← Level Hub", slot -> openLevelHub(player))
                    .closeButton(slot -> player.closeInventory())
                    .open(player);
            return;
        }

        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());
        List<ClassSkill> skills = skillRegistry.forClass(classState.getClassType());

        GuiBuilder gui = GuiBuilder.large(TITLE_SKILLS)
                .fill(Material.BLACK_STAINED_GLASS_PANE)
                .set(4, Material.EXPERIENCE_BOTTLE, "§e" + progression.getUnspentSkillPoints() + " unspent skill point"
                        + (progression.getUnspentSkillPoints() == 1 ? "" : "s"),
                        List.of("", "§7Click a skill below to spend", "§7a point raising its rank."));

        int[] slots = {19, 21, 23, 25, 28, 30, 32, 34};
        int i = 0;
        for (ClassSkill skill : skills) {
            if (i >= slots.length) break;
            int slot = slots[i++];
            int rank = classState.getRank(skill.getId());
            boolean maxed = rank >= skill.getMaxRank();

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7" + skill.getDescription());
            lore.add("");
            lore.add("§7Rank: §f" + rank + "§7/§f" + skill.getMaxRank());
            lore.add("§7Cost: §6" + skill.getPointCostPerRank() + " point" + (skill.getPointCostPerRank() == 1 ? "" : "s")
                    + " per rank");
            lore.add("");
            lore.add(maxed ? "§aMaxed out!" : "§eClick to learn/upgrade!");

            Material icon = maxed ? Material.ENCHANTED_BOOK : Material.BOOK;
            gui.button(slot, icon, (maxed ? "§a" : "§e") + skill.getDisplayName(), lore, slot2 -> {
                int newRank = classState.investPoint(skill, progression);
                if (newRank < 0) {
                    player.sendMessage("§cCan't learn that right now - either it's maxed or you're out of skill points.");
                } else {
                    classStorage.persist(player.getUniqueId(), classState);
                    progressionStorage.persist(player.getUniqueId(), progression);
                    player.sendMessage("§a" + skill.getDisplayName() + " is now rank §6" + newRank
                            + "§a/§6" + skill.getMaxRank() + "§a.");
                }
                openSkillTree(player); // refresh with updated ranks/points
            });
        }

        gui.button(45, Material.NETHER_STAR, "§b← Level Hub", slot -> openLevelHub(player));
        gui.closeButton(slot -> player.closeInventory());
        gui.open(player);
    }

    // =========================================================================
    // CLASS MENU
    // =========================================================================

    public void openClassMenu(Player player) {
        PlayerClassState classState = classStorage.get(player.getUniqueId());
        if (classState.hasClass()) {
            openClassInfo(player, classState);
        } else {
            openClassPicker(player);
        }
    }

    private void openClassInfo(Player player, PlayerClassState classState) {
        PlayerProgressionState progression = progressionStorage.get(player.getUniqueId());

        GuiBuilder.large(TITLE_CLASS_INFO)
                .fill(Material.BLACK_STAINED_GLASS_PANE)
                .set(13, classIcon(classState), classState.getClassType().getColoredName(),
                        List.of("", "§7Level: §f" + progression.getCharacterLevel(),
                                "§7Unspent skill points: §e" + progression.getUnspentSkillPoints()))
                .button(29, Material.ENCHANTED_BOOK, "§d§lSkill Tree", slot -> openSkillTree(player))
                .button(33, Material.BARRIER, "§c§lSwitch Class",
                        List.of("", "§cSwitching resets your level to §f0",
                                "§cand erases every learned skill.", "", "§eClick to continue..."),
                        slot -> openSwitchConfirm(player))
                .button(45, Material.NETHER_STAR, "§b← Level Hub", slot -> openLevelHub(player))
                .closeButton(slot -> player.closeInventory())
                .open(player);
    }

    private void openSwitchConfirm(Player player) {
        GuiBuilder.row(TITLE_SWITCH_CONFIRM)
                .fill(Material.RED_STAINED_GLASS_PANE)
                .button(3, Material.LIME_WOOL, "§a§lYes, switch",
                        List.of("§7You'll be reset to level 0", "§7and can immediately pick a new class."),
                        slot -> openClassPicker(player))
                .button(5, Material.RED_WOOL, "§c§lNo, stay",
                        slot -> openClassInfo(player, classStorage.get(player.getUniqueId())))
                .open(player);
    }

    private void openClassPicker(Player player) {
        boolean firstPick = progressionService.isFirstPick(player.getUniqueId());
        boolean hasGuild = progressionService.hasGuild(player.getUniqueId());
        boolean meetsLevel = !firstPick || progressionService.meetsFirstPickLevel(player.getUniqueId());
        boolean locked = firstPick && (!hasGuild || !meetsLevel);

        GuiBuilder gui = GuiBuilder.large(TITLE_CLASS_PICK).fill(Material.BLACK_STAINED_GLASS_PANE);

        if (locked) {
            List<String> lockLore = new ArrayList<>();
            lockLore.add("");
            if (!meetsLevel) {
                lockLore.add("§c✗ Requires character level §f"
                        + ClassProgressionService.MIN_LEVEL_FOR_FIRST_CLASS + "+");
            } else {
                lockLore.add("§a✓ Character level requirement met");
            }
            if (!hasGuild) {
                lockLore.add("§c✗ Requires joining or creating a guild");
            } else {
                lockLore.add("§a✓ Guild requirement met");
            }
            gui.set(22, Material.BARRIER, "§cClass selection locked", lockLore);
        } else {
            gui.set(11, Material.IRON_SWORD, PlayerClassType.SWORDSMAN.getColoredName(),
                    List.of("", "§7A frontline brawler built around", "§7sword skills - whirlwind strikes",
                            "§7and self-sustain.", "", "§eClick to choose!"));
            gui.handle(11, slot -> handlePick(player, PlayerClassType.SWORDSMAN));

            gui.set(13, Material.IRON_AXE, PlayerClassType.SCOUT.getColoredName(),
                    List.of("", "§7A fast axe-wielding skirmisher", "§7built around mobility and evasion.", "",
                            "§eClick to choose!"));
            gui.handle(13, slot -> handlePick(player, PlayerClassType.SCOUT));

            gui.set(15, Material.BOW, PlayerClassType.BOWMAN.getColoredName(),
                    List.of("", "§7A ranged specialist built around", "§7bow skills - volleys and power shots.",
                            "", "§eClick to choose!"));
            gui.handle(15, slot -> handlePick(player, PlayerClassType.BOWMAN));
        }

        gui.button(45, Material.NETHER_STAR, "§b← Level Hub", slot -> openLevelHub(player));
        gui.closeButton(slot -> player.closeInventory());
        gui.open(player);
    }

    private void handlePick(Player player, PlayerClassType type) {
        ClassProgressionService.PickResult result = progressionService.pick(player.getUniqueId(), type);
        switch (result) {
            case OK -> {
                player.sendMessage("§aYou are now a " + type.getColoredName()
                        + "§a! You've been reset to level §f0§a - equip a matching weapon and start grinding.");
                openClassInfo(player, classStorage.get(player.getUniqueId()));
            }
            case ALREADY_THIS_CLASS -> {
                player.sendMessage("§cYou're already a " + type.getColoredName() + "§c.");
                openClassInfo(player, classStorage.get(player.getUniqueId()));
            }
            case LEVEL_TOO_LOW -> {
                player.sendMessage("§cYou need character level §f" + ClassProgressionService.MIN_LEVEL_FOR_FIRST_CLASS
                        + "+ §cbefore picking your first class.");
                openClassPicker(player);
            }
            case NO_GUILD -> {
                player.sendMessage("§cYou need to join or create a guild first - run §6/guild§c.");
                openClassPicker(player);
            }
        }
    }
}
