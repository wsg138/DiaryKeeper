package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryObtainedEvent;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.persistence.PersistentDataType;

public class DropTrackListener implements Listener {

    private final DiaryPlugin plugin;

    public DropTrackListener(DiaryPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent e) {
        Item item = e.getItemDrop();
        if (!DiaryItem.isDiary(item.getItemStack())) return;

        // Tag the dropped entity with last dropper for void return
        var meta = item.getItemStack().getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(plugin.keyLastDropper(), PersistentDataType.STRING, e.getPlayer().getUniqueId().toString());
        item.getItemStack().setItemMeta(meta);

        plugin.voidWatcher().track(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (DiaryItem.isDiary(e.getEntity().getItemStack())) {
            // If spawned by non-player (e.g., container spit), we don't know the dropper; still track for void.
            plugin.voidWatcher().track(e.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent e) {
        if (DiaryItem.isDiary(e.getItem().getItemStack())) {
            // Stop void tracking as soon as a player interacts/picks it up
            plugin.voidWatcher().untrack(e.getItem());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        if (DiaryItem.isDiary(e.getItem().getItemStack())) {
            Bukkit.getPluginManager().callEvent(new DiaryObtainedEvent(e.getPlayer(), e.getItem().getItemStack()));
            plugin.voidWatcher().untrack(e.getItem());
        }
    }

    // If hoppers move it around, still fine; we don't need special handling beyond void tracking on spawn/drop.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        // no-op; allowing movement per your design
    }
}
