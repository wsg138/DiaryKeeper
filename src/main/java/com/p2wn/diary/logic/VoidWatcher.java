package com.p2wn.diary.logic;

import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class VoidWatcher {

    private record TrackedDrop(UUID worldId) {}

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final DiaryItem diaryItem;
    private final DeliveryService deliveryService;
    private final DuplicateWatcher duplicateWatcher;

    private final Map<UUID, TrackedDrop> trackedDrops = new HashMap<>();
    private BukkitTask task;

    public VoidWatcher(
            Plugin plugin,
            ConfigManager configManager,
            DiaryItem diaryItem,
            DeliveryService deliveryService,
            DuplicateWatcher duplicateWatcher
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.diaryItem = diaryItem;
        this.deliveryService = deliveryService;
        this.duplicateWatcher = duplicateWatcher;
    }

    public void track(Item item) {
        if (!configManager.cfg().getBoolean("void.return-to-dropper", true)) {
            return;
        }
        if (item == null || item.isDead() || !diaryItem.isDiary(item.getItemStack())) {
            return;
        }
        trackedDrops.put(item.getUniqueId(), new TrackedDrop(item.getWorld().getUID()));
        ensureRunning();
    }

    public void untrack(Item item) {
        if (item == null) {
            return;
        }
        trackedDrops.remove(item.getUniqueId());
        duplicateWatcher.removeGroundItemSnapshot(item.getUniqueId());
        stopIfIdle();
    }

    public void reloadSettings() {
        stop();
        if (!trackedDrops.isEmpty() && configManager.cfg().getBoolean("void.return-to-dropper", true)) {
            ensureRunning();
        }
    }

    public void shutdown() {
        stop();
        trackedDrops.clear();
    }

    private void ensureRunning() {
        if (task != null) {
            return;
        }
        int interval = Math.max(5, configManager.cfg().getInt("void.check-interval-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    private void stopIfIdle() {
        if (trackedDrops.isEmpty()) {
            stop();
        }
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (trackedDrops.isEmpty()) {
            stop();
            return;
        }

        Iterator<Map.Entry<UUID, TrackedDrop>> iterator = trackedDrops.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedDrop> entry = iterator.next();
            Item item = findTrackedItem(entry.getKey(), entry.getValue().worldId());
            if (item == null || item.isDead() || !diaryItem.isDiary(item.getItemStack())) {
                iterator.remove();
                continue;
            }

            int minY = item.getWorld().getMinHeight();
            if (item.getLocation().getY() < (minY - 2)) {
                handleVoid(item);
                iterator.remove();
            }
        }

        stopIfIdle();
    }

    private Item findTrackedItem(UUID entityId, UUID worldId) {
        World world = Bukkit.getWorld(worldId);
        if (world == null) {
            return null;
        }
        Entity entity = world.getEntity(entityId);
        return entity instanceof Item item ? item : null;
    }

    private void handleVoid(Item item) {
        duplicateWatcher.removeGroundItemSnapshot(item.getUniqueId());

        if (!configManager.cfg().getBoolean("void.return-to-dropper", true)) {
            item.remove();
            return;
        }

        UUID dropperId = diaryItem.getLastDropper(item.getItemStack());
        if (dropperId == null) {
            item.remove();
            return;
        }

        deliveryService.queue(dropperId, DeliveryReason.VOID_RETURN, item.getItemStack().clone());
        item.remove();
    }
}
