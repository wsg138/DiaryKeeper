package com.p2wn.diary.item;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class WelcomeBookItem {

    private static final String TEMPLATE_FILE_NAME = "welcome-book.yml";
    private static final String TEMPLATE_KEY = "book";
    private final Plugin plugin;
    private final File templateFile;
    private WelcomeBookTemplate template;

    public WelcomeBookItem(Plugin plugin) {
        this.plugin = plugin;
        this.templateFile = new File(plugin.getDataFolder(), TEMPLATE_FILE_NAME);
        reload();
    }

    public void reload() {
        this.template = loadTemplate();
    }

    public boolean importTemplate(ItemStack source) {
        if (source == null || !(source.getItemMeta() instanceof BookMeta)) {
            return false;
        }

        ItemStack saved = source.clone();
        saved.setAmount(1);

        YamlConfiguration data = new YamlConfiguration();
        data.set(TEMPLATE_KEY, saved);

        try {
            data.save(templateFile);
            reload();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save " + TEMPLATE_FILE_NAME + ": " + ex.getMessage());
            return false;
        }
    }

    public ItemStack createWelcomeBook() {
        ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
        if (stack.getItemMeta() instanceof BookMeta meta) {
            if (template.item().getItemMeta() instanceof BookMeta sourceMeta) {
                meta.setPages(sourceMeta.getPages());
            }
            meta.setTitle(color("&6Enthusia SMP Guide"));
            meta.setAuthor(color("&bEnthusia SMP"));
            meta.setDisplayName(color("&6&lEnthusia SMP Guide"));
            meta.setLore(List.of(
                    color("&7A first-join survival guide."),
                    color("&8Keep it safe.")
            ));
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private WelcomeBookTemplate loadTemplate() {
        if (!templateFile.exists()) {
            if (!copyBundledTemplate()) {
                return defaultTemplate();
            }
        }

        ItemStack book = readTemplateItem();
        if (book == null) {
            if (copyBundledTemplate()) {
                book = readTemplateItem();
            }
            if (book == null) {
                return defaultTemplate();
            }
        }
        book.setAmount(1);
        return new WelcomeBookTemplate(book);
    }

    private ItemStack readTemplateItem() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(templateFile);
        ItemStack book = data.getItemStack(TEMPLATE_KEY);
        if (book == null || !(book.getItemMeta() instanceof BookMeta)) {
            return null;
        }
        return book;
    }

    private boolean copyBundledTemplate() {
        try (InputStream input = plugin.getResource(TEMPLATE_FILE_NAME)) {
            if (input == null) {
                return false;
            }
            Files.createDirectories(templateFile.getParentFile().toPath());
            Files.copy(input, templateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to copy bundled " + TEMPLATE_FILE_NAME + ": " + ex.getMessage());
            return false;
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private WelcomeBookTemplate defaultTemplate() {
        ItemStack stack = new ItemStack(Material.WRITTEN_BOOK);
        if (stack.getItemMeta() instanceof BookMeta meta) {
            meta.setPages(List.of(
                    page(
                            "&6&lWelcome to",
                            "&6&lEnthusia SMP!",
                            "",
                            "&7Enthusia is a semi-anarchy",
                            "&7survival server built around",
                            "&7freedom, PvP, trading,",
                            "&7exploration, and player",
                            "&7interaction.",
                            "",
                            "&aMake your own story."
                    ),
                    page(
                            "&2&lSURVIVAL",
                            "",
                            "&7Gather resources,",
                            "&7build a base, explore,",
                            "&7and progress normally.",
                            "",
                            "&7There are no shortcuts.",
                            "&7Most of what you earn",
                            "&7comes from playing."
                    ),
                    page(
                            "&3&lTHE WORLD",
                            "",
                            "&7The world is large",
                            "&7and permanent.",
                            "&f100k x 100k",
                            "",
                            "&7Travel far from spawn",
                            "&7for more safety, or",
                            "&7stay close if you want",
                            "&7more chaos."
                    ),
                    page(
                            "&c&lBASES",
                            "",
                            "&7There are &c&lno land",
                            "&c&lclaims.",
                            "",
                            "&7Your base can be",
                            "&7raided, griefed, or",
                            "&7stolen from.",
                            "",
                            "&6&lHide it well."
                    ),
                    page(
                            "&c&lPVP",
                            "",
                            "&7Spawn and Market are",
                            "&7safe, but outside those",
                            "&7areas you should always",
                            "&7be ready.",
                            "",
                            "&c&lDo not carry what",
                            "&cyou cannot lose."
                    ),
                    page(
                            "&4&lWARZONE",
                            "",
                            "&7The Warzone is near",
                            "&7spawn and has different",
                            "&7areas for different",
                            "&7PvP styles.",
                            "",
                            "&7Some areas may allow",
                            "&7webs, traps, carts, or",
                            "&7other mechanics.",
                            "",
                            "&c&lIt is dangerous."
                    ),
                    page(
                            "&6&lECONOMY",
                            "",
                            "&7The economy uses",
                            "&6&lRaw Gold.",
                            "",
                            "&7You can trade, deposit,",
                            "&7withdraw, and pay other",
                            "&7players.",
                            "",
                            "&7Raw Gold is also used to",
                            "&7rent market stalls, start",
                            "&7events, and more."
                    ),
                    page(
                            "&6&lMARKET",
                            "",
                            "&7Market is a safe zone.",
                            "",
                            "&7Players can rent stalls",
                            "&7and create item-for-item",
                            "&7shops.",
                            "",
                            "&aTrade resources directly",
                            "&awith other players."
                    ),
                    page(
                            "&3&lHOMES",
                            "",
                            "&7Use &3/sethome [name]&r",
                            "&7to save a location.",
                            "",
                            "&7Use &3/homes&r",
                            "&7to view your saved homes.",
                            "",
                            "&7Homes help you travel,",
                            "&7but they &c&ldo not",
                            "&cprotect the area."
                    ),
                    page(
                            "&3&lTELEPORTS",
                            "",
                            "&7Use &3/tpa [player]&r",
                            "&7to request a teleport.",
                            "",
                            "&7Use &3/tpaccept&r",
                            "&7to accept one.",
                            "",
                            "&c&lBe careful",
                            "&7who you trust.",
                            "",
                            "&7Trapping is allowed."
                    ),
                    page(
                            "&5&lREPUTATION",
                            "",
                            "&7Use &3/rep [player]&r",
                            "&7to apply a positive or",
                            "&7negative reputation to a",
                            "&7player.",
                            "",
                            "&7You can only rep each",
                            "&7player once, but you can",
                            "&7change your rep every",
                            "&724 hours.",
                            "",
                            "&aGood rep builds trust.",
                            "&cBad rep warns others."
                    ),
                    page(
                            "&2&lGUILDS",
                            "",
                            "&7Guilds are groups of",
                            "&7players. Like a team.",
                            "",
                            "&7Join or create one to",
                            "&7team up, make allies,",
                            "&7and compete in guild",
                            "&7events.",
                            "",
                            "&7Use &3/guild&7 help",
                            "&7for guild commands."
                    ),
                    page(
                            "&5&lEVENTS",
                            "",
                            "&7Events happen often and",
                            "&7are separate from normal",
                            "&7survival.",
                            "",
                            "&7You may see PvP games,",
                            "&7races, parkour, KOTH,",
                            "&7trivia, and more.",
                            "",
                            "&6Join for rewards",
                            "&6and competition."
                    ),
                    page(
                            "&3&lCOMMANDS",
                            "",
                            "&3/help",
                            "&7View commands.",
                            "&3/report",
                            "&7Report issues.",
                            "&3/rules",
                            "&7View server rules.",
                            "&3/discord",
                            "&7Join the community.",
                            "&3/wiki",
                            "&7Learn more."
                    ),
                    page(
                            "&9&lWEBSITE",
                            "",
                            "&7Join the Discord for",
                            "&7news, events, support,",
                            "&7and community.",
                            "",
                            "&3&lwww.enthusia.info",
                            "",
                            "&7The website has many",
                            "&7useful tools such as the",
                            "&7rules."
                    ),
                    page(
                            "&6&lFINAL NOTES",
                            "",
                            "&7Build, trade, fight,",
                            "&7explore, make allies,",
                            "&7make enemies, or",
                            "&7disappear into the",
                            "&7wilderness.",
                            "",
                            "&7Your story is yours",
                            "&7to make.",
                            "",
                            "&a&lHave fun!"
                    )
            ));
            stack.setItemMeta(meta);
        }
        return new WelcomeBookTemplate(stack);
    }

    private String page(String... lines) {
        return color(String.join("\n", lines));
    }

    private record WelcomeBookTemplate(ItemStack item) {}
}
