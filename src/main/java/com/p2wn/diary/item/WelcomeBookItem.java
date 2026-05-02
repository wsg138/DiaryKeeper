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
                            "&b&lWelcome to",
                            "&b&lEnthusia SMP!",
                            "",
                            "&7Mostly vanilla",
                            "&7survival focused on",
                            "&7freedom, exploration,",
                            "&7and player",
                            "&7interaction.",
                            "",
                            "&aBuild, fight, trade,",
                            "&aand create your own",
                            "&astory."
                    ),
                    page(
                            "&2&lSURVIVAL",
                            "",
                            "&7This is a survival",
                            "&7server.",
                            "",
                            "&fGather resources,",
                            "&fbuild a base, and",
                            "&fprogress normally.",
                            "",
                            "&cThere are no shortcuts.",
                            "&7Everything you gain",
                            "&7comes from playing."
                    ),
                    page(
                            "&3&lWORLD",
                            "",
                            "&7The world spans:",
                            "&f100k &7blocks Overworld",
                            "&f12.5k &7Nether",
                            "&f100k &7block End",
                            "",
                            "&7Explore and move",
                            "&7away from spawn."
                    ),
                    page(
                            "&3&lWORLD",
                            "",
                            "&7Farther distance",
                            "&7means more safety",
                            "&7and fewer players",
                            "&7nearby.",
                            "",
                            "&fOr build around spawn.",
                            "&7We won't stop you."
                    ),
                    page(
                            "&c&lPVP",
                            "",
                            "&7PvP is enabled.",
                            "&7Some areas or",
                            "&7mechanics, like",
                            "&7spawn, may be",
                            "&7balanced or",
                            "&7restricted.",
                            "",
                            "&cBe prepared.",
                            "&cProtect valuables."
                    ),
                    page(
                            "&a&lECONOMY",
                            "",
                            "&7The economy is",
                            "&7fully player-driven.",
                            "",
                            "&fRent market shops",
                            "&fwith Raw Gold.",
                            "",
                            "&7Use shops to trade",
                            "&7items safely."
                    ),
                    page(
                            "&b&lHOMES",
                            "",
                            "&f/homes",
                            "&7View saved locations.",
                            "",
                            "&f/sethome [name]",
                            "&7Set up to two homes.",
                            "",
                            "&cBases are not",
                            "&cprotected from",
                            "&cgriefing or theft."
                    ),
                    page(
                            "&d&lTELEPORT",
                            "",
                            "&f/tpa",
                            "&7Request teleporting",
                            "&7to another player.",
                            "",
                            "&f/tpaccept",
                            "&7Accept requests.",
                            "",
                            "&cBe careful when",
                            "&caccepting requests."
                    ),
                    page(
                            "&5&lREPUTATION",
                            "",
                            "&7Your reputation",
                            "&7matters.",
                            "",
                            "&f/rep",
                            "&7Uprep or downrep",
                            "&7players based on",
                            "&7their behavior."
                    ),
                    page(
                            "&5&lREPUTATION",
                            "",
                            "&7You can rate each",
                            "&7player once.",
                            "",
                            "&7Reputation is shown",
                            "&7publicly.",
                            "",
                            "&aGood rep builds trust.",
                            "&cBad rep may have",
                            "&cfuture consequences."
                    ),
                    page(
                            "&9&lGUILDS",
                            "",
                            "&7Guilds are groups",
                            "&7of players.",
                            "",
                            "&fCreate or join one",
                            "&fto share bases,",
                            "&fresources, and allies."
                    ),
                    page(
                            "&9&lGUILDS",
                            "",
                            "&7Guilds have access",
                            "&7to &f/guildhome&7.",
                            "",
                            "&7Shared banks and",
                            "&7guild stalls may",
                            "&7come later.",
                            "",
                            "&f/guild",
                            "&7Command help."
                    ),
                    page(
                            "&f&lCOMMANDS",
                            "",
                            "&f/report",
                            "&7Report rule violations",
                            "",
                            "&f/help",
                            "&7View all commands"
                    ),
                    page(
                            "&b&lDISCORD",
                            "",
                            "&7Join our Discord",
                            "&7for news, updates,",
                            "&7community, and more.",
                            "",
                            "&7Link available at:",
                            "&fwww.enthusia.info"
                    ),
                    page(
                            "&a&lFINAL NOTES",
                            "",
                            "&7Explore the world.",
                            "&7Build incredible",
                            "&7things.",
                            "",
                            "&7Meet allies and",
                            "&7enemies.",
                            "",
                            "&7Your story is yours",
                            "&7alone.",
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
