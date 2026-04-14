package com.p2wn.diary.data;

import com.p2wn.diary.util.ItemIO;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class DiaryStore {

    private static final class PlayerRecord {
        private String diaryId;
        private Long issuedAt;
        private final Deque<PendingDelivery> pendingDeliveries = new ArrayDeque<>();
    }

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, PlayerRecord> records = new HashMap<>();

    private String lastWorldUid;
    private boolean dirty;
    private BukkitTask autosaveTask;

    public DiaryStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "diaries.yml");
    }

    public void load() {
        records.clear();
        lastWorldUid = null;
        dirty = false;

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        lastWorldUid = data.getString("lastWorldUid");

        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID uuid = parseUuid(key);
                if (uuid == null) {
                    continue;
                }

                PlayerRecord record = getOrCreateRecord(uuid);
                record.diaryId = players.getString(key + ".id");
                long issuedAt = players.getLong(key + ".issuedAt", 0L);
                if (issuedAt > 0L) {
                    record.issuedAt = issuedAt;
                }
            }
        }

        ConfigurationSection pending = data.getConfigurationSection("pendingDeliveries");
        if (pending != null) {
            for (String key : pending.getKeys(false)) {
                UUID uuid = parseUuid(key);
                if (uuid == null) {
                    continue;
                }
                ConfigurationSection entries = pending.getConfigurationSection(key);
                if (entries == null) {
                    continue;
                }
                PlayerRecord record = getOrCreateRecord(uuid);
                List<String> orderedKeys = new ArrayList<>(entries.getKeys(false));
                orderedKeys.sort(String::compareTo);
                for (String entryKey : orderedKeys) {
                    String basePath = key + "." + entryKey;
                    String rawReason = pending.getString(basePath + ".reason", DeliveryReason.VOID_RETURN.name());
                    ItemStack item = pending.getItemStack(basePath + ".item");
                    if (item == null) {
                        continue;
                    }
                    record.pendingDeliveries.addLast(new PendingDelivery(parseReason(rawReason), item));
                }
            }
        }

        ConfigurationSection legacyVoidQueue = data.getConfigurationSection("voidQueue");
        if (legacyVoidQueue != null) {
            for (String key : legacyVoidQueue.getKeys(false)) {
                UUID uuid = parseUuid(key);
                if (uuid == null) {
                    continue;
                }
                PlayerRecord record = getOrCreateRecord(uuid);
                for (String encoded : legacyVoidQueue.getStringList(key)) {
                    try {
                        ItemStack stack = ItemIO.fromBase64(encoded);
                        record.pendingDeliveries.addLast(new PendingDelivery(DeliveryReason.VOID_RETURN, stack));
                    } catch (IOException ex) {
                        plugin.getLogger().warning("Failed to deserialize legacy queued diary for " + uuid + ": " + ex.getMessage());
                    }
                }
            }
        }
    }

    public void reloadAutosave() {
        stopAutosave();
        int interval = Math.max(20, plugin.getConfig().getInt("storage.save-interval-ticks", 1200));
        autosaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flushIfDirty, interval, interval);
    }

    public void shutdown() {
        stopAutosave();
        flushNow();
    }

    public String getLastWorldUid() {
        return lastWorldUid;
    }

    public void setLastWorldUid(String lastWorldUid) {
        if (!Objects.equals(this.lastWorldUid, lastWorldUid)) {
            this.lastWorldUid = lastWorldUid;
            markDirty();
        }
    }

    public void resetAllPlayers() {
        records.clear();
        markDirty();
    }

    public String getOrCreateDiaryId(UUID playerId) {
        PlayerRecord record = getOrCreateRecord(playerId);
        if (record.diaryId == null || record.diaryId.isBlank()) {
            record.diaryId = UUID.randomUUID().toString();
            markDirty();
        }
        return record.diaryId;
    }

    public String getDiaryId(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        return record == null ? null : record.diaryId;
    }

    public boolean hasIssued(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        return record != null && record.issuedAt != null;
    }

    public void markIssued(UUID playerId) {
        PlayerRecord record = getOrCreateRecord(playerId);
        if (record.issuedAt == null) {
            record.issuedAt = Instant.now().getEpochSecond();
            markDirty();
        }
    }

    public long getIssuedAt(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        return record == null || record.issuedAt == null ? 0L : record.issuedAt;
    }

    public void queueDelivery(UUID playerId, DeliveryReason reason, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        PlayerRecord record = getOrCreateRecord(playerId);
        record.pendingDeliveries.addLast(new PendingDelivery(reason, item));
        markDirty();
    }

    public List<PendingDelivery> getPendingDeliveries(UUID playerId, int limit) {
        PlayerRecord record = records.get(playerId);
        if (record == null || record.pendingDeliveries.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }

        List<PendingDelivery> results = new ArrayList<>(Math.min(limit, record.pendingDeliveries.size()));
        int count = 0;
        for (PendingDelivery delivery : record.pendingDeliveries) {
            results.add(delivery.copy());
            count++;
            if (count >= limit) {
                break;
            }
        }
        return results;
    }

    public void removeFirstPendingDeliveries(UUID playerId, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerRecord record = records.get(playerId);
        if (record == null) {
            return;
        }
        for (int i = 0; i < amount && !record.pendingDeliveries.isEmpty(); i++) {
            record.pendingDeliveries.removeFirst();
        }
        markDirty();
    }

    public int getPendingDeliveryCount(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        return record == null ? 0 : record.pendingDeliveries.size();
    }

    public Set<UUID> getPlayersWithPendingDeliveries() {
        Set<UUID> results = new HashSet<>();
        for (Map.Entry<UUID, PlayerRecord> entry : records.entrySet()) {
            if (!entry.getValue().pendingDeliveries.isEmpty()) {
                results.add(entry.getKey());
            }
        }
        return results;
    }

    public void flushIfDirty() {
        if (dirty) {
            flushNow();
        }
    }

    public void flushNow() {
        FileConfiguration data = new YamlConfiguration();
        data.set("lastWorldUid", lastWorldUid);

        for (Map.Entry<UUID, PlayerRecord> entry : records.entrySet()) {
            PlayerRecord record = entry.getValue();
            String key = entry.getKey().toString();
            if (record.diaryId != null) {
                data.set("players." + key + ".id", record.diaryId);
            }
            if (record.issuedAt != null) {
                data.set("players." + key + ".issuedAt", record.issuedAt);
            }

            int index = 0;
            for (PendingDelivery delivery : record.pendingDeliveries) {
                String basePath = "pendingDeliveries." + key + "." + index++;
                data.set(basePath + ".reason", delivery.reason().name());
                data.set(basePath + ".item", delivery.item());
            }
        }

        try {
            data.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save diaries.yml: " + ex.getMessage());
        }
    }

    private void stopAutosave() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }
    }

    private PlayerRecord getOrCreateRecord(UUID uuid) {
        return records.computeIfAbsent(uuid, ignored -> new PlayerRecord());
    }

    private void markDirty() {
        dirty = true;
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private DeliveryReason parseReason(String input) {
        try {
            return DeliveryReason.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DeliveryReason.VOID_RETURN;
        }
    }
}
