package com.p2wn.diary.data;

import com.p2wn.diary.util.ItemIO;
import com.p2wn.diary.logic.PerformanceMonitor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class DiaryStore {

    public enum DurableQueueResult { SAVED, ALREADY_QUEUED, SAVE_FAILED, PLUGIN_DISABLED }

    private static final class PlayerRecord {
        private String diaryId;
        private Long issuedAt;
        private final Deque<PendingDelivery> pendingDeliveries = new ArrayDeque<>();
        private final Deque<PendingRemoval> pendingRemovals = new ArrayDeque<>();
    }

    private static final class DiaryRecordState {
        private UUID ownerUuid;
        private String ownerName;
        private ItemStack snapshot;
        private DiaryLocationRecord location;
        private final List<DiaryLocationRecord> locations = new ArrayList<>();
        private long snapshotUpdatedAt;
    }

    private final Plugin plugin;
    private final File file;
    private final PersistenceWriter persistenceWriter;
    private final Object stateLock = new Object();
    private final Object fileSaveLock = new Object();
    private final Map<UUID, PlayerRecord> records = new HashMap<>();
    private final Map<UUID, PlayerIdentity> identities = new HashMap<>();
    private boolean pendingDeliveryIdsMigrated;
    private final Map<String, DiaryRecordState> diaryRecords = new HashMap<>();
    private final Map<UUID, PurgeOperation> purgeOperations = new HashMap<>();
    private final Map<UUID, Set<String>> locationDiaryIdsByHolder = new HashMap<>();
    private final Map<String, Set<String>> locationDiaryIdsByChunk = new HashMap<>();
    private final Set<String> diaryIdsWithActiveLocations = new HashSet<>();

    private String lastWorldUid;
    private boolean dirty;
    private int dirtyVersion;
    private boolean saveQueued;
    private CompletableFuture<Void> runningSave;
    private BukkitTask autosaveTask;
    private PerformanceMonitor performanceMonitor;
    private long lastPruneAt;

    public DiaryStore(Plugin plugin) {
        this(plugin, null);
    }

    DiaryStore(Plugin plugin, PersistenceWriter persistenceWriter) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "diaries.yml");
        this.persistenceWriter = persistenceWriter;
    }

    public void load() {
        records.clear();
        diaryRecords.clear();
        purgeOperations.clear();
        lastWorldUid = null;
        dirty = false;

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        lastWorldUid = data.getString("lastWorldUid");

        loadPlayers(data.getConfigurationSection("players"));
        loadIdentities(data.getConfigurationSection("identities"));
        loadPendingDeliveries(data);
        loadPendingRemovals(data.getConfigurationSection("pendingRemovals"));
        loadTrackedDiaries(data.getConfigurationSection("trackedDiaries"));
        migrateIdentitiesFromTrackedDiaries();
        rebuildLocationIndexes();
        loadPurgeOperations(data.getConfigurationSection("purgeOperations"));
        reconcileCompetingPurgeOperations();
        loadLegacyVoidQueue(data.getConfigurationSection("voidQueue"));
        pendingDeliveryIdsMigrated = migratePendingDeliveryIds();
    }

    public void reloadAutosave() {
        stopAutosave();
        int interval = Math.max(20, plugin.getConfig().getInt("storage.save-interval-ticks", 1200));
        autosaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            pruneRetainedState();
            flushIfDirty();
        }, interval, interval);
    }

    public void setPerformanceMonitor(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }

    public void shutdown() {
        stopAutosave();
        flushNowBlocking("shutdown");
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
        purgeOperations.clear();
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
        queueDelivery(playerId, reason, item, null);
    }

    public void queueDelivery(UUID playerId, DeliveryReason reason, ItemStack item, UUID token) {
        queueDeliveryIfAbsent(playerId, reason, item, token);
    }

    public boolean queueDeliveryIfAbsent(UUID playerId, DeliveryReason reason, ItemStack item, UUID token) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        UUID deliveryId = token == null ? UUID.randomUUID() : token;
        PlayerRecord record = getOrCreateRecord(playerId);
        if (record.pendingDeliveries.stream().anyMatch(delivery -> deliveryId.equals(delivery.token()))) {
            return false;
        }
        record.pendingDeliveries.addLast(new PendingDelivery(reason, item, deliveryId));
        markDirty();
        return true;
    }

    public CompletableFuture<DurableQueueResult> queueDeliveryDurably(UUID playerId, DeliveryReason reason,
                                                                        ItemStack item, UUID deliveryId) {
        if (!plugin.isEnabled()) return CompletableFuture.completedFuture(DurableQueueResult.PLUGIN_DISABLED);
        boolean added = queueDeliveryIfAbsent(playerId, reason, item, deliveryId);
        if (!added && hasPendingDeliveryToken(deliveryId)) {
            return flushDurably().handle((ignored, failure) -> failure == null
                    ? DurableQueueResult.ALREADY_QUEUED : DurableQueueResult.SAVE_FAILED);
        }
        if (!added) return CompletableFuture.completedFuture(DurableQueueResult.SAVE_FAILED);
        return flushDurably().handle((ignored, failure) -> {
            if (!plugin.isEnabled()) return DurableQueueResult.PLUGIN_DISABLED;
            return failure == null ? DurableQueueResult.SAVED : DurableQueueResult.SAVE_FAILED;
        });
    }

    public CompletableFuture<Boolean> recoverInterruptedDeliveryReleases() {
        List<DeliveryEntry> interrupted = getDeliveryEntries().stream()
                .filter(entry -> entry.delivery().lifecycle() == DeliveryLifecycle.RELEASE_PENDING).toList();
        if (interrupted.isEmpty()) return CompletableFuture.completedFuture(false);
        for (DeliveryEntry entry : interrupted) {
            updateDeliveryLifecycle(entry.playerId(), entry.delivery().token(), DeliveryLifecycle.RELEASE_PENDING,
                    DeliveryLifecycle.QUEUED, entry.delivery().lastPersistenceError());
        }
        return flushDurably().handle((ignored, failure) -> {
            if (failure == null) return true;
            for (DeliveryEntry entry : interrupted) {
                updateDeliveryLifecycle(entry.playerId(), entry.delivery().token(), DeliveryLifecycle.QUEUED,
                        DeliveryLifecycle.RELEASE_PENDING, rootMessage(failure));
            }
            return false;
        });
    }

    public boolean hasPendingDeliveryToken(UUID token) {
        return token != null && records.values().stream()
                .flatMap(record -> record.pendingDeliveries.stream())
                .anyMatch(delivery -> token.equals(delivery.token()));
    }

    public List<PendingDelivery> getPendingDeliveries(UUID playerId, int limit) {
        PlayerRecord record = records.get(playerId);
        if (record == null || record.pendingDeliveries.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<PendingDelivery> results = new ArrayList<>(Math.min(limit, record.pendingDeliveries.size()));
        int count = 0;
        for (PendingDelivery delivery : record.pendingDeliveries) {
            if (delivery.lifecycle() != DeliveryLifecycle.QUEUED) {
                continue;
            }
            results.add(delivery.copy());
            if (++count >= limit) {
                break;
            }
        }
        return results;
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

    public boolean claimDelivery(UUID playerId, UUID token) {
        return updateDeliveryLifecycle(playerId, token, DeliveryLifecycle.QUEUED, DeliveryLifecycle.CLAIMED);
    }

    public boolean releaseDeliveryClaim(UUID playerId, UUID token) {
        return updateDeliveryLifecycle(playerId, token, DeliveryLifecycle.CLAIMED, DeliveryLifecycle.QUEUED, null);
    }

    public CompletableFuture<Boolean> releaseDeliveryClaimDurably(UUID playerId, UUID deliveryId) {
        if (!updateDeliveryLifecycle(playerId, deliveryId, DeliveryLifecycle.CLAIMED,
                DeliveryLifecycle.RELEASE_PENDING, null)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        flushDurably().whenComplete((ignored, firstFailure) -> {
            if (!plugin.isEnabled()) {
                result.completeExceptionally(new IOException("Plugin disabled during durable release"));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (firstFailure != null) {
                    recordDeliveryPersistenceError(playerId, deliveryId, rootMessage(firstFailure));
                    result.completeExceptionally(firstFailure);
                    return;
                }
                if (!updateDeliveryLifecycle(playerId, deliveryId, DeliveryLifecycle.RELEASE_PENDING,
                        DeliveryLifecycle.QUEUED, null)) {
                    result.complete(false);
                    return;
                }
                flushDurably().whenComplete((saved, secondFailure) -> {
                    if (!plugin.isEnabled()) {
                        result.completeExceptionally(new IOException("Plugin disabled during durable release"));
                        return;
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (secondFailure == null) {
                            result.complete(true);
                        } else {
                            updateDeliveryLifecycle(playerId, deliveryId, DeliveryLifecycle.QUEUED,
                                    DeliveryLifecycle.RELEASE_PENDING, rootMessage(secondFailure));
                            recordDeliveryPersistenceError(playerId, deliveryId, rootMessage(secondFailure));
                            result.completeExceptionally(secondFailure);
                        }
                    });
                });
            });
        });
        return result;
    }

    public boolean releaseDeliveryClaim(UUID token) {
        for (UUID playerId : records.keySet()) {
            if (releaseDeliveryClaim(playerId, token)) {
                return true;
            }
        }
        return false;
    }

    public boolean markDeliveryDelivered(UUID playerId, UUID token) {
        return updateDeliveryLifecycle(playerId, token, DeliveryLifecycle.CLAIMED, DeliveryLifecycle.DELIVERED, null);
    }

    public boolean confirmDeliveryPresent(UUID playerId, UUID deliveryId) {
        DeliveryEntry entry = getDeliveryEntry(deliveryId);
        if (entry == null || !entry.playerId().equals(playerId)
                || entry.delivery().lifecycle() == DeliveryLifecycle.DELIVERED) {
            return false;
        }
        return updateDeliveryLifecycle(playerId, deliveryId, entry.delivery().lifecycle(),
                DeliveryLifecycle.DELIVERED, null);
    }

    private boolean updateDeliveryLifecycle(UUID playerId, UUID token, DeliveryLifecycle expected, DeliveryLifecycle replacement) {
        return updateDeliveryLifecycle(playerId, token, expected, replacement, null);
    }

    private boolean updateDeliveryLifecycle(UUID playerId, UUID token, DeliveryLifecycle expected,
                                            DeliveryLifecycle replacement, String persistenceError) {
        if (token == null) {
            return false;
        }
        PlayerRecord record = records.get(playerId);
        if (record == null) {
            return false;
        }
        List<PendingDelivery> deliveries = new ArrayList<>(record.pendingDeliveries);
        for (int i = 0; i < deliveries.size(); i++) {
            PendingDelivery delivery = deliveries.get(i);
            if (token.equals(delivery.token()) && delivery.lifecycle() == expected) {
                long now = Instant.now().getEpochSecond();
                long claimedAt = replacement == DeliveryLifecycle.CLAIMED ? now : delivery.claimedAt();
                long deliveredAt = replacement == DeliveryLifecycle.DELIVERED ? now : delivery.deliveredAt();
                deliveries.set(i, new PendingDelivery(delivery.reason(), delivery.item(), token, replacement,
                        delivery.createdAt(), claimedAt, deliveredAt, persistenceError));
                record.pendingDeliveries.clear();
                record.pendingDeliveries.addAll(deliveries);
                markDirty();
                return true;
            }
        }
        return false;
    }

    private void recordDeliveryPersistenceError(UUID playerId, UUID deliveryId, String error) {
        PlayerRecord record = records.get(playerId);
        if (record == null) return;
        List<PendingDelivery> deliveries = new ArrayList<>(record.pendingDeliveries);
        for (int i = 0; i < deliveries.size(); i++) {
            PendingDelivery delivery = deliveries.get(i);
            if (deliveryId.equals(delivery.token())) {
                deliveries.set(i, new PendingDelivery(delivery.reason(), delivery.item(), delivery.token(),
                        delivery.lifecycle(), delivery.createdAt(), delivery.claimedAt(), delivery.deliveredAt(), error));
                record.pendingDeliveries.clear();
                record.pendingDeliveries.addAll(deliveries);
                markDirty();
                return;
            }
        }
    }

    private String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public CompletableFuture<Boolean> retryDeliveryDurably(UUID deliveryId) {
        DeliveryEntry entry = getDeliveryEntry(deliveryId);
        if (entry == null || entry.delivery().lifecycle() == DeliveryLifecycle.DELIVERED) {
            return CompletableFuture.completedFuture(false);
        }
        DeliveryLifecycle lifecycle = entry.delivery().lifecycle();
        if (lifecycle == DeliveryLifecycle.QUEUED) {
            return flushDurably().thenApply(ignored -> true);
        }
        if (lifecycle == DeliveryLifecycle.RELEASE_PENDING
                && !updateDeliveryLifecycle(entry.playerId(), deliveryId, DeliveryLifecycle.RELEASE_PENDING,
                DeliveryLifecycle.CLAIMED, entry.delivery().lastPersistenceError())) {
            return CompletableFuture.completedFuture(false);
        }
        return releaseDeliveryClaimDurably(entry.playerId(), deliveryId);
    }

    public CompletableFuture<Boolean> markDeliveryDeliveredDurably(UUID deliveryId) {
        DeliveryEntry entry = getDeliveryEntry(deliveryId);
        if (entry == null || entry.delivery().lifecycle() == DeliveryLifecycle.DELIVERED) {
            return CompletableFuture.completedFuture(false);
        }
        DeliveryLifecycle previous = entry.delivery().lifecycle();
        if (!updateDeliveryLifecycle(entry.playerId(), deliveryId, previous, DeliveryLifecycle.DELIVERED, null)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        flushDurably().whenComplete((ignored, failure) -> {
            if (failure == null) {
                result.complete(true);
            } else {
                rollbackDeliveryOnMain(entry.playerId(), deliveryId, DeliveryLifecycle.DELIVERED,
                        previous, failure, result);
            }
        });
        return result;
    }

    public CompletableFuture<Boolean> cancelDeliveryDurably(UUID deliveryId) {
        DeliveryEntry entry = getDeliveryEntry(deliveryId);
        if (entry == null) return CompletableFuture.completedFuture(false);
        if (!cancelDelivery(deliveryId)) return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        flushDurably().whenComplete((ignored, failure) -> {
            if (failure == null) {
                result.complete(true);
            } else if (!plugin.isEnabled()) {
                result.completeExceptionally(failure);
            } else {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    records.get(entry.playerId()).pendingDeliveries.addLast(entry.delivery().copy());
                    recordDeliveryPersistenceError(entry.playerId(), deliveryId, rootMessage(failure));
                    result.completeExceptionally(failure);
                });
            }
        });
        return result;
    }

    private void rollbackDeliveryOnMain(UUID playerId, UUID deliveryId, DeliveryLifecycle current,
                                        DeliveryLifecycle previous, Throwable failure,
                                        CompletableFuture<Boolean> result) {
        if (!plugin.isEnabled()) {
            result.completeExceptionally(failure);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            updateDeliveryLifecycle(playerId, deliveryId, current, previous, rootMessage(failure));
            recordDeliveryPersistenceError(playerId, deliveryId, rootMessage(failure));
            result.completeExceptionally(failure);
        });
    }

    public int removeAllPendingDeliveriesByDiaryId(String diaryId) {
        int removed = 0;
        for (PlayerRecord record : records.values()) {
            int before = record.pendingDeliveries.size();
            record.pendingDeliveries.removeIf(delivery -> diaryId.equals(extractDiaryId(delivery.item())));
            removed += before - record.pendingDeliveries.size();
        }
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    public boolean hasPendingDelivery(UUID playerId, String diaryId) {
        PlayerRecord record = records.get(playerId);
        return record != null && record.pendingDeliveries.stream()
                .anyMatch(delivery -> diaryId.equals(extractDiaryId(delivery.item())));
    }

    public int getPendingDeliveryCount(UUID playerId) {
        PlayerRecord record = records.get(playerId);
        return record == null ? 0 : (int) record.pendingDeliveries.stream()
                .filter(delivery -> delivery.lifecycle() == DeliveryLifecycle.QUEUED).count();
    }

    public Set<UUID> getPlayersWithPendingDeliveries() {
        Set<UUID> results = new HashSet<>();
        for (Map.Entry<UUID, PlayerRecord> entry : records.entrySet()) {
            if (entry.getValue().pendingDeliveries.stream()
                    .anyMatch(delivery -> delivery.lifecycle() == DeliveryLifecycle.QUEUED)) {
                results.add(entry.getKey());
            }
        }
        return results;
    }

    public int getTotalPendingDeliveryCount() {
        int total = 0;
        for (PlayerRecord record : records.values()) {
            total += record.pendingDeliveries.stream().filter(delivery -> delivery.lifecycle() == DeliveryLifecycle.QUEUED).count();
        }
        return total;
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

    public void updateTrackedDiary(String diaryId, UUID ownerUuid, String ownerName, ItemStack snapshot, DiaryLocationRecord location) {
        if (diaryId == null || snapshot == null) {
            return;
        }
        DiaryRecordState state = diaryRecords.computeIfAbsent(diaryId, ignored -> new DiaryRecordState());
        state.ownerUuid = ownerUuid;
        if (ownerName != null && !ownerName.isBlank()) {
            state.ownerName = ownerName;
        }
        state.snapshot = snapshot.clone();
        state.snapshotUpdatedAt = Instant.now().getEpochSecond();
        state.location = location;
        if (location != null) {
            int existingIndex = -1;
            for (int i = 0; i < state.locations.size(); i++) {
                if (state.locations.get(i).identityKey().equals(location.identityKey())) {
                    existingIndex = i;
                    break;
                }
            }
            if (existingIndex >= 0) {
                state.locations.set(existingIndex, state.locations.get(existingIndex).observedAgain(location));
            } else {
                state.locations.add(location);
            }
        }
        rebuildLocationIndexes();
        markDirty();
    }

    public List<DeliveryEntry> getDeliveryEntries() {
        List<DeliveryEntry> entries = new ArrayList<>();
        records.forEach((playerId, record) -> record.pendingDeliveries.forEach(delivery ->
                entries.add(new DeliveryEntry(playerId, delivery.copy()))));
        return entries;
    }

    public DeliveryEntry getDeliveryEntry(UUID deliveryId) {
        return getDeliveryEntries().stream()
                .filter(entry -> deliveryId.equals(entry.delivery().token())).findFirst().orElse(null);
    }

    public boolean cancelDelivery(UUID deliveryId) {
        for (PlayerRecord record : records.values()) {
            if (record.pendingDeliveries.removeIf(delivery -> deliveryId.equals(delivery.token()))) {
                markDirty();
                return true;
            }
        }
        return false;
    }

    public void updateTrackedSnapshot(String diaryId, UUID ownerUuid, String ownerName, ItemStack snapshot) {
        if (diaryId == null || snapshot == null) {
            return;
        }
        DiaryRecordState state = diaryRecords.computeIfAbsent(diaryId, ignored -> new DiaryRecordState());
        state.ownerUuid = ownerUuid;
        if (ownerName != null && !ownerName.isBlank()) {
            state.ownerName = ownerName;
        }
        state.snapshot = snapshot.clone();
        state.snapshotUpdatedAt = Instant.now().getEpochSecond();
        markDirty();
    }

    public void markTrackedScopeInactive(DiaryLocationType type, UUID holderUuid, UUID worldUuid,
                                         Integer x, Integer y, Integer z, Set<String> observedKeys) {
        long now = Instant.now().getEpochSecond();
        boolean changed = false;
        for (DiaryRecordState state : diaryRecords.values()) {
            for (int i = 0; i < state.locations.size(); i++) {
                DiaryLocationRecord location = state.locations.get(i);
                boolean sameScope = location.type() == type
                        && Objects.equals(location.holderUuid(), holderUuid)
                        && Objects.equals(location.worldUuid(), worldUuid)
                        && Objects.equals(location.x(), x)
                        && Objects.equals(location.y(), y)
                        && Objects.equals(location.z(), z);
                if (sameScope && location.active() && !observedKeys.contains(location.identityKey())) {
                    state.locations.set(i, location.inactive(now));
                    changed = true;
                }
            }
        }
        if (changed) {
            diaryRecords.values().forEach(record -> record.location = mostRecentActive(record.locations));
            rebuildLocationIndexes();
            markDirty();
        }
    }

    public void markEntityLocationInactive(UUID entityUuid) {
        if (entityUuid == null) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        boolean changed = false;
        for (DiaryRecordState state : diaryRecords.values()) {
            for (int i = 0; i < state.locations.size(); i++) {
                DiaryLocationRecord location = state.locations.get(i);
                if (location.active() && entityUuid.equals(location.entityUuid())) {
                    state.locations.set(i, location.inactive(now));
                    changed = true;
                }
            }
        }
        if (changed) {
            diaryRecords.values().forEach(record -> record.location = mostRecentActive(record.locations));
            rebuildLocationIndexes();
            markDirty();
        }
    }

    public void markAllLocationsInactive(String diaryId) {
        DiaryRecordState state = diaryRecords.get(diaryId);
        if (state == null) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        boolean changed = false;
        for (int i = 0; i < state.locations.size(); i++) {
            DiaryLocationRecord location = state.locations.get(i);
            if (location.active()) {
                state.locations.set(i, location.inactive(now));
                changed = true;
            }
        }
        if (changed) {
            state.location = mostRecentActive(state.locations);
            rebuildLocationIndexes();
            markDirty();
        }
    }

    private void rebuildLocationIndexes() {
        locationDiaryIdsByHolder.clear();
        locationDiaryIdsByChunk.clear();
        diaryIdsWithActiveLocations.clear();
        for (Map.Entry<String, DiaryRecordState> entry : diaryRecords.entrySet()) {
            String diaryId = entry.getKey();
            for (DiaryLocationRecord location : entry.getValue().locations) {
                if (location.holderUuid() != null) {
                    locationDiaryIdsByHolder.computeIfAbsent(location.holderUuid(), ignored -> new HashSet<>())
                            .add(diaryId);
                }
                if ((location.worldUuid() != null || location.worldName() != null)
                        && location.x() != null && location.z() != null) {
                    String world = location.worldUuid() == null ? location.worldName() : location.worldUuid().toString();
                    String chunkKey = world + ":" + (location.x() >> 4) + ":" + (location.z() >> 4);
                    locationDiaryIdsByChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(diaryId);
                }
                if (location.active()) {
                    diaryIdsWithActiveLocations.add(diaryId);
                }
            }
        }
    }

    public TrackedDiaryRecord getTrackedDiary(String diaryId) {
        DiaryRecordState state = diaryRecords.get(diaryId);
        if (state == null) {
            return null;
        }
        return new TrackedDiaryRecord(diaryId, state.ownerUuid, state.ownerName,
                state.snapshot == null ? null : state.snapshot.clone(), mostRecentActive(state.locations),
                state.locations, state.snapshotUpdatedAt);
    }

    public List<DiaryLocationRecord> getLocations(String diaryId, boolean activeOnly) {
        DiaryRecordState state = diaryRecords.get(diaryId);
        if (state == null) {
            return List.of();
        }
        return state.locations.stream().filter(location -> !activeOnly || location.active()).toList();
    }

    public void addPurgeOperation(PurgeOperation operation) {
        operation.attachDirtyCallback(this::markDirty);
        purgeOperations.put(operation.operationId(), operation);
        markDirty();
    }

    public PurgeOperation getPurgeOperation(UUID operationId) {
        return purgeOperations.get(operationId);
    }

    public List<PurgeOperation> getPurgeOperations() {
        return List.copyOf(purgeOperations.values());
    }

    public List<PurgeOperation> getPurgeOperationsForDiary(String diaryId) {
        return purgeOperations.values().stream()
                .filter(operation -> operation.diaryId().equals(diaryId))
                .sorted((left, right) -> Long.compare(right.startedAt(), left.startedAt()))
                .toList();
    }

    public List<PurgeOperation> getActivePurgeOperations() {
        return purgeOperations.values().stream().filter(operation -> !operation.terminal()).toList();
    }

    public void purgeOperationChanged() {
        markDirty();
    }

    public String findDiaryIdByOwner(UUID ownerUuid) {
        PlayerRecord record = records.get(ownerUuid);
        return record == null ? null : record.diaryId;
    }

    public TrackedDiaryRecord findTrackedDiaryByOwner(UUID ownerUuid) {
        String diaryId = findDiaryIdByOwner(ownerUuid);
        return diaryId == null ? null : getTrackedDiary(diaryId);
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
        List<String> matches = diaryRecords.keySet().stream().filter(id -> id.startsWith(query)).limit(2).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    public boolean isAmbiguousDiaryIdPrefix(String query) {
        return query != null && diaryRecords.keySet().stream().filter(id -> id.startsWith(query)).limit(2).count() > 1;
    }

    public PurgeOperation getActivePurgeOperation(String diaryId) {
        return purgeOperations.values().stream()
                .filter(operation -> operation.diaryId().equals(diaryId) && !operation.terminal())
                .findFirst().orElse(null);
    }

    public void reconcileCompetingPurgeOperations() {
        Map<String, List<PurgeOperation>> activeByDiary = new HashMap<>();
        getActivePurgeOperations().forEach(operation ->
                activeByDiary.computeIfAbsent(operation.diaryId(), ignored -> new ArrayList<>()).add(operation));
        long now = Instant.now().getEpochSecond();
        for (List<PurgeOperation> operations : activeByDiary.values()) {
            if (operations.size() < 2) {
                continue;
            }
            operations.sort((left, right) -> {
                if (left.restorationOccurred() != right.restorationOccurred()) {
                    return left.restorationOccurred() ? -1 : 1;
                }
                return Long.compare(left.startedAt(), right.startedAt());
            });
            for (int i = 1; i < operations.size(); i++) {
                PurgeOperation cancelled = operations.get(i);
                cancelled.addError("Cancelled during migration because another active purge owns this diary");
                cancelled.setState(PurgeState.CANCELLED);
                cancelled.setCompletedAt(now);
            }
        }
    }

    public Set<UUID> getCredibleOfflineHolders(String diaryId) {
        Set<UUID> holders = new HashSet<>();
        DiaryRecordState state = diaryRecords.get(diaryId);
        if (state != null) {
            state.locations.stream()
                    .filter(DiaryLocationRecord::active)
                    .filter(location -> location.type() == DiaryLocationType.PLAYER_INVENTORY
                            || location.type() == DiaryLocationType.PLAYER_ENDER_CHEST)
                    .map(DiaryLocationRecord::holderUuid)
                    .filter(Objects::nonNull)
                    .forEach(holders::add);
        }
        return holders;
    }

    public void reconcileLegacyPendingRemovals(String diaryId) {
        boolean changed = false;
        for (PlayerRecord record : records.values()) {
            changed |= record.pendingRemovals.removeIf(removal -> diaryId.equals(removal.diaryId()));
        }
        if (changed) {
            markDirty();
        }
    }

    public Set<UUID> getTrackedOwners() {
        return Set.copyOf(records.keySet());
    }

    public int getIssuedPlayerCount() {
        int count = 0;
        for (PlayerRecord record : records.values()) {
            if (record.issuedAt != null && record.diaryId != null) {
                count++;
            }
        }
        return count;
    }

    public int getTrackedDiaryCount() {
        return diaryRecords.size();
    }

    public List<TrackedDiaryRecord> getRecentTrackedDiaries(int limit) {
        if (limit <= 0 || diaryRecords.isEmpty()) {
            return Collections.emptyList();
        }
        return diaryRecords.entrySet().stream()
                .map(entry -> getTrackedDiary(entry.getKey()))
                .filter(Objects::nonNull)
                .sorted((left, right) -> Long.compare(
                        right.lastKnownLocation() == null ? 0L : right.lastKnownLocation().updatedAtEpochSeconds(),
                        left.lastKnownLocation() == null ? 0L : left.lastKnownLocation().updatedAtEpochSeconds()
                ))
                .limit(limit)
                .toList();
    }

    public void flushIfDirty() {
        if (isDirtyAndIdle()) {
            flushNow();
        }
    }

    public void pruneRetainedState() {
        long now = Instant.now().getEpochSecond();
        if (now - lastPruneAt < 3600L) {
            return;
        }
        lastPruneAt = now;
        long operationCutoff = now - Math.max(1L,
                plugin.getConfig().getLong("purge.retention.completed-operation-days", 30L)) * 86400L;
        boolean changed = pruneOperations(now, operationCutoff);
        long locationCutoff = now - Math.max(1L,
                plugin.getConfig().getLong("purge.retention.inactive-location-days", 90L)) * 86400L;
        int maxLocations = Math.max(1,
                plugin.getConfig().getInt("purge.retention.max-locations-per-diary", 100));
        changed |= pruneLocations(locationCutoff, maxLocations);
        long deliveryCutoff = now - Math.max(1L,
                plugin.getConfig().getLong("delivery.audit-retention-days", 30L)) * 86400L;
        changed |= records.values().stream().map(record -> record.pendingDeliveries)
                .map(deliveries -> deliveries.removeIf(delivery -> delivery.lifecycle() == DeliveryLifecycle.DELIVERED
                        && delivery.deliveredAt() > 0L && delivery.deliveredAt() < deliveryCutoff))
                .reduce(false, Boolean::logicalOr);
        if (changed) {
            rebuildLocationIndexes();
            markDirty();
        }
    }

    private boolean pruneOperations(long now, long cutoff) {
        return purgeOperations.values().removeIf(operation ->
                operation.terminal() && operation.completedAt() > 0L
                        && operation.completedAt() < cutoff && operation.watchUntil() < now);
    }

    private boolean pruneLocations(long cutoff, int maxLocations) {
        boolean changed = false;
        for (DiaryRecordState state : diaryRecords.values()) {
            changed |= state.locations.removeIf(location ->
                    !location.active() && location.lastSeenAtEpochSeconds() < cutoff);
            if (state.locations.size() > maxLocations) {
                state.locations.sort((left, right) -> Long.compare(
                        right.lastSeenAtEpochSeconds(), left.lastSeenAtEpochSeconds()));
                state.locations.subList(maxLocations, state.locations.size()).clear();
                changed = true;
            }
            state.location = mostRecentActive(state.locations);
        }
        return changed;
    }

    public void flushNow() {
        synchronized (stateLock) {
            if (!dirty) {
                countSaveSkipped();
                return;
            }
            if (saveQueued) {
                countSaveSkipped();
                return;
            }
        }

        SaveSnapshot snapshot;
        try {
            snapshot = createSnapshot();
        } catch (IOException ex) {
            if (performanceMonitor != null) {
                performanceMonitor.yamlSaveFailed();
            }
            plugin.getLogger().warning("Failed to serialize diaries.yml snapshot: " + ex.getMessage());
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (stateLock) {
            if (saveQueued) {
                countSaveSkipped();
                return;
            }
            saveQueued = true;
            runningSave = future;
        }
        if (performanceMonitor != null) {
            performanceMonitor.yamlSaveQueued();
            performanceMonitor.yamlSaveRunning(1);
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> saveSnapshot(snapshot, future, false));
    }

    /**
     * Persists the current version and completes only after the atomic move succeeds.
     * Callers must not touch Bukkit state from the completion thread.
     */
    public CompletableFuture<Void> flushDurably() {
        synchronized (stateLock) {
            if (!dirty && runningSave == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (runningSave != null) {
                return runningSave.thenCompose(ignored -> flushDurably());
            }
        }
        flushNow();
        synchronized (stateLock) {
            if (runningSave != null) {
                return runningSave;
            }
            return dirty ? CompletableFuture.failedFuture(new IOException("Unable to persist diaries.yml"))
                    : CompletableFuture.completedFuture(null);
        }
    }

    public void flushNowBlocking(String reason) {
        waitForRunningSave(reason);
        synchronized (stateLock) {
            if (!dirty) {
                countSaveSkipped();
                return;
            }
        }

        SaveSnapshot snapshot;
        try {
            snapshot = createSnapshot();
        } catch (IOException ex) {
            if (performanceMonitor != null) {
                performanceMonitor.yamlSaveFailed();
            }
            plugin.getLogger().warning("Failed to serialize diaries.yml snapshot during " + reason + ": " + ex.getMessage());
            return;
        }
        saveSnapshot(snapshot, null, true);
    }

    private SaveSnapshot createSnapshot() throws IOException {
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
                data.set(basePath + ".itemBase64", ItemIO.toBase64(delivery.item()));
                data.set(basePath + ".token", delivery.token() == null ? null : delivery.token().toString());
                data.set(basePath + ".lifecycle", delivery.lifecycle().name());
                data.set(basePath + ".createdAt", delivery.createdAt());
                data.set(basePath + ".claimedAt", delivery.claimedAt());
                data.set(basePath + ".deliveredAt", delivery.deliveredAt());
                data.set(basePath + ".lastPersistenceError", delivery.lastPersistenceError());
            }

            int removalIndex = 0;
            for (PendingRemoval pendingRemoval : record.pendingRemovals) {
                String basePath = "pendingRemovals." + playerKey + "." + removalIndex++;
                data.set(basePath + ".diaryId", pendingRemoval.diaryId());
                data.set(basePath + ".locationType", pendingRemoval.locationType().name());
                data.set(basePath + ".holderUuid", pendingRemoval.holderUuid() == null ? null : pendingRemoval.holderUuid().toString());
            }
        }

        for (PlayerIdentity identity : identities.values()) {
            String base = "identities." + identity.uuid();
            data.set(base + ".name", identity.currentName());
            data.set(base + ".aliases", identity.aliases().stream().toList());
            data.set(base + ".lastSeen", identity.lastSeen());
            data.set(base + ".xuid", identity.xuid());
            data.set(base + ".platform", identity.platform());
        }

        for (Map.Entry<String, DiaryRecordState> entry : diaryRecords.entrySet()) {
            String diaryId = entry.getKey();
            DiaryRecordState state = entry.getValue();
            String basePath = "trackedDiaries." + diaryId;
            data.set(basePath + ".ownerUuid", state.ownerUuid == null ? null : state.ownerUuid.toString());
            data.set(basePath + ".ownerName", state.ownerName);
            data.set(basePath + ".snapshotBase64", state.snapshot == null ? null : ItemIO.toBase64(state.snapshot));
            data.set(basePath + ".snapshotUpdatedAt", state.snapshotUpdatedAt);
            if (state.location != null) {
                ConfigurationSection locationSection = data.createSection(basePath + ".location");
                state.location.writeTo(locationSection);
            }
            for (int i = 0; i < state.locations.size(); i++) {
                ConfigurationSection locationSection = data.createSection(basePath + ".locations." + i);
                state.locations.get(i).writeTo(locationSection);
            }
        }

        for (PurgeOperation operation : purgeOperations.values()) {
            writePurgeOperation(data, operation);
        }

        return new SaveSnapshot(data, currentDirtyVersion());
    }

    private void saveSnapshot(SaveSnapshot snapshot, CompletableFuture<Void> future, boolean blocking) {
        boolean success = false;
        try {
            if (persistenceWriter == null) {
                writeAtomically(snapshot.data());
            } else {
                persistenceWriter.write(snapshot.data());
            }
            success = true;
            if (performanceMonitor != null) {
                performanceMonitor.yamlSaveFlushed();
            }
        } catch (IOException ex) {
            if (performanceMonitor != null) {
                performanceMonitor.yamlSaveFailed();
            }
            plugin.getLogger().warning("Failed to save diaries.yml" + (blocking ? " during blocking flush" : "") + ": " + ex.getMessage());
        } finally {
            synchronized (stateLock) {
                if (success && dirtyVersion == snapshot.version()) {
                    dirty = false;
                }
                saveQueued = false;
                if (runningSave == future) {
                    runningSave = null;
                }
            }
            if (performanceMonitor != null) {
                performanceMonitor.yamlSaveRunning(0);
            }
            if (future != null) {
                if (success) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(new IOException("diaries.yml save failed"));
                }
            }
            if (success && blocking) {
                plugin.getLogger().info("DiaryKeeper diaries.yml flush completed during blocking flush.");
            }
        }
    }

    private void writeAtomically(FileConfiguration data) throws IOException {
        synchronized (fileSaveLock) {
            Files.createDirectories(file.getParentFile().toPath());
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            data.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void waitForRunningSave(String reason) {
        CompletableFuture<Void> future;
        synchronized (stateLock) {
            future = runningSave;
        }
        if (future == null) {
            return;
        }
        plugin.getLogger().info("Waiting for running diaries.yml async save before " + reason + " flush.");
        try {
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while waiting for diaries.yml async save before " + reason + ".");
        } catch (ExecutionException ex) {
            plugin.getLogger().warning("diaries.yml async save failed before " + reason + ": " + ex.getMessage());
        }
    }

    private boolean isDirtyAndIdle() {
        synchronized (stateLock) {
            return dirty && !saveQueued;
        }
    }

    private void countSaveSkipped() {
        if (performanceMonitor != null) {
            performanceMonitor.yamlSaveSkipped();
        }
    }

    private int currentDirtyVersion() {
        synchronized (stateLock) {
            return dirtyVersion;
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

    private void loadIdentities(ConfigurationSection section) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            UUID uuid = parseUuid(key);
            if (uuid == null) continue;
            PlayerIdentity identity = new PlayerIdentity(uuid, section.getString(key + ".name"), section.getLong(key + ".lastSeen"));
            for (String alias : section.getStringList(key + ".aliases")) identity.addAlias(alias);
            identity.loadFloodgate(section.getString(key + ".xuid"), section.getString(key + ".platform"));
            identities.put(uuid, identity);
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
                    ItemStack item = readItem(pending, basePath + ".itemBase64", basePath + ".item");
                    if (item != null) {
                        long createdAt = pending.getLong(basePath + ".createdAt", file.lastModified() / 1000L);
                        record.pendingDeliveries.addLast(new PendingDelivery(parseReason(rawReason), item,
                                parseUuid(pending.getString(basePath + ".token")), parseEnum(DeliveryLifecycle.class,
                                pending.getString(basePath + ".lifecycle"), DeliveryLifecycle.QUEUED), createdAt,
                                pending.getLong(basePath + ".claimedAt", 0L),
                                pending.getLong(basePath + ".deliveredAt", 0L),
                                pending.getString(basePath + ".lastPersistenceError")));
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
                DiaryLocationType parsedType = parseLocationType(locationType, "pendingRemovals." + basePath + ".locationType");
                if (diaryId != null) {
                    record.pendingRemovals.addLast(new PendingRemoval(
                            diaryId,
                            parsedType,
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
            state.ownerName = section.getString("ownerName");
            state.snapshot = readItem(section, "snapshotBase64", "snapshot");
            state.snapshotUpdatedAt = section.getLong("snapshotUpdatedAt", 0L);
            ConfigurationSection locationSection = section.getConfigurationSection("location");
            if (locationSection != null) {
                state.location = DiaryLocationRecord.readFrom(locationSection);
                if (state.location.type() == DiaryLocationType.UNKNOWN && locationSection.contains("type")) {
                    plugin.getLogger().warning("Unknown tracked diary location type at trackedDiaries." + diaryId + ".location.type: " + locationSection.getString("type"));
                }
            }
            ConfigurationSection locationsSection = section.getConfigurationSection("locations");
            if (locationsSection != null) {
                List<String> keys = new ArrayList<>(locationsSection.getKeys(false));
                keys.sort(String::compareTo);
                for (String key : keys) {
                    ConfigurationSection entry = locationsSection.getConfigurationSection(key);
                    if (entry != null) {
                        state.locations.add(DiaryLocationRecord.readFrom(entry));
                    }
                }
            }
            if (state.locations.isEmpty() && state.location != null) {
                state.locations.add(state.location);
            }
            for (int i = 0; i < state.locations.size(); i++) {
                DiaryLocationRecord location = state.locations.get(i);
                if (location.worldUuid() == null && location.worldName() != null
                        && Bukkit.getWorld(location.worldName()) != null) {
                    state.locations.set(i, location.withWorldUuid(Bukkit.getWorld(location.worldName()).getUID()));
                    markDirty();
                }
            }
            state.location = mostRecentActive(state.locations);
            diaryRecords.put(diaryId, state);
        }
    }

    public boolean migratePendingDeliveryIds() {
        boolean changed = false;
        for (PlayerRecord record : records.values()) {
            List<PendingDelivery> migrated = new ArrayList<>();
            for (PendingDelivery delivery : record.pendingDeliveries) {
                if (delivery.token() == null) {
                    migrated.add(new PendingDelivery(delivery.reason(), delivery.item(), UUID.randomUUID(),
                            delivery.lifecycle(), delivery.claimedAt()));
                    changed = true;
                } else {
                    migrated.add(delivery);
                }
            }
            if (changed) {
                record.pendingDeliveries.clear();
                record.pendingDeliveries.addAll(migrated);
            }
        }
        if (changed) {
            markDirty();
        }
        return changed;
    }

    public boolean pendingDeliveryIdsMigrated() {
        return pendingDeliveryIdsMigrated;
    }

    public void observeIdentity(UUID uuid, String name) {
        long now = Instant.now().getEpochSecond();
        PlayerIdentity existing = identities.get(uuid);
        if (existing == null) {
            identities.put(uuid, new PlayerIdentity(uuid, name, now));
            markDirty();
        } else if (existing.observe(name, now)) {
            markDirty();
        }
    }

    public void observeFloodgateIdentity(UUID uuid, String name, String xuid, String platform) {
        long now = Instant.now().getEpochSecond();
        PlayerIdentity identity = identities.computeIfAbsent(uuid,
                ignored -> new PlayerIdentity(uuid, name, now));
        if (identity.observeFloodgate(name, xuid, platform, now)) markDirty();
    }

    public IdentityResolution resolveStoredPlayer(String input) {
        try { return IdentityResolution.found(UUID.fromString(input)); } catch (IllegalArgumentException ignored) { }
        List<UUID> currentMatches = identities.values().stream()
                .filter(identity -> identity.currentName() != null
                        && identity.currentName().equalsIgnoreCase(input))
                .map(PlayerIdentity::uuid).distinct().toList();
        if (currentMatches.size() == 1) return IdentityResolution.found(currentMatches.getFirst());
        if (currentMatches.size() > 1) return IdentityResolution.ambiguous();
        List<UUID> aliasMatches = identities.values().stream()
                .filter(identity -> identity.aliases().stream()
                        .anyMatch(alias -> alias.equalsIgnoreCase(input)))
                .map(PlayerIdentity::uuid).distinct().toList();
        return aliasMatches.size() == 1 ? IdentityResolution.found(aliasMatches.getFirst())
                : aliasMatches.isEmpty() ? IdentityResolution.notFound() : IdentityResolution.ambiguous();
    }

    PlayerIdentity identityForTesting(UUID uuid) {
        return identities.get(uuid);
    }

    private void migrateIdentitiesFromTrackedDiaries() {
        for (DiaryRecordState state : diaryRecords.values()) {
            if (state.ownerUuid != null && state.ownerName != null) {
                PlayerIdentity identity = identities.get(state.ownerUuid);
                if (identity == null) {
                    identities.put(state.ownerUuid, new PlayerIdentity(state.ownerUuid,
                            state.ownerName, Math.max(0L, state.snapshotUpdatedAt)));
                    markDirty();
                } else if (state.snapshotUpdatedAt > identity.lastSeen()
                        ? identity.observe(state.ownerName, state.snapshotUpdatedAt)
                        : identity.addAlias(state.ownerName)) {
                    markDirty();
                }
            }
        }
    }

    private void writePurgeOperation(FileConfiguration data, PurgeOperation operation) throws IOException {
        String base = "purgeOperations." + operation.operationId();
        data.set(base + ".diaryId", operation.diaryId());
        data.set(base + ".ownerUuid", operation.ownerUuid() == null ? null : operation.ownerUuid().toString());
        data.set(base + ".adminUuid", operation.adminUuid() == null ? null : operation.adminUuid().toString());
        data.set(base + ".destination", operation.destination().name());
        data.set(base + ".state", operation.state().name());
        data.set(base + ".startedAt", operation.startedAt());
        data.set(base + ".completedAt", operation.completedAt());
        data.set(base + ".snapshotBase64", operation.snapshot() == null ? null : ItemIO.toBase64(operation.snapshot()));
        data.set(base + ".onlinePlayersScanned", operation.onlinePlayersScanned());
        data.set(base + ".loadedChunksScanned", operation.loadedChunksScanned());
        data.set(base + ".pendingDeliveriesRemoved", operation.pendingDeliveriesRemoved());
        data.set(base + ".restorationOccurred", operation.restorationOccurred());
        data.set(base + ".replacementHolder", operation.replacementHolder() == null ? null : operation.replacementHolder().toString());
        data.set(base + ".watchUntil", operation.watchUntil());
        data.set(base + ".partialRestoreConfirmed", operation.partialRestoreConfirmed());
        data.set(base + ".deliveryToken", operation.deliveryToken() == null ? null : operation.deliveryToken().toString());
        data.set(base + ".verificationRequired", operation.verificationRequired());
        data.set(base + ".verificationRemovedBaseline", operation.verificationRemovedBaseline());
        data.set(base + ".verificationGeneration", operation.verificationGeneration());
        data.set(base + ".pendingPlayers", operation.pendingPlayers().stream().map(UUID::toString).toList());
        data.set(base + ".errors", operation.errors());
        for (Map.Entry<String, Integer> count : operation.removedByLocation().entrySet()) {
            data.set(base + ".removed." + count.getKey(), count.getValue());
        }
        for (int i = 0; i < operation.chunkTargets().size(); i++) {
            PurgeChunkTarget target = operation.chunkTargets().get(i);
            String targetBase = base + ".chunks." + i;
            data.set(targetBase + ".worldUuid", target.worldUuid() == null ? null : target.worldUuid().toString());
            data.set(targetBase + ".worldName", target.worldName());
            data.set(targetBase + ".chunkX", target.chunkX());
            data.set(targetBase + ".chunkZ", target.chunkZ());
            data.set(targetBase + ".blockX", target.blockX());
            data.set(targetBase + ".blockY", target.blockY());
            data.set(targetBase + ".blockZ", target.blockZ());
            data.set(targetBase + ".completed", target.completed());
            data.set(targetBase + ".attempts", target.attempts());
            data.set(targetBase + ".error", target.error());
            data.set(targetBase + ".loading", false);
        }
    }

    private void loadPurgeOperations(ConfigurationSection operations) {
        if (operations == null) {
            return;
        }
        for (String key : operations.getKeys(false)) {
            UUID operationId = parseUuid(key);
            ConfigurationSection section = operations.getConfigurationSection(key);
            if (operationId == null || section == null) {
                continue;
            }
            String diaryId = section.getString("diaryId");
            ItemStack snapshot = readItem(section, "snapshotBase64", "snapshot");
            if (diaryId == null) {
                continue;
            }
            PurgeOperation operation = new PurgeOperation(
                    operationId,
                    diaryId,
                    parseUuid(section.getString("ownerUuid")),
                    parseUuid(section.getString("adminUuid")),
                    parseEnum(PurgeDestination.class, section.getString("destination"), PurgeDestination.NONE),
                    section.getLong("startedAt", 0L),
                    snapshot
            );
            operation.setState(parseEnum(PurgeState.class, section.getString("state"), PurgeState.QUEUED));
            operation.setCompletedAt(section.getLong("completedAt", 0L));
            operation.setOnlinePlayersScanned(section.getInt("onlinePlayersScanned"));
            operation.setLoadedChunksScanned(section.getInt("loadedChunksScanned"));
            operation.setPendingDeliveriesRemoved(section.getInt("pendingDeliveriesRemoved"));
            operation.setRestorationOccurred(section.getBoolean("restorationOccurred"));
            operation.setReplacementHolder(parseUuid(section.getString("replacementHolder")));
            operation.setWatchUntil(section.getLong("watchUntil"));
            operation.setPartialRestoreConfirmed(section.getBoolean("partialRestoreConfirmed"));
            for (String playerId : section.getStringList("pendingPlayers")) {
                UUID uuid = parseUuid(playerId);
                if (uuid != null) {
                    operation.loadPendingPlayer(uuid);
                }
            }
            section.getStringList("errors").forEach(operation::loadError);
            ConfigurationSection removed = section.getConfigurationSection("removed");
            if (removed != null) {
                for (String location : removed.getKeys(false)) {
                    operation.loadRemoved(location, removed.getInt(location));
                }
            }
            ConfigurationSection chunks = section.getConfigurationSection("chunks");
            if (chunks != null) {
                List<String> chunkKeys = new ArrayList<>(chunks.getKeys(false));
                chunkKeys.sort(String::compareTo);
                for (String chunkKey : chunkKeys) {
                    ConfigurationSection targetSection = chunks.getConfigurationSection(chunkKey);
                    if (targetSection == null) {
                        continue;
                    }
                    PurgeChunkTarget target = new PurgeChunkTarget(
                            parseUuid(targetSection.getString("worldUuid")),
                            targetSection.getString("worldName"),
                            targetSection.getInt("chunkX"),
                            targetSection.getInt("chunkZ"),
                            targetSection.contains("blockX") ? targetSection.getInt("blockX") : null,
                            targetSection.contains("blockY") ? targetSection.getInt("blockY") : null,
                            targetSection.contains("blockZ") ? targetSection.getInt("blockZ") : null
                    );
                    target.loadState(targetSection.getBoolean("completed"),
                            targetSection.getInt("attempts"), targetSection.getString("error"));
                    operation.loadChunkTarget(target);
                }
            }
            operation.setDeliveryToken(parseUuid(section.getString("deliveryToken")));
            operation.setVerificationRequired(section.getBoolean("verificationRequired", true));
            operation.setVerificationRemovedBaseline(section.getInt("verificationRemovedBaseline"));
            operation.setVerificationGeneration(section.getInt("verificationGeneration"));
            operation.attachDirtyCallback(this::markDirty);
            purgeOperations.put(operationId, operation);
        }
    }

    private DiaryLocationRecord mostRecentActive(List<DiaryLocationRecord> locations) {
        return locations.stream().filter(DiaryLocationRecord::active)
                .max((left, right) -> Long.compare(
                        left.lastSeenAtEpochSeconds(), right.lastSeenAtEpochSeconds())).orElse(null);
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String raw, T fallback) {
        try {
            return raw == null ? fallback : Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
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
        synchronized (stateLock) {
            dirty = true;
            dirtyVersion++;
        }
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
            return input == null ? DeliveryReason.VOID_RETURN : DeliveryReason.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown delivery reason in diaries.yml: " + input + ". Using VOID_RETURN.");
            return DeliveryReason.VOID_RETURN;
        }
    }

    private DiaryLocationType parseLocationType(String input, String path) {
        try {
            return input == null ? DiaryLocationType.UNKNOWN : DiaryLocationType.valueOf(input.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Unknown diary location type at " + path + ": " + input + ". Using UNKNOWN.");
            return DiaryLocationType.UNKNOWN;
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

    private ItemStack readItem(ConfigurationSection section, String base64Path, String legacyPath) {
        String encoded = section.getString(base64Path);
        if (encoded != null && !encoded.isBlank()) {
            try {
                return ItemIO.fromBase64(encoded);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to deserialize item at " + section.getCurrentPath() + "." + base64Path + ": " + ex.getMessage());
                return null;
            }
        }
        return section.getItemStack(legacyPath);
    }

    private record SaveSnapshot(FileConfiguration data, int version) {}

    @FunctionalInterface
    interface PersistenceWriter {
        void write(FileConfiguration data) throws IOException;
    }
}
