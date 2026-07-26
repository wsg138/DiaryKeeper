package com.p2wn.diary.logic;

import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingDelivery;
import com.p2wn.diary.data.DeliveryLifecycle;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DeliveryService {

    private final Plugin plugin;
    private final DiaryStore diaryStore;
    private final MainThreadExecutor mainThread;
    private BukkitTask task;
    private DiaryService diaryService;
    private DiaryTrackerService trackerService;
    private DiaryAnalyticsStore analyticsStore;
    private PerformanceMonitor performanceMonitor;
    private final Set<UUID> inFlightDeliveries = new HashSet<>();
    private final Map<UUID, Integer> releaseAttempts = new HashMap<>();
    private long generation;

    public DeliveryService(Plugin plugin, DiaryStore diaryStore) {
        this(plugin, diaryStore, new MainThreadExecutor() {
            @Override public void execute(Runnable task) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
            @Override public void executeLater(Runnable task, long delayTicks) {
                Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        });
    }

    DeliveryService(Plugin plugin, DiaryStore diaryStore, MainThreadExecutor mainThread) {
        this.plugin = plugin;
        this.diaryStore = diaryStore;
        this.mainThread = mainThread;
    }

    public void setDiaryService(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    public void setTrackerService(DiaryTrackerService trackerService) {
        this.trackerService = trackerService;
    }

    public void setAnalyticsStore(DiaryAnalyticsStore analyticsStore) {
        this.analyticsStore = analyticsStore;
    }

    public void setPerformanceMonitor(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }

    public void queue(UUID playerId, DeliveryReason reason, ItemStack item) {
        queue(playerId, reason, item, null);
    }

    public void queue(UUID playerId, DeliveryReason reason, ItemStack item, UUID token) {
        diaryStore.queueDelivery(playerId, reason, item, token);
        diaryStore.flushIfDirty();
        updateDeliveryQueueSize();
        if (analyticsStore != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString();
            analyticsStore.record(DiaryAnalyticsEventType.QUEUED_DELIVERY, playerId, playerName, extractDiaryId(item), reason.name());
        }
        if (trackerService != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString();
            trackerService.trackQueuedDelivery(playerId, playerName, item);
        }
        requestDelivery(playerId);
    }

    public CompletableFuture<DiaryStore.DurableQueueResult> queueDurably(UUID playerId, DeliveryReason reason,
                                                                            ItemStack item, UUID token) {
        return diaryStore.queueDeliveryDurably(playerId, reason, item, token);
    }

    public void completeDurableQueueOnMainThread(UUID playerId, DeliveryReason reason, ItemStack item) {
        updateDeliveryQueueSize();
        if (analyticsStore != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString();
            analyticsStore.record(DiaryAnalyticsEventType.QUEUED_DELIVERY, playerId, playerName, extractDiaryId(item), reason.name());
        }
        if (trackerService != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : playerId.toString();
            trackerService.trackQueuedDelivery(playerId, playerName, item);
        }
        requestDelivery(playerId);
    }

    public void requestDelivery(UUID playerId) {
        if (diaryStore.getPendingDeliveryCount(playerId) > 0) {
            ensureRunning();
        }
    }

    public void reloadSettings() {
        generation++;
        releaseInFlightClaims();
        stop();
        if (!diaryStore.getPlayersWithPendingDeliveries().isEmpty()) {
            ensureRunning();
        }
        updateDeliveryQueueSize();
    }

    public void shutdown() {
        generation++;
        releaseInFlightClaims();
        stop();
    }

    private void ensureRunning() {
        if (task != null || !plugin.isEnabled()) {
            return;
        }
        int interval = Math.max(10, plugin.getConfig().getInt("delivery.retry-interval-ticks", 20));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void tick() {
        Set<UUID> playerIds = diaryStore.getPlayersWithPendingDeliveries();
        if (playerIds.isEmpty()) {
            stop();
            return;
        }

        int maxPlayersPerTick = Math.max(1, plugin.getConfig().getInt("delivery.max-players-per-tick", 10));
        int maxItemsPerPlayer = Math.max(1, plugin.getConfig().getInt("delivery.max-items-per-player-per-tick", 2));

        int processedPlayers = 0;
        for (UUID playerId : playerIds) {
            if (processedPlayers >= maxPlayersPerTick) {
                break;
            }

            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }

            List<PendingDelivery> deliveries = diaryStore.getPendingDeliveries(playerId, maxItemsPerPlayer);
            if (deliveries.isEmpty()) {
                continue;
            }

            boolean deliveryStarted = false;
            for (int index = 0; index < deliveries.size() && !deliveryStarted; index++) {
                PendingDelivery delivery = deliveries.get(index);
                if (hasDeliveredToken(player, delivery.token())) {
                    confirmPresent(playerId, delivery.token());
                    continue;
                }
                if (!diaryStore.claimDelivery(playerId, delivery.token())) {
                    continue;
                }
                persistClaimThenDeliver(playerId, delivery);
                deliveryStarted = true;
            }

            processedPlayers++;
        }

        if (diaryStore.getPlayersWithPendingDeliveries().isEmpty()) {
            stop();
        }
        updateDeliveryQueueSize();
    }

    public void reconcileClaimedDeliveries(Player player) {
        diaryStore.getDeliveryEntries().stream()
                .filter(entry -> entry.playerId().equals(player.getUniqueId()))
                .filter(entry -> entry.delivery().lifecycle() == DeliveryLifecycle.CLAIMED)
                .filter(entry -> hasDeliveredToken(player, entry.delivery().token()))
                .forEach(entry -> confirmPresent(entry.playerId(), entry.delivery().token()));
    }

    private void persistClaimThenDeliver(UUID playerId, PendingDelivery delivery) {
        long callbackGeneration = generation;
        inFlightDeliveries.add(delivery.token());
        diaryStore.flushDurably().whenComplete((ignored, failure) -> {
            if (!plugin.isEnabled()) return;
            mainThread.execute(() -> {
            inFlightDeliveries.remove(delivery.token());
            if (!plugin.isEnabled() || callbackGeneration != generation) {
                return;
            }
            if (failure != null) {
                releaseClaimDurably(playerId, delivery.token(), callbackGeneration);
                return;
            }
            Player player = Bukkit.getPlayer(playerId);
            var current = diaryStore.getDeliveryEntry(delivery.token());
            if (current == null || !current.playerId().equals(playerId)
                    || current.delivery().lifecycle() != DeliveryLifecycle.CLAIMED
                    || player == null || !player.isOnline()) {
                releaseClaimDurably(playerId, delivery.token(), callbackGeneration);
                return;
            }
            if (hasDeliveredToken(player, delivery.token())) {
                confirmPresent(playerId, delivery.token());
                return;
            }
            ItemStack item = delivery.item().clone();
            stampDeliveryToken(item, delivery.token());
            if (!player.getInventory().addItem(item).isEmpty()) {
                releaseClaimDurably(playerId, delivery.token(), callbackGeneration);
                return;
            }
            markDelivered(playerId, delivery.token());
            if (delivery.reason() == DeliveryReason.VOID_RETURN && diaryService != null) {
                diaryService.onVoidReturnDelivered(player, delivery.item());
            }
            if (analyticsStore != null) {
                analyticsStore.record(DiaryAnalyticsEventType.DELIVERED_FROM_QUEUE, player.getUniqueId(),
                        player.getName(), extractDiaryId(item), delivery.reason().name());
            }
            if (trackerService != null) {
                trackerService.trackPlayerInventory(player);
                trackerService.refreshQueuedDeliveries(playerId, player.getName());
            }
            if (diaryService != null) {
                diaryService.refreshOwnedDiaries(player);
            }
            });
        });
    }

    private void releaseClaimDurably(UUID playerId, UUID deliveryId, long callbackGeneration) {
        diaryStore.releaseDeliveryClaimDurably(playerId, deliveryId).whenComplete((released, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            mainThread.execute(() ->
                    finishReleaseOnMainThread(playerId, deliveryId, callbackGeneration, released, failure));
        });
    }

    private void finishReleaseOnMainThread(UUID playerId, UUID deliveryId, long callbackGeneration,
                                           Boolean released, Throwable failure) {
        if (!plugin.isEnabled() || callbackGeneration != generation) return;
        if (failure == null && Boolean.TRUE.equals(released)) {
            releaseAttempts.remove(deliveryId);
            requestDelivery(playerId);
            return;
        }
        int attempt = releaseAttempts.merge(deliveryId, 1, Integer::sum);
        plugin.getLogger().warning("Delivery claim release could not be persisted for " + deliveryId
                + " (attempt " + attempt + "/3).");
        if (attempt >= 3) {
            releaseAttempts.remove(deliveryId);
            return;
        }
        long delay = 20L << (attempt - 1);
        mainThread.executeLater(() -> {
            if (plugin.isEnabled() && callbackGeneration == generation) {
                retryPendingRelease(playerId, deliveryId, callbackGeneration);
            }
        }, delay);
    }

    private void retryPendingRelease(UUID playerId, UUID deliveryId, long callbackGeneration) {
        diaryStore.retryDeliveryDurably(deliveryId).whenComplete((released, failure) -> {
            if (!plugin.isEnabled()) return;
            mainThread.execute(() ->
                    finishReleaseOnMainThread(playerId, deliveryId, callbackGeneration, released, failure));
        });
    }

    private void releaseInFlightClaims() {
        for (UUID deliveryId : Set.copyOf(inFlightDeliveries)) {
            diaryStore.releaseDeliveryClaim(deliveryId);
        }
        inFlightDeliveries.clear();
        releaseAttempts.clear();
        diaryStore.flushNowBlocking("delivery claim recovery");
    }

    private void markDelivered(UUID playerId, UUID deliveryId) {
        if (diaryStore.markDeliveryDelivered(playerId, deliveryId)) {
            diaryStore.flushDurably().exceptionally(failure -> {
                plugin.getLogger().warning("Delivery " + deliveryId
                        + " was inserted but its DELIVERED audit state could not be persisted.");
                return null;
            });
        }
    }

    private void confirmPresent(UUID playerId, UUID deliveryId) {
        if (diaryStore.confirmDeliveryPresent(playerId, deliveryId)) {
            diaryStore.flushDurably().exceptionally(failure -> {
                plugin.getLogger().warning("Existing delivery " + deliveryId
                        + " was found in inventory but confirmation could not be persisted.");
                return null;
            });
        }
    }

    private String extractDiaryId(ItemStack item) {
        if (diaryService == null) {
            return null;
        }
        return diaryService.getDiaryId(item);
    }

    boolean hasDeliveredToken(Player player, UUID token) {
        if (token == null || !(plugin instanceof com.p2wn.diary.DiaryPlugin diaryPlugin)) {
            return false;
        }
        String value = token.toString();
        int maxDepth = Math.max(1, plugin.getConfig().getInt("delivery.token-search-depth", 4));
        return containsDeliveryToken(player.getInventory().getContents(), value, diaryPlugin, maxDepth)
                || containsDeliveryToken(player.getEnderChest().getContents(), value, diaryPlugin, maxDepth);
    }

    private boolean containsDeliveryToken(ItemStack[] items, String token, com.p2wn.diary.DiaryPlugin diaryPlugin,
                                          int maxDepth) {
        for (ItemStack item : items) {
            if (containsDeliveryToken(item, token, diaryPlugin, 0, maxDepth)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDeliveryToken(ItemStack item, String token, com.p2wn.diary.DiaryPlugin diaryPlugin,
                                          int depth, int maxDepth) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        var meta = item.getItemMeta();
        if (token.equals(meta.getPersistentDataContainer()
                .get(diaryPlugin.diaryKeys().deliveryToken(), PersistentDataType.STRING))) {
            return true;
        }
        if (depth >= maxDepth) {
            return false;
        }
        if (item.getType() == Material.BUNDLE && meta instanceof BundleMeta bundleMeta) {
            for (ItemStack nested : bundleMeta.getItems()) {
                if (containsDeliveryToken(nested, token, diaryPlugin, depth + 1, maxDepth)) {
                    return true;
                }
            }
        }
        if (meta instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            return containsDeliveryToken(shulkerBox.getInventory().getContents(), token, diaryPlugin, maxDepth - depth - 1);
        }
        return false;
    }

    private void stampDeliveryToken(ItemStack item, UUID token) {
        if (token == null || !(plugin instanceof com.p2wn.diary.DiaryPlugin diaryPlugin)) {
            return;
        }
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
                diaryPlugin.diaryKeys().deliveryToken(), PersistentDataType.STRING, token.toString());
        item.setItemMeta(meta);
    }

    private void updateDeliveryQueueSize() {
        if (performanceMonitor != null) {
            performanceMonitor.deliveryQueueSize(diaryStore.getTotalPendingDeliveryCount());
        }
    }

    interface MainThreadExecutor {
        void execute(Runnable task);
        void executeLater(Runnable task, long delayTicks);
    }
}
