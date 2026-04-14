package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

public final class DiaryTrackingListener implements Listener {

    private final DiaryPlugin plugin;

    public DiaryTrackingListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.diaryTrackerService().trackPlayerInventory(event.getPlayer());
        plugin.diaryTrackerService().trackEnderChest(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.diaryTrackerService().trackPlayerInventory(event.getPlayer());
        plugin.diaryTrackerService().trackEnderChest(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.diaryTrackerService().trackPlayerInventory(player);
            plugin.diaryTrackerService().trackInventoryView(player, event.getView().getTopInventory());
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            plugin.diaryTrackerService().trackPlayerInventory(player);
            plugin.diaryTrackerService().trackInventoryView(player, event.getView().getTopInventory());
        }, 1L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.diaryTrackerService().trackPlayerInventory(player);
            plugin.diaryTrackerService().trackInventoryView(player, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            trackBlockInventory(event.getSource());
            trackBlockInventory(event.getDestination());
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        plugin.diaryTrackerService().trackGroundItem(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.diaryTrackerService().trackPlayerInventory(player), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Container container)) {
            return;
        }
        plugin.diaryTrackerService().trackBlockInventory(event.getBlock(), container.getInventory(), java.util.List.of());
    }

    private void trackBlockInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        if (inventory.getHolder() instanceof BlockState state) {
            plugin.diaryTrackerService().trackBlockInventory(state.getBlock(), inventory, java.util.List.of());
        }
    }
}
