package com.p2wn.diary.logic;

import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingDelivery;
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
import java.util.UUID;

public final class DeliveryService {

    private final Plugin plugin;
    private final DiaryStore diaryStore;
    private BukkitTask task;
    private DiaryService diaryService;
    private DiaryTrackerService trackerService;
    private DiaryAnalyticsStore analyticsStore;
    private PerformanceMonitor performanceMonitor;

    public DeliveryService(Plugin plugin, DiaryStore diaryStore) {
        this.plugin = plugin;
        this.diaryStore = diaryStore;
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

    public void requestDelivery(UUID playerId) {
        if (diaryStore.getPendingDeliveryCount(playerId) > 0) {
            ensureRunning();
        }
    }

    public void reloadSettings() {
        stop();
        if (!diaryStore.getPlayersWithPendingDeliveries().isEmpty()) {
            ensureRunning();
        }
        updateDeliveryQueueSize();
    }

    public void shutdown() {
        stop();
    }

    private void ensureRunning() {
        if (task != null) {
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

            int deliveredCount = 0;
            for (PendingDelivery delivery : deliveries) {
                ItemStack item = delivery.item().clone();
                if (hasDeliveredToken(player, delivery.token())) {
                    deliveredCount++;
                    continue;
                }
                stampDeliveryToken(item, delivery.token());
                if (!player.getInventory().addItem(item).isEmpty()) {
                    break;
                }

                deliveredCount++;

                if (delivery.reason() == DeliveryReason.VOID_RETURN) {
                    diaryService.onVoidReturnDelivered(player, delivery.item());
                }
                if (analyticsStore != null) {
                    analyticsStore.record(
                            DiaryAnalyticsEventType.DELIVERED_FROM_QUEUE,
                            player.getUniqueId(),
                            player.getName(),
                            extractDiaryId(item),
                            delivery.reason().name()
                    );
                }
            }

            if (deliveredCount > 0) {
                diaryStore.removeFirstPendingDeliveries(playerId, deliveredCount);
                diaryStore.flushIfDirty();
                if (trackerService != null) {
                    trackerService.trackPlayerInventory(player);
                    trackerService.refreshQueuedDeliveries(playerId, player.getName());
                }
                diaryService.refreshOwnedDiaries(player);
                updateDeliveryQueueSize();
            }

            processedPlayers++;
        }

        if (diaryStore.getPlayersWithPendingDeliveries().isEmpty()) {
            stop();
        }
        updateDeliveryQueueSize();
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
}
