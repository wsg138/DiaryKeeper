package com.p2wn.diary.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ConfigManager {

    public record MigrationReport(List<String> actions, List<String> warnings) {
        public boolean changed() {
            return !actions.isEmpty();
        }
    }

    private static final int CURRENT_CONFIG_VERSION = 2;

    private final Plugin plugin;
    private FileConfiguration config;
    private FileConfiguration messages;
    private MigrationReport lastMigrationReport = new MigrationReport(List.of(), List.of());

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        lastMigrationReport = migrateFiles();
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

    public MigrationReport lastMigrationReport() {
        return lastMigrationReport;
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

    private MigrationReport migrateFiles() {
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        migrateYaml("config.yml", true, actions, warnings);
        migrateYaml("messages.yml", false, actions, warnings);
        migrateYaml("welcome-book.yml", false, actions, warnings);
        return new MigrationReport(List.copyOf(actions), List.copyOf(warnings));
    }

    private void migrateYaml(String resourceName, boolean bukkitConfig, List<String> actions, List<String> warnings) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            if (!bukkitConfig) {
                plugin.saveResource(resourceName, false);
            }
            return;
        }

        YamlConfiguration defaults = loadBundledYaml(resourceName);
        if (defaults == null) {
            warnings.add(resourceName + ": bundled defaults were not found");
            return;
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        int userVersion = current.getInt("config-version", 1);
        boolean changed = false;
        if (!current.contains("config-version")) {
            current.set("config-version", CURRENT_CONFIG_VERSION);
            actions.add(resourceName + ": added config-version=" + CURRENT_CONFIG_VERSION);
            changed = true;
        } else if (userVersion < CURRENT_CONFIG_VERSION) {
            current.set("config-version", CURRENT_CONFIG_VERSION);
            actions.add(resourceName + ": migrated config-version " + userVersion + " -> " + CURRENT_CONFIG_VERSION);
            changed = true;
        }

        for (String key : defaults.getKeys(true)) {
            if ("welcome-book.yml".equals(resourceName) && key.startsWith("book.") && current.contains("book")) {
                continue;
            }
            if (isSection(defaults, key) || current.contains(key)) {
                continue;
            }
            current.set(key, defaults.get(key));
            actions.add(resourceName + ": added missing key " + key);
            changed = true;
        }

        if (!changed) {
            return;
        }

        File backup = backupFile(file);
        if (backup == null) {
            warnings.add(resourceName + ": migration skipped because backup could not be created");
            return;
        }
        try {
            current.save(file);
        } catch (IOException ex) {
            warnings.add(resourceName + ": migration failed after backup " + backup.getName() + ": " + ex.getMessage());
        }
    }

    private boolean isSection(YamlConfiguration config, String key) {
        ConfigurationSection section = config.getConfigurationSection(key);
        return section != null && config.get(key) instanceof ConfigurationSection;
    }

    private YamlConfiguration loadBundledYaml(String resourceName) {
        try (InputStream input = plugin.getResource(resourceName)) {
            if (input == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to read bundled " + resourceName + ": " + ex.getMessage());
            return null;
        }
    }

    private File backupFile(File file) {
        File backupDir = new File(plugin.getDataFolder(), "backups");
        String stamp = Long.toString(Instant.now().getEpochSecond());
        File backup = new File(backupDir, file.getName() + "." + stamp + ".bak");
        try {
            Files.createDirectories(backupDir.toPath());
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to backup " + file.getName() + " before migration: " + ex.getMessage());
            return null;
        }
    }
}
