package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiaryTrackingListener implements Listener {

    private final DiaryPlugin plugin;
    private final Map<UUID, BukkitTask> pendingPlayerScans = new HashMap<>();
    private final Map<String, BukkitTask> pendingInventoryScans = new HashMap<>();

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
        if (!isLikelyDiaryRelated(event)) {
            return;
        }
        schedulePlayerScan(player);
        scheduleInventoryScan(player, event.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.restrictionService().isDiaryOrNestedDiary(event.getOldCursor())) {
            return;
        }
        schedulePlayerScan(player);
        scheduleInventoryScan(player, event.getView().getTopInventory());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            schedulePlayerScan(player);
            scheduleInventoryScan(player, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!plugin.restrictionService().isDiaryOrNestedDiary(event.getItem())) {
            return;
        }
        scheduleInventoryScan(event.getSource());
        scheduleInventoryScan(event.getDestination());
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
        if (!plugin.restrictionService().isDiaryOrNestedDiary(event.getItem().getItemStack())) {
            return;
        }
        plugin.diaryTrackerService().markGroundItemInactive(event.getItem().getUniqueId());
        schedulePlayerScan(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Container container)) {
            return;
        }
        plugin.diaryTrackerService().trackBlockInventory(event.getBlock(), container.getInventory(), java.util.List.of());
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.duplicateWatcher().removeBlockContainerSnapshot(event.getBlock()));
    }

    private void trackBlockInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        if (inventory.getHolder() instanceof BlockState state) {
            plugin.diaryTrackerService().trackBlockInventory(state.getBlock(), inventory, java.util.List.of());
        }
    }

    private boolean isLikelyDiaryRelated(InventoryClickEvent event) {
        if (plugin.restrictionService().isDiaryOrNestedDiary(event.getCurrentItem())
                || plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
            return true;
        }
        return switch (event.getAction()) {
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD ->
                    plugin.restrictionService().isDiaryOrNestedDiary(plugin.restrictionService().getHotbarOrOffhandItem(event));
            default -> false;
        };
    }

    private void schedulePlayerScan(Player player) {
        UUID playerId = player.getUniqueId();
        if (pendingPlayerScans.containsKey(playerId)) {
            plugin.performanceMonitor().diaryScanCoalesced();
            return;
        }
        plugin.performanceMonitor().diaryScanQueued();
        pendingPlayerScans.put(playerId, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingPlayerScans.remove(playerId);
            if (player.isOnline()) {
                plugin.diaryTrackerService().trackPlayerInventory(player);
            }
        }, 2L));
    }

    private void scheduleInventoryScan(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        String key = inventoryKey(inventory);
        if (pendingInventoryScans.containsKey(key)) {
            plugin.performanceMonitor().diaryScanCoalesced();
            return;
        }
        plugin.performanceMonitor().diaryScanQueued();
        pendingInventoryScans.put(key, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingInventoryScans.remove(key);
            if (player.isOnline()) {
                plugin.diaryTrackerService().trackInventoryView(player, inventory);
                plugin.duplicateWatcher().refreshContainerSnapshot(inventory);
            }
        }, 2L));
    }

    private void scheduleInventoryScan(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        String key = inventoryKey(inventory);
        if (pendingInventoryScans.containsKey(key)) {
            plugin.performanceMonitor().diaryScanCoalesced();
            return;
        }
        plugin.performanceMonitor().diaryScanQueued();
        pendingInventoryScans.put(key, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingInventoryScans.remove(key);
            trackBlockInventory(inventory);
            plugin.duplicateWatcher().refreshContainerSnapshot(inventory);
        }, 2L));
    }

    private String inventoryKey(Inventory inventory) {
        if (inventory instanceof DoubleChestInventory doubleInventory) {
            return doubleChestKey(doubleInventory.getLeftSide().getHolder(), doubleInventory.getRightSide().getHolder());
        }
        if (inventory.getHolder() instanceof DoubleChest doubleChest) {
            return doubleChestKey(doubleChest.getLeftSide(), doubleChest.getRightSide());
        }
        if (inventory.getHolder() instanceof BlockState state) {
            return blockStateKey(state);
        }
        return "inventory:" + System.identityHashCode(inventory);
    }

    private String doubleChestKey(InventoryHolder left, InventoryHolder right) {
        List<String> halfKeys = new ArrayList<>(2);
        addDoubleChestHalfKey(halfKeys, left);
        addDoubleChestHalfKey(halfKeys, right);
        if (halfKeys.size() != 2) {
            return "double-chest:" + System.identityHashCode(left) + ":" + System.identityHashCode(right);
        }
        Collections.sort(halfKeys);
        return "double-chest:" + String.join("|", halfKeys);
    }

    private void addDoubleChestHalfKey(List<String> halfKeys, InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            halfKeys.add(blockStateKey(state));
        }
    }

    private String blockStateKey(BlockState state) {
        return state.getWorld().getUID() + ":" + state.getX() + ":" + state.getY() + ":" + state.getZ();
    }
}
