package com.p2wn.diary.item;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
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
                            "&6&lWelcome to",
                            "&6&lEnthusia SMP!",
                            "",
                            "&7This server is a mostly vanilla survival world focused on freedom, long-term exploration, and player interaction.",
                            "",
                            "&aBuild, fight, trade, and create your own story!"
                    ),
                    page(
                            "&2&lSURVIVAL",
                            "",
                            "&7This is a survival server.",
                            "",
                            "&fGather resources, build a base, and progress normally.",
                            "",
                            "&cThere are no shortcuts.",
                            "&7Everything you gain comes from playing."
                    ),
                    page(
                            "&3&lWORLD",
                            "",
                            "&7The world spans:",
                            "&f100k &7blocks Overworld",
                            "&f12.5k &7Nether",
                            "&f100k &7block End",
                            "",
                            "&7You are encouraged to explore and move away from spawn."
                    ),
                    page(
                            "&3&lWORLD",
                            "",
                            "&7Farther distance means more safety and fewer players nearby.",
                            "",
                            "&eOr just build straight around spawn - we won't stop you!"
                    ),
                    page(
                            "&c&lPVP",
                            "",
                            "&7PvP is enabled, although some areas or mechanics like spawn may be balanced or restricted.",
                            "",
                            "&cAlways be prepared and protect your valuables!"
                    ),
                    page(
                            "&6&lECONOMY",
                            "",
                            "&7The economy is fully player-driven.",
                            "",
                            "&fRent shops &7in the market with &6Raw Gold&7 and use them to trade items safely for what you need."
                    ),
                    page(
                            "&b&lHOMES",
                            "",
                            "&f/homes &7- view saved locations",
                            "&f/sethome [name]",
                            "&7Set up to two homes to quickly return to important places like your base.",
                            "",
                            "&cBases are not protected from griefing or theft!"
                    ),
                    page(
                            "&d&lTELEPORT",
                            "",
                            "&f/tpa &7- request teleportation to another player",
                            "&f/tpaccept &7- accept incoming requests",
                            "",
                            "&cAlways be careful when accepting requests!"
                    ),
                    page(
                            "&5&lREPUTATION",
                            "",
                            "&7Your reputation matters!",
                            "",
                            "&f/rep &7lets you uprep or downrep players depending on their behavior toward you and others."
                    ),
                    page(
                            "&5&lREPUTATION",
                            "",
                            "&7You can only give reputation to each player once, and it is displayed publicly.",
                            "",
                            "&aGood reputation &7may build trust.",
                            "&cNegative reputation &7may lead to future consequences..."
                    ),
                    page(
                            "&9&lGUILDS",
                            "",
                            "&7Guilds are groups of players.",
                            "",
                            "&fCreate or join a guild &7to share bases, resources, and allies."
                    ),
                    page(
                            "&9&lGUILDS",
                            "",
                            "&7As a guild, you will have access to &f/guildhome&7 plus a shared bank and guild stall in the future.",
                            "",
                            "&f/guild &7- help with further commands"
                    ),
                    page(
                            "&e&lCOMMANDS",
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
                            "&7Join our Discord for news, updates, community and more!",
                            "",
                            "&7The link is available through:",
                            "&fwww.enthusia.info"
                    ),
                    page(
                            "&6&lFINAL NOTES",
                            "",
                            "&7Explore the world, build incredible things, meet allies and enemies - your story on this server is yours alone.",
                            "",
                            "&aHave fun!"
                    )
            );
            meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
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
