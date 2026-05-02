package com.p2wn.diary.item;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;

public final class WelcomeBookItem {

    public ItemStack createWelcomeBook() {
        ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
        if (stack.getItemMeta() instanceof BookMeta meta) {
            meta.setTitle(color("&6Enthusia SMP Guide"));
            meta.setAuthor(color("&bEnthusia SMP"));
            meta.setDisplayName(color("&6&lEnthusia SMP Guide"));
            meta.setLore(List.of(
                    color("&7A first-join survival guide."),
                    color("&8Keep it safe.")
            ));
            meta.setPages(
                    page(
                            "&b&lENTHUSIA SMP",
                            "",
                            "&7A mostly vanilla",
                            "&7survival world about",
                            "&7freedom, exploration,",
                            "&7and interaction.",
                            "",
                            "&8- &fBuild",
                            "&8- &fFight",
                            "&8- &fTrade",
                            "&8- &fCreate your story"
                    ),
                    page(
                            "&2&lSURVIVAL",
                            "",
                            "&7Progress is earned",
                            "&7through normal play.",
                            "",
                            "&8- &fGather resources",
                            "&8- &fBuild a base",
                            "&8- &fUpgrade gear",
                            "&8- &fExplore safely",
                            "",
                            "&cNo shortcuts.",
                            "&7Everything you gain",
                            "&7comes from playing."
                    ),
                    page(
                            "&3&lWORLD",
                            "",
                            "&8- &fOverworld: &7100k",
                            "&8- &fNether: &712.5k",
                            "&8- &fEnd: &7100k",
                            "",
                            "&7Farther from spawn",
                            "&7usually means fewer",
                            "&7players nearby.",
                            "",
                            "&fBuild near spawn if",
                            "&fyou want. We won't",
                            "&fstop you."
                    ),
                    page(
                            "&c&lPVP",
                            "",
                            "&7PvP is enabled.",
                            "&7Some areas, such as",
                            "&7spawn, may have",
                            "&7special balancing.",
                            "",
                            "&8- &fStay aware",
                            "&8- &fHide valuables",
                            "&8- &fTrust carefully",
                            "",
                            "&cAlways be prepared."
                    ),
                    page(
                            "&a&lECONOMY",
                            "",
                            "&7The economy is fully",
                            "&7player-driven.",
                            "",
                            "&8- &fRent market shops",
                            "&8- &fPay with Raw Gold",
                            "&8- &fTrade safely",
                            "",
                            "&7Shops help players",
                            "&7buy and sell without",
                            "&7needing direct trust."
                    ),
                    page(
                            "&b&lHOMES",
                            "",
                            "&f/homes &8- &7View homes",
                            "&f/sethome [name]",
                            "&8- &7Save a location",
                            "",
                            "&7You can set up to",
                            "&7two homes for quick",
                            "&7travel.",
                            "",
                            "&cBases are not safe",
                            "&cfrom griefing or",
                            "&ctheft."
                    ),
                    page(
                            "&d&lTELEPORT",
                            "",
                            "&f/tpa &8- &7Request TP",
                            "&f/tpaccept",
                            "&8- &7Accept a request",
                            "",
                            "&7Teleporting is useful,",
                            "&7but risky.",
                            "",
                            "&cOnly accept requests",
                            "&cfrom players you are",
                            "&cready to meet."
                    ),
                    page(
                            "&5&lREPUTATION",
                            "",
                            "&f/rep",
                            "&7Rate players based",
                            "&7on behavior.",
                            "",
                            "&8- &aGood rep builds trust",
                            "&8- &cBad rep warns others",
                            "",
                            "&7You can rate each",
                            "&7player once, and it",
                            "&7is shown publicly."
                    ),
                    page(
                            "&9&lGUILDS",
                            "",
                            "&7Guilds are player",
                            "&7groups for shared",
                            "&7goals and allies.",
                            "",
                            "&8- &fShare bases",
                            "&8- &fPool resources",
                            "&8- &fUse /guildhome",
                            "",
                            "&f/guild &8- &7Help"
                    ),
                    page(
                            "&f&lCOMMANDS",
                            "",
                            "&f/report",
                            "&8- &7Report rule breaks",
                            "",
                            "&f/help",
                            "&8- &7View commands",
                            "",
                            "&f/guild",
                            "&8- &7Guild commands",
                            "",
                            "&f/rep",
                            "&8- &7Reputation"
                    ),
                    page(
                            "&b&lDISCORD",
                            "",
                            "&7Join for news,",
                            "&7updates, community,",
                            "&7and announcements.",
                            "",
                            "&7Link:",
                            "&fwww.enthusia.info"
                    ),
                    page(
                            "&a&lFINAL NOTES",
                            "",
                            "&7Explore the world.",
                            "&7Build something worth",
                            "&7remembering.",
                            "",
                            "&7Meet allies.",
                            "&7Make enemies.",
                            "",
                            "&7This server gives you",
                            "&7the freedom to choose",
                            "&7what happens next.",
                            "",
                            "&aHave fun!"
                    )
            );
            meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String page(String... lines) {
        return color(String.join("\n", lines));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
