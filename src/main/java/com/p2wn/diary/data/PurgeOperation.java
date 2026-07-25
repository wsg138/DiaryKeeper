package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PurgeOperation {

    private final UUID operationId;
    private final String diaryId;
    private final UUID ownerUuid;
    private final UUID adminUuid;
    private final PurgeDestination destination;
    private final long startedAt;
    private final ItemStack snapshot;
    private final Set<UUID> pendingPlayers = new LinkedHashSet<>();
    private final List<PurgeChunkTarget> chunkTargets = new ArrayList<>();
    private final Map<String, Integer> removedByLocation = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private PurgeState state;
    private long completedAt;
    private int onlinePlayersScanned;
    private int loadedChunksScanned;
    private int pendingDeliveriesRemoved;
    private boolean restorationOccurred;
    private UUID replacementHolder;
    private long watchUntil;
    private boolean partialRestoreConfirmed;

    public PurgeOperation(UUID operationId, String diaryId, UUID ownerUuid, UUID adminUuid,
                          PurgeDestination destination, long startedAt, ItemStack snapshot) {
        this.operationId = operationId;
        this.diaryId = diaryId;
        this.ownerUuid = ownerUuid;
        this.adminUuid = adminUuid;
        this.destination = destination;
        this.startedAt = startedAt;
        this.snapshot = snapshot == null ? null : snapshot.clone();
        this.state = PurgeState.QUEUED;
    }

    public static PurgeOperation create(String diaryId, UUID ownerUuid, UUID adminUuid,
                                        PurgeDestination destination, ItemStack snapshot) {
        return new PurgeOperation(UUID.randomUUID(), diaryId, ownerUuid, adminUuid,
                destination, Instant.now().getEpochSecond(), snapshot);
    }

    public UUID operationId() { return operationId; }
    public String diaryId() { return diaryId; }
    public UUID ownerUuid() { return ownerUuid; }
    public UUID adminUuid() { return adminUuid; }
    public PurgeDestination destination() { return destination; }
    public long startedAt() { return startedAt; }
    public ItemStack snapshot() { return snapshot == null ? null : snapshot.clone(); }
    public Set<UUID> pendingPlayers() { return pendingPlayers; }
    public List<PurgeChunkTarget> chunkTargets() { return chunkTargets; }
    public Map<String, Integer> removedByLocation() { return removedByLocation; }
    public List<String> errors() { return errors; }
    public PurgeState state() { return state; }
    public long completedAt() { return completedAt; }
    public int onlinePlayersScanned() { return onlinePlayersScanned; }
    public int loadedChunksScanned() { return loadedChunksScanned; }
    public int pendingDeliveriesRemoved() { return pendingDeliveriesRemoved; }
    public boolean restorationOccurred() { return restorationOccurred; }
    public UUID replacementHolder() { return replacementHolder; }
    public long watchUntil() { return watchUntil; }
    public boolean partialRestoreConfirmed() { return partialRestoreConfirmed; }

    public void setState(PurgeState state) { this.state = state; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; }
    public void setOnlinePlayersScanned(int value) { this.onlinePlayersScanned = value; }
    public void setLoadedChunksScanned(int value) { this.loadedChunksScanned = value; }
    public void setPendingDeliveriesRemoved(int value) { this.pendingDeliveriesRemoved = value; }
    public void setRestorationOccurred(boolean value) { this.restorationOccurred = value; }
    public void setReplacementHolder(UUID value) { this.replacementHolder = value; }
    public void setWatchUntil(long value) { this.watchUntil = value; }
    public void setPartialRestoreConfirmed(boolean value) { this.partialRestoreConfirmed = value; }

    public void addRemoved(String location, int amount) {
        if (amount > 0) {
            removedByLocation.merge(location, amount, Integer::sum);
        }
    }

    public int totalRemoved() {
        return removedByLocation.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int pendingChunks() {
        return (int) chunkTargets.stream().filter(target -> !target.completed()).count();
    }

    public boolean terminal() {
        return state == PurgeState.COMPLETED || state == PurgeState.CANCELLED || state == PurgeState.FAILED;
    }
}
