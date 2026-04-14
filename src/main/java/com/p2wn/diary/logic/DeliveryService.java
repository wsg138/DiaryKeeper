package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class DeliveryService {

    private static final class Parcel {
        final UUID player;
        final ItemStack item;
        int attempts = 0;
        Parcel(UUID p, ItemStack i) { player = p; item = i; }
    }

    private final DiaryPlugin plugin;
    private final Deque<Parcel> queue = new ArrayDeque<>();
    private BukkitTask task;

    public DeliveryService(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void enqueue(UUID player, ItemStack item) {
        if (item == null) return;
        queue.addLast(new Parcel(player, item.clone()));
        ensureRunning();
    }

    public void enqueueAll(UUID player, Collection<ItemStack> items) {
        for (ItemStack it : items) enqueue(player, it);
    }

    private void ensureRunning() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10, 20); // start after 0.5s, then every 1s
    }

    public void stopIfIdle(boolean force) {
        if (force || queue.isEmpty()) {
            if (task != null) { task.cancel(); task = null; }
        }
    }

    private void tick() {
        if (queue.isEmpty()) { stopIfIdle(false); return; }

        int batch = Math.min(queue.size(), 8); // small batch per second
        for (int i = 0; i < batch; i++) {
            Parcel p = queue.pollFirst();
            if (p == null) break;

            Player pl = Bukkit.getPlayer(p.player);
            if (pl == null || !pl.isOnline()) {
                // Still offline â€” requeue to try later
                if (++p.attempts <= 120) { // try for up to ~2 minutes of online checks
                    queue.addLast(p);
                }
                continue;
            }

            // If player is hovering over the void, avoid dropping; try again later
            int minY = pl.getWorld().getMinHeight();
            if (pl.getLocation().getY() < (minY + 4)) {
                if (++p.attempts <= 20) { // give ~20s to get to safety
                    queue.addLast(p);
                } else {
                    // Fallback: drop at world spawn location safely
                    Location safe = pl.getWorld().getSpawnLocation().add(0, 1, 0);
                    pl.getWorld().dropItemNaturally(safe, p.item);
                }
                continue;
            }

            // Try inventory
            var leftovers = pl.getInventory().addItem(p.item);
            if (!leftovers.isEmpty()) {
                // Inventory full â€” keep trying a bit, then drop at their feet (safe location)
                if (++p.attempts <= 10) {
                    queue.addLast(p);
                } else {
                    pl.getWorld().dropItemNaturally(pl.getLocation(), p.item);
                }
            } else {
                // success
                pl.sendMessage(plugin.configManager().msg("void-returned"));
            }
        }

        stopIfIdle(false);
    }
}
