package com.p2wn.diary.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;

public final class ConfigManager {

    private final Plugin plugin;
    private FileConfiguration config;
    private FileConfiguration messages;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();

        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reload() {
        load();
    }

    public FileConfiguration cfg() {
        return config;
    }

    public String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    public String msg(String path) {
        return msg(path, Map.of());
    }

    public String msg(String path, Map<String, String> placeholders) {
        String prefix = messages.getString("prefix", "");
        String raw = messages.getString(path, path);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return color(prefix + raw);
    }

    public String raw(String path) {
        return messages.getString(path, path);
    }
}
