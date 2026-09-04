package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.data.DiaryLocationRecord;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PurgeChunkTarget;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.PurgeState;
import com.p2wn.diary.data.TrackedDiaryRecord;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DiaryPurgeService {

    private record PlayerTarget(UUID operationId, UUID playerId) {}
    private record ChunkTarget(UUID operationId, PurgeChunkTarget target) {}
    private record BlockPosition(int x, int y, int z) {}
    private record ChunkWork(Deque<BlockPosition> blocks, Deque<UUID> entities) {}

    private final DiaryPlugin plugin;
    private final DiaryStore store;
    private DiaryItemPurger itemPurger;
    private final Deque<PlayerTarget> onlineQueue = new ArrayDeque<>();
    private final Deque<ChunkTarget> chunkQueue = new ArrayDeque<>();
    private final Set<String> queuedPlayerKeys = new HashSet<>();
    private final Set<String> queuedChunkKeys = new HashSet<>();
    private final Map<String, ChunkWork> chunkWork = new HashMap<>();
    private final Map<String, World> ticketedChunks = new HashMap<>();
    private final Set<UUID> persistenceInFlight = new HashSet<>();
    private BukkitTask task;

    public DiaryPurgeService(DiaryPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.diaryStore();
        this.itemPurger = new DiaryItemPurger(plugin.diaryItem(),
                Math.max(1, plugin.getConfig().getInt("purge.max-recursion-depth", 4)));
    }

    public void start() {
        itemPurger = new DiaryItemPurger(plugin.diaryItem(),
                Math.max(1, plugin.getConfig().getInt("purge.max-recursion-depth", 4)));
        for (PurgeOperation operation : store.getActivePurgeOperations()) {
            if (operation.state() != PurgeState.CANCELLED) {
                enqueueRemaining(operation);
                finalizeIfReady(operation);
            }
        }
        ensureTask();
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        store.flushNowBlocking("purge shutdown");
    }

    public PurgeOperation begin(TrackedDiaryRecord record, PurgeDestination destination, Player admin) {
        if (record == null || record.snapshot() == null) {
            return null;
        }
        PurgeOperation existing = store.getActivePurgeOperation(record.diaryId());
        if (existing != null) {
            enqueueRemaining(existing);
            return existing;
        }

        UUID adminUuid = admin == null ? null : admin.getUniqueId();
        PurgeOperation operation = PurgeOperation.create(record.diaryId(), record.ownerUuid(),
                adminUuid, destination, record.snapshot());
        operation.setState(PurgeState.SCANNING_ONLINE_PLAYERS);

        Set<UUID> onlineIds = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineIds.add(player.getUniqueId());
            operation.addPendingPlayer(player.getUniqueId());
            enqueuePlayer(operation, player.getUniqueId());
        }
        int removedDeliveries = store.removeAllPendingDeliveriesByDiaryId(record.diaryId());
        store.reconcileLegacyPendingRemovals(record.diaryId());
        for (UUID playerId : store.getCredibleOfflineHolders(record.diaryId())) {
            if (!onlineIds.contains(playerId)) {
                operation.addPendingPlayer(playerId);
                analytics(DiaryAnalyticsEventType.PURGE_PENDING_PLAYER, operation,
                        playerId, "join-time removal queued");
            }
        }

        addKnownChunkTargets(operation, record.locations());
        operation.setPendingDeliveriesRemoved(removedDeliveries);
        operation.addRemoved("delivery_queue", removedDeliveries);
        store.addPurgeOperation(operation);
        store.flushIfDirty();
        analytics(DiaryAnalyticsEventType.PURGE_STARTED, operation, adminUuid, destination.name());
        log(operation, "started by " + adminLabel(admin) + ", destination=" + destination
                + ", pendingPlayers=" + operation.pendingPlayers().size()
                + ", chunks=" + operation.chunkTargets().size());
        ensureTask();
        finalizeIfReady(operation);
        return operation;
    }

    public boolean cancel(UUID operationId, String actor) {
        PurgeOperation operation = store.getPurgeOperation(operationId);
        if (operation == null || operation.terminal() || operation.restorationOccurred()) {
            return false;
        }
        operation.setState(PurgeState.CANCELLED);
        operation.setCompletedAt(Instant.now().getEpochSecond());
        store.purgeOperationChanged();
        store.flushIfDirty();
        log(operation, "cancelled by " + actor);
        return true;
    }

    public boolean resume(UUID operationId, String actor) {
        PurgeOperation operation = store.getPurgeOperation(operationId);
        if (operation == null || operation.state() == PurgeState.COMPLETED || operation.restorationOccurred()) {
            return false;
        }
        if (operation.state() == PurgeState.PARTIAL
                && plugin.getConfig().getBoolean("purge.allow-restore-on-partial-purge", false)) {
            operation.setPartialRestoreConfirmed(true);
            analytics(DiaryAnalyticsEventType.PURGE_PARTIAL, operation, operation.adminUuid(),
                    "partial restore explicitly confirmed by " + actor);
            finalizeIfReady(operation);
            store.flushIfDirty();
            return true;
        }
        operation.setState(PurgeState.QUEUED);
        operation.clearErrors();
        operation.chunkTargets().stream()
                .filter(target -> target.completed() && target.error() != null)
                .forEach(PurgeChunkTarget::resetForRetry);
        enqueueRemaining(operation);
        store.purgeOperationChanged();
        store.flushIfDirty();
        log(operation, "resumed by " + actor);
        return true;
    }

    public void processJoin(Player player) {
        boolean changed = false;
        for (PurgeOperation operation : store.getActivePurgeOperations()) {
            if (!operation.pendingPlayers().contains(player.getUniqueId())) {
                continue;
            }
            int removed = purgePlayer(player, operation.diaryId());
            operation.addRemoved("offline_player_join", removed);
            operation.completePlayer(player.getUniqueId());
            operation.setOnlinePlayersScanned(operation.onlinePlayersScanned() + 1);
            changed = true;
            logRemoval(operation, "join:" + player.getUniqueId(), removed);
            finalizeIfReady(operation);
        }
        if (changed) {
            player.updateInventory();
            store.purgeOperationChanged();
            store.flushIfDirty();
            plugin.diaryTrackerService().trackPlayerInventory(player);
            plugin.diaryTrackerService().trackEnderChest(player);
            plugin.duplicateWatcher().refreshPlayerSnapshot(player);
        }
    }

    public void onChunkLoad(Chunk chunk) {
        for (PurgeOperation operation : store.getActivePurgeOperations()) {
            PurgeChunkTarget target = findChunkTarget(operation, chunk);
            if (target == null) {
                continue;
            }
            if (target.completed()) {
                target.resetForRetry();
                chunkWork.remove(chunkWorkKey(operation.operationId(), target));
            }
            operation.setVerificationRequired(true);
            if (!target.loading()) {
                enqueueChunk(operation, target);
            }
        }
        ensureTask();
    }

    public PurgeOperation find(String query) {
        try {
            PurgeOperation operation = store.getPurgeOperation(UUID.fromString(query));
            if (operation != null) {
                return operation;
            }
        } catch (IllegalArgumentException ignored) {
        }
        String diaryId = store.findDiaryIdByExactOrPrefix(query);
        if (diaryId == null) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(query);
            diaryId = store.findDiaryIdByOwner(player.getUniqueId());
        }
        if (diaryId == null) {
            return null;
        }
        List<PurgeOperation> operations = store.getPurgeOperationsForDiary(diaryId);
        return operations.isEmpty() ? null : operations.getFirst();
    }

    public boolean restoreDuplicate(TrackedDiaryRecord record, Player requestedAdmin) {
        if (record == null || record.snapshot() == null) {
            return false;
        }
        PurgeOperation active = store.getActivePurgeOperation(record.diaryId());
        if (active != null && !active.terminal()) {
            plugin.getLogger().warning("[Diary Purge] Blocked intentional duplicate restore diary=" + record.diaryId()
                    + " activeOperation=" + active.operationId()
                    + " state=" + active.state()
                    + " requestedBy=" + adminLabel(requestedAdmin));
            return false;
        }
        UUID token = UUID.randomUUID();
        plugin.deliveryService().queue(record.ownerUuid(), DeliveryReason.RESTORE_DUPLICATE, record.snapshot(), token);
        analytics(DiaryAnalyticsEventType.RESTORE_DUPLICATE, null, requestedAdmin == null ? null : requestedAdmin.getUniqueId(),
                "intentional duplicate");
        plugin.getLogger().warning("[Diary Purge] Intentional duplicate restore diary=" + record.diaryId()
                + " requestedBy=" + adminLabel(requestedAdmin));
        return true;
    }

    public void onObservedCopy(String diaryId, String detail, int occurrenceCount) {
        long now = Instant.now().getEpochSecond();
        for (PurgeOperation operation : store.getPurgeOperationsForDiary(diaryId)) {
            if (!operation.terminal()) {
                operation.setVerificationRequired(true);
                ensureTask();
                continue;
            }
            if (operation.state() == PurgeState.COMPLETED && operation.watchUntil() >= now
                    && (operation.destination() == PurgeDestination.NONE || occurrenceCount > 1)) {
                analytics(DiaryAnalyticsEventType.POST_PURGE_COPY_FOUND, operation, null, detail);
                plugin.getLogger().warning("[Diary Purge] Copy reappeared after purge operation="
                        + operation.operationId() + " diary=" + diaryId + " location=" + detail);
                if (plugin.getConfig().getBoolean("purge.auto-remove-reappearing-copies", false)
                        && store.getPurgeOperationsForDiary(diaryId).stream()
                        .noneMatch(candidate -> !candidate.terminal())) {
                    TrackedDiaryRecord record = store.getTrackedDiary(diaryId);
                    if (record != null && record.snapshot() != null) {
                        PurgeDestination destination = operation.destination() == PurgeDestination.ADMIN
                                ? PurgeDestination.OWNER : operation.destination();
                        begin(record, destination, null);
                    }
                }
            }
        }
    }

    private void tick() {
        int maxPlayers = Math.max(1, plugin.getConfig().getInt("purge.max-players-per-tick", 2));
        int maxChunks = Math.max(1, plugin.getConfig().getInt("purge.max-chunks-per-tick", 1));
        for (int i = 0; i < maxPlayers && !onlineQueue.isEmpty(); i++) {
            processPlayerTarget(onlineQueue.removeFirst());
        }
        for (int i = 0; i < maxChunks && !chunkQueue.isEmpty(); i++) {
            processChunkTarget(chunkQueue.removeFirst());
        }
        if (onlineQueue.isEmpty() && chunkQueue.isEmpty()) {
            task.cancel();
            task = null;
            for (PurgeOperation operation : store.getActivePurgeOperations()) {
                finalizeIfReady(operation);
            }
        }
        store.flushIfDirty();
    }

    private void processPlayerTarget(PlayerTarget target) {
        queuedPlayerKeys.remove(target.operationId() + ":" + target.playerId());
        PurgeOperation operation = store.getPurgeOperation(target.operationId());
        Player player = Bukkit.getPlayer(target.playerId());
        if (operation == null || operation.terminal() || player == null || !player.isOnline()) {
            if (operation != null && !operation.terminal()) {
                operation.addPendingPlayer(target.playerId());
            }
            return;
        }
        int removed = purgePlayer(player, operation.diaryId());
        operation.addRemoved("online_player", removed);
        operation.completePlayer(player.getUniqueId());
        operation.setOnlinePlayersScanned(operation.onlinePlayersScanned() + 1);
        player.updateInventory();
        logRemoval(operation, "player:" + player.getUniqueId(), removed);
        plugin.diaryTrackerService().trackPlayerInventory(player);
        plugin.diaryTrackerService().trackEnderChest(player);
        plugin.duplicateWatcher().refreshPlayerSnapshot(player);
        finalizeIfReady(operation);
    }

    private int purgePlayer(Player player, String diaryId) {
        int removed = itemPurger.purgeInventory(player.getInventory(), diaryId);
        removed += itemPurger.purgeInventory(player.getEnderChest(), diaryId);
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != player.getInventory() && top != player.getEnderChest()) {
            removed += itemPurger.purgeInventory(top, diaryId);
        }
        return removed;
    }

    private void processChunkTarget(ChunkTarget queued) {
        queuedChunkKeys.remove(queued.operationId() + ":" + queued.target().key());
        PurgeOperation operation = store.getPurgeOperation(queued.operationId());
        PurgeChunkTarget target = queued.target();
        if (operation == null || operation.terminal() || target.completed()) {
            return;
        }
        World world = resolveWorld(target);
        if (world == null) {
            target.fail("world unavailable");
            if (retryOrFinish(operation, target)) {
                operation.addError("World unavailable for " + target.key());
            }
            analytics(DiaryAnalyticsEventType.PURGE_FAILED, operation, null, "world unavailable " + target.key());
            finalizeIfReady(operation);
            return;
        }

        if (!world.isChunkLoaded(target.chunkX(), target.chunkZ())) {
            requestAsyncChunkLoad(operation, target, world);
            return;
        }
        scanLoadedChunk(operation, target, world.getChunkAt(target.chunkX(), target.chunkZ()));
    }

    void requestAsyncChunkLoad(PurgeOperation operation, PurgeChunkTarget target, World world) {
        if (target.loading()) {
            return;
        }
        operation.setState(PurgeState.PROCESSING_KNOWN_UNLOADED_CHUNKS);
        target.setLoading(true);
        int generation = operation.verificationGeneration();
        CompletableFuture<Chunk> future = world.getChunkAtAsync(target.chunkX(), target.chunkZ(), true);
        future.whenComplete((chunk, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
            target.setLoading(false);
            PurgeOperation current = store.getPurgeOperation(operation.operationId());
            if (current != operation || operation.terminal()
                    || (operation.state() == PurgeState.VERIFYING && operation.verificationGeneration() != generation)) {
                releaseChunkTicket(operation, target);
                return;
            }
            if (failure != null || chunk == null) {
                failChunk(operation, target, failure == null ? "asynchronous chunk load failed" : failure.getMessage());
                return;
            }
            String workKey = chunkWorkKey(operation.operationId(), target);
            if (!ticketedChunks.containsKey(workKey)
                    && world.addPluginChunkTicket(target.chunkX(), target.chunkZ(), plugin)) {
                ticketedChunks.put(workKey, world);
            }
            scanLoadedChunk(operation, target, chunk);
            });
        });
    }

    private void scanLoadedChunk(PurgeOperation operation, PurgeChunkTarget target, Chunk chunk) {
        try {
            operation.setState(PurgeState.SCANNING_LOADED_CHUNKS);
            ChunkPurgeResult result = purgeChunk(chunk, operation.diaryId(), operation.operationId(), target);
            operation.addRemoved("chunk", result.removed());
            if (result.complete()) {
                operation.setLoadedChunksScanned(operation.loadedChunksScanned() + 1);
                target.complete();
                releaseChunkTicket(operation, target);
            } else {
                enqueueChunk(operation, target);
            }
            logRemoval(operation, "chunk:" + target.key(), result.removed());
        } catch (RuntimeException ex) {
            failChunk(operation, target, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        finalizeIfReady(operation);
    }

    private void failChunk(PurgeOperation operation, PurgeChunkTarget target, String message) {
            target.fail(message);
            if (retryOrFinish(operation, target)) {
                operation.addError("Chunk " + target.key() + ": " + message);
                releaseChunkTicket(operation, target);
            }
            analytics(DiaryAnalyticsEventType.PURGE_FAILED, operation, null, "chunk " + target.key());
        finalizeIfReady(operation);
    }

    private void releaseChunkTicket(PurgeOperation operation, PurgeChunkTarget target) {
        World world = ticketedChunks.remove(chunkWorkKey(operation.operationId(), target));
        if (world != null) {
            world.removePluginChunkTicket(target.chunkX(), target.chunkZ(), plugin);
        }
    }

    private boolean retryOrFinish(PurgeOperation operation, PurgeChunkTarget target) {
        int maxRetries = Math.max(1, plugin.getConfig().getInt("purge.max-chunk-retries", 3));
        if (target.attempts() < maxRetries) {
            enqueueChunk(operation, target);
            return false;
        }
        target.finishWithError(target.error());
        return true;
    }

    private record ChunkPurgeResult(int removed, boolean complete) {}

    private ChunkPurgeResult purgeChunk(Chunk chunk, String diaryId, UUID operationId, PurgeChunkTarget target) {
        int removed = 0;
        String workKey = operationId + ":" + target.key();
        ChunkWork work = chunkWork.computeIfAbsent(workKey, ignored -> createChunkWork(chunk));
        int maxBlockEntities = Math.max(1, plugin.getConfig().getInt("purge.max-block-entities-per-tick", 50));
        for (int i = 0; i < maxBlockEntities && !work.blocks().isEmpty(); i++) {
            BlockPosition position = work.blocks().removeFirst();
            BlockState state = chunk.getWorld().getBlockAt(position.x(), position.y(), position.z()).getState();
            if (state instanceof InventoryHolder holder) {
                int count = itemPurger.purgeInventory(holder.getInventory(), diaryId);
                if (count > 0) {
                    state.update(true, false);
                    removed += count;
                }
            }
        }
        int maxEntities = Math.max(1, plugin.getConfig().getInt("purge.max-entities-per-tick", 100));
        if (work.blocks().isEmpty()) {
            for (int i = 0; i < maxEntities && !work.entities().isEmpty(); i++) {
                Entity entity = Bukkit.getEntity(work.entities().removeFirst());
                if (entity != null && entity.getChunk().equals(chunk)) {
                removed += purgeEntity(entity, diaryId);
                }
            }
        }
        boolean complete = work.blocks().isEmpty() && work.entities().isEmpty();
        if (complete) {
            chunkWork.remove(workKey);
        }
        return new ChunkPurgeResult(removed, complete);
    }

    private ChunkWork createChunkWork(Chunk chunk) {
        Deque<BlockPosition> blocks = new ArrayDeque<>();
        java.util.Arrays.stream(chunk.getTileEntities())
                .map(state -> new BlockPosition(state.getX(), state.getY(), state.getZ()))
                .sorted(Comparator.comparingInt(BlockPosition::x)
                        .thenComparingInt(BlockPosition::y).thenComparingInt(BlockPosition::z))
                .forEach(blocks::addLast);
        Deque<UUID> entities = new ArrayDeque<>();
        java.util.Arrays.stream(chunk.getEntities()).map(Entity::getUniqueId).sorted().forEach(entities::addLast);
        return new ChunkWork(blocks, entities);
    }

    private int purgeEntity(Entity entity, String diaryId) {
        if (entity instanceof Item item) {
            DiaryItemPurger.Result result = itemPurger.purge(item.getItemStack(), diaryId, 0);
            if (result.removed() > 0) {
                if (result.item() == null) {
                    item.remove();
                } else {
                    item.setItemStack(result.item());
                }
            }
            return result.removed();
        }
        if (entity instanceof ItemFrame frame) {
            DiaryItemPurger.Result result = itemPurger.purge(frame.getItem(), diaryId, 0);
            if (result.removed() > 0) {
                frame.setItem(result.item() == null ? new ItemStack(org.bukkit.Material.AIR) : result.item(), false);
            }
            return result.removed();
        }
        int removed = 0;
        if (entity instanceof InventoryHolder holder) {
            removed += itemPurger.purgeInventory(holder.getInventory(), diaryId);
        }
        if (entity instanceof LivingEntity living) {
            removed += purgeEquipment(living.getEquipment(), diaryId);
        }
        return removed;
    }

    private int purgeEquipment(EntityEquipment equipment, String diaryId) {
        if (equipment == null) {
            return 0;
        }
        int removed = 0;
        ItemStack[] armor = equipment.getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            DiaryItemPurger.Result result = itemPurger.purge(armor[i], diaryId, 0);
            armor[i] = result.item();
            removed += result.removed();
        }
        equipment.setArmorContents(armor);
        DiaryItemPurger.Result main = itemPurger.purge(equipment.getItemInMainHand(), diaryId, 0);
        DiaryItemPurger.Result off = itemPurger.purge(equipment.getItemInOffHand(), diaryId, 0);
        if (main.removed() > 0) equipment.setItemInMainHand(main.item());
        if (off.removed() > 0) equipment.setItemInOffHand(off.item());
        return removed + main.removed() + off.removed();
    }

    private void finalizeIfReady(PurgeOperation operation) {
        if (operation.terminal()) {
            return;
        }
        long maxAgeSeconds = Math.max(1L, plugin.getConfig().getLong("purge.max-operation-age-minutes", 1440L)) * 60L;
        if (Instant.now().getEpochSecond() - operation.startedAt() > maxAgeSeconds) {
            operation.addError("Purge exceeded configured maximum operation age");
            operation.setState(PurgeState.PARTIAL);
            store.purgeOperationChanged();
            return;
        }
        boolean queuedOnline = onlineQueue.stream().anyMatch(target -> target.operationId().equals(operation.operationId()));
        if (queuedOnline) {
            operation.setState(PurgeState.SCANNING_ONLINE_PLAYERS);
            return;
        }
        if (operation.pendingChunks() > 0) {
            operation.setState(PurgeState.PROCESSING_KNOWN_UNLOADED_CHUNKS);
            return;
        }
        if (!operation.pendingPlayers().isEmpty()) {
            operation.setState(PurgeState.WAITING_FOR_OFFLINE_PLAYERS);
            return;
        }
        if (!operation.errors().isEmpty()) {
            operation.setState(PurgeState.PARTIAL);
            analytics(DiaryAnalyticsEventType.PURGE_PARTIAL, operation, null,
                    Integer.toString(operation.errors().size()));
            if (!plugin.getConfig().getBoolean("purge.allow-restore-on-partial-purge", false)
                    || !operation.partialRestoreConfirmed()) {
                return;
            }
        }
        if (operation.verificationRequired()) {
            beginVerification(operation);
            return;
        }
        if (operation.state() == PurgeState.VERIFYING
                && operation.totalRemoved() > operation.verificationRemovedBaseline()) {
            operation.setVerificationRequired(true);
            finalizeIfReady(operation);
            return;
        }
        operation.setState(PurgeState.READY_TO_RESTORE);
        store.markAllLocationsInactive(operation.diaryId());
        persistReadyThenRestore(operation);
    }

    private void beginVerification(PurgeOperation operation) {
        operation.setState(PurgeState.VERIFYING);
        operation.nextVerificationGeneration();
        operation.setVerificationRequired(false);
        operation.setVerificationRemovedBaseline(operation.totalRemoved());
        for (Player player : Bukkit.getOnlinePlayers()) {
            operation.addPendingPlayer(player.getUniqueId());
            enqueuePlayer(operation, player.getUniqueId());
        }
        for (PurgeChunkTarget target : operation.chunkTargets()) {
            if (target.error() == null) {
                target.resetForRetry();
                enqueueChunk(operation, target);
            }
        }
        addLoadedChunkTargets(operation);
        int removed = store.removeAllPendingDeliveriesByDiaryId(operation.diaryId());
        operation.setPendingDeliveriesRemoved(operation.pendingDeliveriesRemoved() + removed);
        operation.addRemoved("delivery_queue_verification", removed);
        ensureTask();
        if (onlineQueue.stream().noneMatch(target -> target.operationId().equals(operation.operationId()))
                && operation.pendingChunks() == 0) {
            finalizeIfReady(operation);
        }
    }

    private void persistReadyThenRestore(PurgeOperation operation) {
        if (!persistenceInFlight.add(operation.operationId())) {
            return;
        }
        if (operation.deliveryToken() == null) {
            operation.setDeliveryToken(UUID.randomUUID());
        }
        store.flushDurably().whenComplete((ignored, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
            persistenceInFlight.remove(operation.operationId());
            if (failure != null) {
                operation.addError("Could not persist READY_TO_RESTORE: " + failure.getMessage());
                operation.setState(PurgeState.FAILED);
                return;
            }
            if (operation.verificationRequired() || operation.pendingChunks() > 0
                    || !operation.pendingPlayers().isEmpty()) {
                operation.setState(PurgeState.QUEUED);
                finalizeIfReady(operation);
                return;
            }
            restoreIfNeeded(operation);
            });
        });
    }

    private void restoreIfNeeded(PurgeOperation operation) {
        if (operation.restorationOccurred()) {
            complete(operation);
            return;
        }
        if (operation.destination() == PurgeDestination.NONE) {
            complete(operation);
            return;
        }
        UUID target = operation.destination() == PurgeDestination.OWNER
                ? operation.ownerUuid() : operation.adminUuid();
        if (target == null || operation.snapshot() == null) {
            operation.addError("Restore destination or snapshot is missing");
            operation.setState(PurgeState.FAILED);
            analytics(DiaryAnalyticsEventType.PURGE_FAILED, operation, target, "missing restore target");
            return;
        }
        DeliveryReason reason = operation.destination() == PurgeDestination.OWNER
                ? DeliveryReason.RESTORE_OWNER : DeliveryReason.RESTORE_ADMIN;
        if (!store.hasPendingDeliveryToken(operation.deliveryToken())) {
            store.queueDelivery(target, reason, operation.snapshot(), operation.deliveryToken());
        }
        operation.setRestorationOccurred(true);
        operation.setReplacementHolder(target);
        operation.setState(PurgeState.RESTORED);
        store.flushDurably().whenComplete((ignored, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
            if (failure != null) {
                operation.addError("Could not persist restore outbox: " + failure.getMessage());
                operation.setState(PurgeState.FAILED);
                return;
            }
            plugin.deliveryService().requestDelivery(target);
            analytics(operation.destination() == PurgeDestination.OWNER
                            ? DiaryAnalyticsEventType.RESTORE_TO_OWNER : DiaryAnalyticsEventType.RESTORE_TO_ADMIN,
                    operation, target, reason.name());
            log(operation, "replacement queued to " + target + " owner remains " + operation.ownerUuid());
            complete(operation);
            });
        });
    }

    private void complete(PurgeOperation operation) {
        operation.setState(PurgeState.COMPLETED);
        operation.setCompletedAt(Instant.now().getEpochSecond());
        long watchMinutes = Math.max(0L, plugin.getConfig().getLong("purge.post-purge-watch-minutes", 60L));
        operation.setWatchUntil(Instant.now().plusSeconds(watchMinutes * 60L).getEpochSecond());
        store.purgeOperationChanged();
        store.flushIfDirty();
        analytics(DiaryAnalyticsEventType.PURGE_COMPLETED, operation, operation.replacementHolder(),
                "removed=" + operation.totalRemoved());
        log(operation, "completed removed=" + operation.totalRemoved());
    }

    private void enqueueRemaining(PurgeOperation operation) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (operation.pendingPlayers().contains(player.getUniqueId())) {
                enqueuePlayer(operation, player.getUniqueId());
            }
        }
        for (PurgeChunkTarget target : operation.chunkTargets()) {
            if (!target.completed()) {
                enqueueChunk(operation, target);
            }
        }
        ensureTask();
    }

    private void enqueuePlayer(PurgeOperation operation, UUID playerId) {
        String key = operation.operationId() + ":" + playerId;
        if (queuedPlayerKeys.add(key)) {
            onlineQueue.addLast(new PlayerTarget(operation.operationId(), playerId));
        }
    }

    private void enqueueChunk(PurgeOperation operation, PurgeChunkTarget target) {
        String key = chunkWorkKey(operation.operationId(), target);
        if (queuedChunkKeys.add(key)) {
            chunkQueue.addLast(new ChunkTarget(operation.operationId(), target));
        }
    }

    int queuedChunkCount() {
        return queuedChunkKeys.size();
    }

    private PurgeChunkTarget findChunkTarget(PurgeOperation operation, Chunk chunk) {
        return operation.chunkTargets().stream()
                .filter(target -> target.chunkX() == chunk.getX() && target.chunkZ() == chunk.getZ()
                        && sameWorld(target, chunk.getWorld()))
                .findFirst()
                .orElse(null);
    }

    private String chunkWorkKey(UUID operationId, PurgeChunkTarget target) {
        return operationId + ":" + target.key();
    }

    private void addLoadedChunkTargets(PurgeOperation operation) {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                addChunkTarget(operation, new PurgeChunkTarget(world.getUID(), world.getName(),
                        chunk.getX(), chunk.getZ(), null, null, null));
            }
        }
    }

    private void addKnownChunkTargets(PurgeOperation operation, List<DiaryLocationRecord> locations) {
        for (DiaryLocationRecord location : locations) {
            if (location.x() == null || location.z() == null
                    || (location.worldUuid() == null && location.worldName() == null)) {
                continue;
            }
            addChunkTarget(operation, new PurgeChunkTarget(location.worldUuid(), location.worldName(),
                    location.x() >> 4, location.z() >> 4, location.x(), location.y(), location.z()));
        }
    }

    private void addChunkTarget(PurgeOperation operation, PurgeChunkTarget candidate) {
        if (operation.chunkTargets().stream().noneMatch(existing -> sameChunk(existing, candidate))) {
            int maximum = Math.max(1, plugin.getConfig().getInt("purge.max-chunk-targets", 512));
            if (operation.chunkTargets().size() >= maximum) {
                operation.addError("Purge exceeded configured maximum chunk targets");
                operation.setState(PurgeState.PARTIAL);
                return;
            }
            operation.addChunkTarget(candidate);
            enqueueChunk(operation, candidate);
            analytics(DiaryAnalyticsEventType.PURGE_PENDING_CHUNK, operation, null, candidate.key());
        }
    }

    private boolean sameChunk(PurgeChunkTarget left, PurgeChunkTarget right) {
        if (left.chunkX() != right.chunkX() || left.chunkZ() != right.chunkZ()) {
            return false;
        }
        World leftWorld = resolveWorld(left);
        World rightWorld = resolveWorld(right);
        if (leftWorld != null && rightWorld != null) {
            return leftWorld.getUID().equals(rightWorld.getUID());
        }
        if (left.worldUuid() != null && right.worldUuid() != null) {
            return left.worldUuid().equals(right.worldUuid());
        }
        return left.worldName() != null && right.worldName() != null
                && left.worldName().equalsIgnoreCase(right.worldName());
    }

    private World resolveWorld(PurgeChunkTarget target) {
        World world = target.worldUuid() == null ? null : Bukkit.getWorld(target.worldUuid());
        return world != null ? world : Bukkit.getWorld(target.worldName());
    }

    private boolean sameWorld(PurgeChunkTarget target, World world) {
        return target.worldUuid() != null ? target.worldUuid().equals(world.getUID())
                : target.worldName() != null && target.worldName().equals(world.getName());
    }

    private void ensureTask() {
        if (task == null && (!onlineQueue.isEmpty() || !chunkQueue.isEmpty())) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    private void analytics(DiaryAnalyticsEventType type, PurgeOperation operation, UUID player, String detail) {
        String diaryId = operation == null ? null : operation.diaryId();
        UUID subject = player != null ? player : operation == null ? null : operation.ownerUuid();
        String name = subject == null ? null : Bukkit.getOfflinePlayer(subject).getName();
        plugin.diaryAnalyticsStore().record(type, subject, name, diaryId,
                operation == null ? detail : "operation=" + operation.operationId() + " " + detail);
    }

    private void logRemoval(PurgeOperation operation, String location, int removed) {
        if (removed > 0) {
            analytics(DiaryAnalyticsEventType.PURGE_COPY_REMOVED, operation, null,
                    "location=" + location + " count=" + removed);
            log(operation, "removed=" + removed + " location=" + location);
        }
    }

    private void log(PurgeOperation operation, String message) {
        plugin.getLogger().info("[Diary Purge] operation=" + operation.operationId()
                + " diary=" + operation.diaryId() + " owner=" + operation.ownerUuid() + " " + message);
    }

    private String adminLabel(Player admin) {
        return admin == null ? "console" : admin.getName() + "/" + admin.getUniqueId();
    }
}
