package com.p2wn.diary.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DiaryAnalyticsStore {

    private final Plugin plugin;
    private final File file;
    private final List<DiaryAnalyticsEvent> events = new ArrayList<>();

    private boolean dirty;
    private BukkitTask autosaveTask;

    public DiaryAnalyticsStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "analytics.yml");
    }

    public void load() {
        events.clear();
        dirty = false;

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = data.getConfigurationSection("events");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            DiaryAnalyticsEvent event = readEvent(section.getConfigurationSection(key));
            if (event != null) {
                events.add(event);
            }
        }
        events.sort(Comparator.comparingLong(DiaryAnalyticsEvent::occurredAt));
        prune();
    }

    public void reloadAutosave() {
        stopAutosave();
        int interval = Math.max(20, plugin.getConfig().getInt("storage.save-interval-ticks", 1200));
        autosaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushIfDirty, interval, interval);
    }

    public void reloadRetention() {
        prune();
        flushIfDirty();
    }

    public void shutdown() {
        stopAutosave();
        flushNow();
    }

    public void record(DiaryAnalyticsEventType type, UUID playerUuid, String playerName, String diaryId, String detail) {
        events.add(new DiaryAnalyticsEvent(
                Instant.now().getEpochSecond(),
                type,
                playerUuid,
                playerName,
                diaryId,
                detail
        ));
        dirty = true;
        prune();
    }

    public List<DiaryAnalyticsEvent> recentEvents(int limit) {
        return newest(events, limit);
    }

    public List<DiaryAnalyticsEvent> recentEventsForPlayer(UUID playerUuid, int limit) {
        if (playerUuid == null) {
            return List.of();
        }
        List<DiaryAnalyticsEvent> matches = events.stream()
                .filter(event -> playerUuid.equals(event.playerUuid()))
                .toList();
        return newest(matches, limit);
    }

    public long countSince(DiaryAnalyticsEventType type, long sinceEpochSeconds) {
        return events.stream()
                .filter(event -> event.type() == type)
                .filter(event -> event.occurredAt() >= sinceEpochSeconds)
                .count();
    }

    public long lastActivityAt(UUID playerUuid) {
        if (playerUuid == null) {
            return 0L;
        }
        long latest = 0L;
        for (DiaryAnalyticsEvent event : events) {
            if (playerUuid.equals(event.playerUuid())) {
                latest = Math.max(latest, event.occurredAt());
            }
        }
        return latest;
    }

    public void flushIfDirty() {
        if (dirty) {
            flushNow();
        }
    }

    public void flushNow() {
        FileConfiguration data = new YamlConfiguration();
        int index = 0;
        for (DiaryAnalyticsEvent event : events) {
            String basePath = "events." + index++;
            data.set(basePath + ".occurredAt", event.occurredAt());
            data.set(basePath + ".type", event.type().name());
            data.set(basePath + ".playerUuid", event.playerUuid() == null ? null : event.playerUuid().toString());
            data.set(basePath + ".playerName", event.playerName());
            data.set(basePath + ".diaryId", event.diaryId());
            data.set(basePath + ".detail", event.detail());
        }

        try {
            data.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save analytics.yml: " + ex.getMessage());
        }
    }

    private List<DiaryAnalyticsEvent> newest(List<DiaryAnalyticsEvent> source, int limit) {
        if (limit <= 0 || source.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, source.size() - limit);
        List<DiaryAnalyticsEvent> results = new ArrayList<>(source.subList(from, source.size()));
        results.sort(Comparator.comparingLong(DiaryAnalyticsEvent::occurredAt).reversed());
        return results;
    }

    private void prune() {
        long now = Instant.now().getEpochSecond();
        int retentionDays = Math.max(1, plugin.getConfig().getInt("analytics.retention-days", 30));
        long oldestAllowed = now - retentionDays * 86_400L;
        boolean removed = events.removeIf(event -> event.occurredAt() < oldestAllowed);

        int maxEvents = Math.max(100, plugin.getConfig().getInt("analytics.max-events", 1000));
        while (events.size() > maxEvents) {
            events.remove(0);
            removed = true;
        }

        if (removed) {
            dirty = true;
        }
    }

    private DiaryAnalyticsEvent readEvent(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        DiaryAnalyticsEventType type = parseType(section.getString("type"));
        if (type == null) {
            return null;
        }
        long occurredAt = section.getLong("occurredAt", 0L);
        if (occurredAt <= 0L) {
            return null;
        }
        return new DiaryAnalyticsEvent(
                occurredAt,
                type,
                parseUuid(section.getString("playerUuid")),
                section.getString("playerName"),
                section.getString("diaryId"),
                section.getString("detail")
        );
    }

    private DiaryAnalyticsEventType parseType(String input) {
        try {
            return input == null ? null : DiaryAnalyticsEventType.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private UUID parseUuid(String input) {
        try {
            return input == null ? null : UUID.fromString(input);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void stopAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }
}
