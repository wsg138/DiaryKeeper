package com.p2wn.diary.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

public class ConfigManager {
    private final Plugin plugin;
    private FileConfiguration messages;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
    }

    public FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String msg(String path) {
        String prefix = messages.getString("prefix", "");
        String raw = messages.getString(path, path);
        return color(prefix + raw);
    }

    public String raw(String path) {
        return messages.getString(path, path);
    }
}
