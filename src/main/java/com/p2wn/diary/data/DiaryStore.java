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
        private final Deque<PendingRemoval> pendingRemovals = new ArrayDeque<>();
    }

    private static final class DiaryRecordState {
        private UUID ownerUuid;
        private ItemStack snapshot;
        private DiaryLocationRecord location;
    }

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, PlayerRecord> records = new HashMap<>();
    private final Map<String, DiaryRecordState> diaryRecords = new HashMap<>();

    private String lastWorldUid;
    private boolean dirty;
    private BukkitTask autosaveTask;

    public DiaryStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "diaries.yml");
    }

    public void load() {
        records.clear();
        diaryRecords.clear();
        lastWorldUid = null;
        dirty = false;

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        lastWorldUid = data.getString("lastWorldUid");

        loadPlayers(data.getConfigurationSection("players"));
        loadPendingDeliveries(data);
        loadPendingRemovals(data.getConfigurationSection("pendingRemovals"));
        loadTrackedDiaries(data.getConfigurationSection("trackedDiaries"));
        loadLegacyVoidQueue(data.getConfigurationSection("voidQueue"));
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
        diaryRecords.clear();
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
            if (++count >= limit) {
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

    public boolean removePendingDeliveriesByDiaryId(UUID playerId, String diaryId) {
        PlayerRecord record = records.get(playerId);
        if (record == null || record.pendingDeliveries.isEmpty()) {
            return false;
        }
        boolean removed = record.pendingDeliveries.removeIf(delivery -> diaryId.equals(extractDiaryId(delivery.item())));
        if (removed) {
            markDirty();
        }
        return removed;
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

    public void queuePendingRemoval(UUID playerId, PendingRemoval pendingRemoval) {
        PlayerRecord record = getOrCreateRecord(playerId);
        record.pendingRemovals.addLast(pendingRemoval);
        markDirty();
    }

    public List<PendingRemoval> getPendingRemovals(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        if (record == null || record.pendingRemovals.isEmpty()) {
            return Collections.emptyList();
        }
        return List.copyOf(record.pendingRemovals);
    }

    public void clearPendingRemovals(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        if (record == null || record.pendingRemovals.isEmpty()) {
            return;
        }
        record.pendingRemovals.clear();
        markDirty();
    }

    public void updateTrackedDiary(String diaryId, UUID ownerUuid, ItemStack snapshot, DiaryLocationRecord location) {
        if (diaryId == null || snapshot == null) {
            return;
        }
        DiaryRecordState state = diaryRecords.computeIfAbsent(diaryId, ignored -> new DiaryRecordState());
        state.ownerUuid = ownerUuid;
        state.snapshot = snapshot.clone();
        state.location = location;
        markDirty();
    }

    public TrackedDiaryRecord getTrackedDiary(String diaryId) {
        DiaryRecordState state = diaryRecords.get(diaryId);
        if (state == null) {
            return null;
        }
        return new TrackedDiaryRecord(diaryId, state.ownerUuid, state.snapshot == null ? null : state.snapshot.clone(), state.location);
    }

    public String findDiaryIdByOwner(UUID ownerUuid) {
        PlayerRecord record = records.get(ownerUuid);
        return record == null ? null : record.diaryId;
    }

    public String findDiaryIdByExactOrPrefix(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        if (diaryRecords.containsKey(query)) {
            return query;
        }
        String exactPlayerDiary = findDiaryIdByPlayerQuery(query);
        if (exactPlayerDiary != null) {
            return exactPlayerDiary;
        }
        for (String diaryId : diaryRecords.keySet()) {
            if (diaryId.startsWith(query)) {
                return diaryId;
            }
        }
        return null;
    }

    public Set<UUID> getTrackedOwners() {
        return Set.copyOf(records.keySet());
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
            String playerKey = entry.getKey().toString();
            PlayerRecord record = entry.getValue();

            if (record.diaryId != null) {
                data.set("players." + playerKey + ".id", record.diaryId);
            }
            if (record.issuedAt != null) {
                data.set("players." + playerKey + ".issuedAt", record.issuedAt);
            }

            int deliveryIndex = 0;
            for (PendingDelivery delivery : record.pendingDeliveries) {
                String basePath = "pendingDeliveries." + playerKey + "." + deliveryIndex++;
                data.set(basePath + ".reason", delivery.reason().name());
                data.set(basePath + ".item", delivery.item());
            }

            int removalIndex = 0;
            for (PendingRemoval pendingRemoval : record.pendingRemovals) {
                String basePath = "pendingRemovals." + playerKey + "." + removalIndex++;
                data.set(basePath + ".diaryId", pendingRemoval.diaryId());
                data.set(basePath + ".locationType", pendingRemoval.locationType().name());
                data.set(basePath + ".holderUuid", pendingRemoval.holderUuid() == null ? null : pendingRemoval.holderUuid().toString());
            }
        }

        for (Map.Entry<String, DiaryRecordState> entry : diaryRecords.entrySet()) {
            String diaryId = entry.getKey();
            DiaryRecordState state = entry.getValue();
            String basePath = "trackedDiaries." + diaryId;
            data.set(basePath + ".ownerUuid", state.ownerUuid == null ? null : state.ownerUuid.toString());
            data.set(basePath + ".snapshot", state.snapshot);
            if (state.location != null) {
                ConfigurationSection locationSection = data.createSection(basePath + ".location");
                state.location.writeTo(locationSection);
            }
        }

        try {
            data.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save diaries.yml: " + ex.getMessage());
        }
    }

    private void loadPlayers(ConfigurationSection players) {
        if (players == null) {
            return;
        }
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

    private void loadPendingDeliveries(FileConfiguration data) {
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
                    if (item != null) {
                        record.pendingDeliveries.addLast(new PendingDelivery(parseReason(rawReason), item));
                    }
                }
            }
        }
    }

    private void loadPendingRemovals(ConfigurationSection pendingRemovalsSection) {
        if (pendingRemovalsSection == null) {
            return;
        }
        for (String key : pendingRemovalsSection.getKeys(false)) {
            UUID uuid = parseUuid(key);
            if (uuid == null) {
                continue;
            }
            ConfigurationSection entries = pendingRemovalsSection.getConfigurationSection(key);
            if (entries == null) {
                continue;
            }
            PlayerRecord record = getOrCreateRecord(uuid);
            List<String> orderedKeys = new ArrayList<>(entries.getKeys(false));
            orderedKeys.sort(String::compareTo);
            for (String entryKey : orderedKeys) {
                String basePath = key + "." + entryKey;
                String diaryId = pendingRemovalsSection.getString(basePath + ".diaryId");
                String locationType = pendingRemovalsSection.getString(basePath + ".locationType", DiaryLocationType.UNKNOWN.name());
                if (diaryId != null) {
                    record.pendingRemovals.addLast(new PendingRemoval(
                            diaryId,
                            DiaryLocationType.valueOf(locationType.toUpperCase(Locale.ROOT)),
                            parseUuid(pendingRemovalsSection.getString(basePath + ".holderUuid"))
                    ));
                }
            }
        }
    }

    private void loadTrackedDiaries(ConfigurationSection trackedDiariesSection) {
        if (trackedDiariesSection == null) {
            return;
        }
        for (String diaryId : trackedDiariesSection.getKeys(false)) {
            ConfigurationSection section = trackedDiariesSection.getConfigurationSection(diaryId);
            if (section == null) {
                continue;
            }
            DiaryRecordState state = new DiaryRecordState();
            state.ownerUuid = parseUuid(section.getString("ownerUuid"));
            state.snapshot = section.getItemStack("snapshot");
            ConfigurationSection locationSection = section.getConfigurationSection("location");
            if (locationSection != null) {
                state.location = DiaryLocationRecord.readFrom(locationSection);
            }
            diaryRecords.put(diaryId, state);
        }
    }

    private void loadLegacyVoidQueue(ConfigurationSection legacyVoidQueue) {
        if (legacyVoidQueue == null) {
            return;
        }
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
            return input == null ? null : UUID.fromString(input);
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

    private String findDiaryIdByPlayerQuery(String query) {
        UUID uuid = parseUuid(query);
        if (uuid != null) {
            return getDiaryId(uuid);
        }
        return null;
    }

    private String extractDiaryId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        for (org.bukkit.NamespacedKey key : pdc.getKeys()) {
            if ("diary_id".equals(key.getKey())) {
                return pdc.get(key, org.bukkit.persistence.PersistentDataType.STRING);
            }
        }
        return null;
    }
}
