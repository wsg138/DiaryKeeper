package com.p2wn.diary.data;

import com.p2wn.diary.logic.PerformanceMonitor;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DiaryAnalyticsStore {

    private final Plugin plugin;
    private final File file;
    private final List<DiaryAnalyticsEvent> events = new ArrayList<>();
    private final Map<UUID, List<DiaryAnalyticsEvent>> eventsByPlayer = new HashMap<>();
    private final Map<UUID, Long> lastActivityByPlayer = new HashMap<>();

    private boolean dirty;
    private int dirtyVersion;
    private boolean saveQueued;
    private BukkitTask autosaveTask;
    private PerformanceMonitor performanceMonitor;

    public DiaryAnalyticsStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "analytics.yml");
    }

    public void load() {
        events.clear();
        eventsByPlayer.clear();
        lastActivityByPlayer.clear();
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
                index(event);
            }
        }
        events.sort(Comparator.comparingLong(DiaryAnalyticsEvent::occurredAt));
        rebuildIndexes();
        prune();
    }

    public void setPerformanceMonitor(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
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
        if (saveQueued) {
            plugin.getLogger().warning("analytics.yml async save was still queued during shutdown; forcing a final blocking flush if dirty.");
        }
        flushNowBlocking("shutdown");
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
        DiaryAnalyticsEvent event = events.get(events.size() - 1);
        index(event);
        markDirty();
        prune();
    }

    public List<DiaryAnalyticsEvent> recentEvents(int limit) {
        return newest(events, limit);
    }

    public List<DiaryAnalyticsEvent> recentEventsForPlayer(UUID playerUuid, int limit) {
        if (playerUuid == null) {
            return List.of();
        }
        List<DiaryAnalyticsEvent> matches = eventsByPlayer.getOrDefault(playerUuid, List.of());
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
        return lastActivityByPlayer.getOrDefault(playerUuid, 0L);
    }

    public void flushIfDirty() {
        if (dirty && !saveQueued) {
            flushNow();
        }
    }

    public void flushNow() {
        if (!dirty || saveQueued) {
            return;
        }
        FileConfiguration data = createSnapshot();
        int version = dirtyVersion;
        saveQueued = true;
        if (performanceMonitor != null) {
            performanceMonitor.analyticsSaveQueued();
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> saveSnapshot(data, version, false));
    }

    public void flushNowBlocking(String reason) {
        if (!dirty) {
            return;
        }
        FileConfiguration data = createSnapshot();
        int version = dirtyVersion;
        saveSnapshot(data, version, true);
        plugin.getLogger().info("DiaryKeeper analytics.yml flush completed during " + reason + ".");
    }

    private FileConfiguration createSnapshot() {
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

        return data;
    }

    private void saveSnapshot(FileConfiguration data, int version, boolean blocking) {
        try {
            data.save(file);
            if (dirtyVersion == version) {
                dirty = false;
            }
            saveQueued = false;
            if (performanceMonitor != null) {
                performanceMonitor.analyticsSaveFlushed();
            }
        } catch (IOException ex) {
            saveQueued = false;
            if (performanceMonitor != null) {
                performanceMonitor.analyticsSaveFailed();
            }
            plugin.getLogger().warning("Failed to save analytics.yml" + (blocking ? " during blocking flush" : "") + ": " + ex.getMessage());
        }
    }

    private List<DiaryAnalyticsEvent> newest(List<DiaryAnalyticsEvent> source, int limit) {
        if (limit <= 0 || source.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, source.size() - limit);
        List<DiaryAnalyticsEvent> results = new ArrayList<>(Math.min(limit, source.size()));
        for (int i = source.size() - 1; i >= from; i--) {
            results.add(source.get(i));
        }
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
            rebuildIndexes();
            markDirty();
        }
    }

    private void markDirty() {
        dirty = true;
        dirtyVersion++;
    }

    private void index(DiaryAnalyticsEvent event) {
        if (event.playerUuid() == null) {
            return;
        }
        eventsByPlayer.computeIfAbsent(event.playerUuid(), ignored -> new ArrayList<>()).add(event);
        lastActivityByPlayer.merge(event.playerUuid(), event.occurredAt(), Math::max);
    }

    private void rebuildIndexes() {
        eventsByPlayer.clear();
        lastActivityByPlayer.clear();
        for (DiaryAnalyticsEvent event : events) {
            index(event);
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
