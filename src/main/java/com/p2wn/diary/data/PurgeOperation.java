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

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
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
    private UUID deliveryToken;
    private boolean verificationRequired = true;
    private int verificationRemovedBaseline;
    private transient Runnable dirtyCallback = () -> { };

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
    public Set<UUID> pendingPlayers() { return Set.copyOf(pendingPlayers); }
    public List<PurgeChunkTarget> chunkTargets() { return List.copyOf(chunkTargets); }
    public Map<String, Integer> removedByLocation() { return Map.copyOf(removedByLocation); }
    public List<String> errors() { return List.copyOf(errors); }
    public PurgeState state() { return state; }
    public long completedAt() { return completedAt; }
    public int onlinePlayersScanned() { return onlinePlayersScanned; }
    public int loadedChunksScanned() { return loadedChunksScanned; }
    public int pendingDeliveriesRemoved() { return pendingDeliveriesRemoved; }
    public boolean restorationOccurred() { return restorationOccurred; }
    public UUID replacementHolder() { return replacementHolder; }
    public long watchUntil() { return watchUntil; }
    public boolean partialRestoreConfirmed() { return partialRestoreConfirmed; }
    public UUID deliveryToken() { return deliveryToken; }
    public boolean verificationRequired() { return verificationRequired; }
    public int verificationRemovedBaseline() { return verificationRemovedBaseline; }

    public void attachDirtyCallback(Runnable callback) {
        dirtyCallback = callback == null ? () -> { } : callback;
        chunkTargets.forEach(target -> target.attachDirtyCallback(dirtyCallback));
    }

    public void setState(PurgeState value) { state = value; changed(); }
    public void setCompletedAt(long value) { completedAt = value; changed(); }
    public void setOnlinePlayersScanned(int value) { onlinePlayersScanned = value; changed(); }
    public void setLoadedChunksScanned(int value) { loadedChunksScanned = value; changed(); }
    public void setPendingDeliveriesRemoved(int value) { pendingDeliveriesRemoved = value; changed(); }
    public void setRestorationOccurred(boolean value) { restorationOccurred = value; changed(); }
    public void setReplacementHolder(UUID value) { replacementHolder = value; changed(); }
    public void setWatchUntil(long value) { watchUntil = value; changed(); }
    public void setPartialRestoreConfirmed(boolean value) { partialRestoreConfirmed = value; changed(); }
    public void setDeliveryToken(UUID value) { deliveryToken = value; changed(); }
    public void setVerificationRequired(boolean value) { verificationRequired = value; changed(); }
    public void setVerificationRemovedBaseline(int value) { verificationRemovedBaseline = value; changed(); }

    public boolean addPendingPlayer(UUID playerId) { boolean result = pendingPlayers.add(playerId); if (result) changed(); return result; }
    public boolean completePlayer(UUID playerId) { boolean result = pendingPlayers.remove(playerId); if (result) changed(); return result; }
    public void addChunkTarget(PurgeChunkTarget target) { target.attachDirtyCallback(dirtyCallback); chunkTargets.add(target); changed(); }
    public void addError(String error) { errors.add(error); pruneErrors(); changed(); }
    public void clearErrors() { if (!errors.isEmpty()) { errors.clear(); changed(); } }
    void loadPendingPlayer(UUID playerId) { pendingPlayers.add(playerId); }
    void loadError(String error) { errors.add(error); }
    void loadRemoved(String location, int count) { removedByLocation.put(location, count); }
    void loadChunkTarget(PurgeChunkTarget target) { chunkTargets.add(target); }

    public void addRemoved(String location, int amount) {
        if (amount > 0) {
            removedByLocation.merge(location, amount, Integer::sum);
            changed();
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

    private void pruneErrors() {
        while (errors.size() > 25) {
            errors.removeFirst();
        }
    }

    private void changed() {
        dirtyCallback.run();
    }
}
