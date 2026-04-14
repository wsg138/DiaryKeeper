package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryVoidReturnEvent;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class VoidWatcher {

    private final DiaryPlugin plugin;
    private final DeliveryService delivery;
    private final Set<UUID> tracked = new HashSet<>();
    private BukkitTask task;

    public VoidWatcher(DiaryPlugin plugin, DeliveryService delivery) {
        this.plugin = plugin;
        this.delivery = delivery;
    }

    public void track(Item item) {
        if (item == null || item.isDead()) return;
        if (!DiaryItem.isDiary(item.getItemStack())) return;
        tracked.add(item.getUniqueId());
        ensureRunning();
    }

    public void untrack(Item item) {
        if (item == null) return;
        tracked.remove(item.getUniqueId());
        stopIfIdle(false);
    }

    private void ensureRunning() {
        if (task != null) return;
        int interval = plugin.configManager().cfg().getInt("void.check-interval-ticks", 10);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stopIfIdle(boolean force) {
        if (force || tracked.isEmpty()) {
            if (task != null) { task.cancel(); task = null; }
        }
    }

    private void tick() {
        if (tracked.isEmpty()) { stopIfIdle(false); return; }

        List<UUID> toRemove = new ArrayList<>();

        for (UUID id : tracked) {
            var ent = findItem(id);
            if (ent == null || ent.isDead() || ent.getItemStack() == null) {
                toRemove.add(id);
                continue;
            }

            int minY = ent.getWorld().getMinHeight();
            if (ent.getLocation().getY() < (minY - 2)) {
                handleVoid(ent);
                toRemove.add(id);
            }
        }

        for (UUID id : toRemove) tracked.remove(id);
        stopIfIdle(false);
    }

    private Item findItem(UUID id) {
        for (var w : Bukkit.getWorlds()) {
            var e = w.getEntity(id);
            if (e instanceof Item i) return i;
        }
        return null;
    }

    private void handleVoid(Item item) {
        if (!plugin.configManager().cfg().getBoolean("void.return-to-dropper", true)) {
            item.remove();
            return;
        }

        ItemStack stack = item.getItemStack();
        if (!DiaryItem.isDiary(stack)) { item.remove(); return; }

        String dropperStr = stack.getItemMeta().getPersistentDataContainer()
                .get(plugin.keyLastDropper(), PersistentDataType.STRING);

        item.remove(); // remove the falling entity

        if (dropperStr == null) return;

        try {
            UUID dropperId = UUID.fromString(dropperStr);
            Player dropper = Bukkit.getPlayer(dropperId);
            if (dropper != null && dropper.isOnline()) {
                // Try once; if full/unsafe, hand to DeliveryService for safe retries
                var leftovers = dropper.getInventory().addItem(stack);
                if (!leftovers.isEmpty()) {
                    delivery.enqueue(dropperId, stack);
                } else {
                    Bukkit.getPluginManager().callEvent(new DiaryVoidReturnEvent(dropper, stack));
                    dropper.sendMessage(plugin.configManager().msg("void-returned"));
                }
            } else {
                // Offline exact return â€” persist and deliver at next login
                plugin.diaryStore().queueVoidReturn(dropperId, stack);
            }
        } catch (Exception ignored) {}
    }
}
