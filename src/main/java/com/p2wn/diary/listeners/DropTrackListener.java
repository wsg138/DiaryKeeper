package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

public final class DropTrackListener implements Listener {

    private final DiaryPlugin plugin;

    public DropTrackListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        if (!plugin.diaryService().isDiary(item.getItemStack())) {
            return;
        }

        var tagged = item.getItemStack().clone();
        plugin.diaryService().tagLastDropper(tagged, event.getPlayer().getUniqueId());
        item.setItemStack(tagged);
        plugin.duplicateWatcher().refreshGroundItemSnapshot(item);
        plugin.duplicateWatcher().refreshPlayerSnapshot(event.getPlayer());
        plugin.voidWatcher().track(item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.diaryService().isDiary(event.getEntity().getItemStack())) {
            return;
        }
        plugin.duplicateWatcher().refreshGroundItemSnapshot(event.getEntity());
        plugin.voidWatcher().track(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (!plugin.diaryService().isDiary(event.getItem().getItemStack())) {
            return;
        }

        var pickedUp = event.getItem().getItemStack().clone();
        plugin.voidWatcher().untrack(event.getItem());
        plugin.duplicateWatcher().removeGroundItemSnapshot(event.getItem().getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.diaryService().handleDiaryObtained(player, pickedUp), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        // No special handling needed here. Container restrictions are enforced elsewhere.
    }
}
