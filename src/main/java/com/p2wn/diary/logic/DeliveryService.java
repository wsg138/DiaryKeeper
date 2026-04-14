package com.p2wn.diary.logic;

import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingDelivery;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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

    public DeliveryService(Plugin plugin, DiaryStore diaryStore) {
        this.plugin = plugin;
        this.diaryStore = diaryStore;
    }

    public void setDiaryService(DiaryService diaryService) {
        this.diaryService = diaryService;
    }

    public void queue(UUID playerId, DeliveryReason reason, ItemStack item) {
        diaryStore.queueDelivery(playerId, reason, item);
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

    private void tick() {
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
                if (!player.getInventory().addItem(item).isEmpty()) {
                    break;
                }

                deliveredCount++;

                if (delivery.reason() == DeliveryReason.VOID_RETURN) {
                    diaryService.onVoidReturnDelivered(player, delivery.item());
                }
            }

            if (deliveredCount > 0) {
                diaryStore.removeFirstPendingDeliveries(playerId, deliveredCount);
                diaryService.refreshOwnedDiaries(player);
            }

            processedPlayers++;
        }

        if (diaryStore.getPlayersWithPendingDeliveries().isEmpty()) {
            stop();
        }
    }
}
