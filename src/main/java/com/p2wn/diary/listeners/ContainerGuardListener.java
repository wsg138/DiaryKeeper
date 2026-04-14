package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryContainerAttemptEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public final class ContainerGuardListener implements Listener {

    private final DiaryPlugin plugin;

    public ContainerGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.restrictionService().isRestrictedTopContainer(event.getView())) {
            return;
        }

        boolean topSlot = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());
        String containerName = event.getView().getTopInventory().getType().name();

        if (topSlot
                && event.getClick() == ClickType.SWAP_OFFHAND
                && plugin.restrictionService().isDiaryOrNestedDiary(event.getWhoClicked().getInventory().getItemInOffHand())) {
            fireContainerAttempt(event.getWhoClicked() instanceof Player p ? p : null,
                    event.getWhoClicked().getInventory().getItemInOffHand(),
                    containerName);
            event.setCancelled(true);
            return;
        }

        switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (topSlot && plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
                    fireContainerAttempt(event.getWhoClicked() instanceof Player p ? p : null, event.getCursor(), containerName);
                    event.setCancelled(true);
                }
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (!topSlot && plugin.restrictionService().isDiaryOrNestedDiary(event.getCurrentItem())) {
                    fireContainerAttempt(event.getWhoClicked() instanceof Player p ? p : null, event.getCurrentItem(), containerName);
                    event.setCancelled(true);
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (topSlot) {
                    ItemStack hotbar = plugin.restrictionService().getHotbarOrOffhandItem(event);
                    if (plugin.restrictionService().isDiaryOrNestedDiary(hotbar)) {
                        fireContainerAttempt(event.getWhoClicked() instanceof Player p ? p : null, hotbar, containerName);
                        event.setCancelled(true);
                    }
                }
            }
            case COLLECT_TO_CURSOR -> {
                if (plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
                    fireContainerAttempt(event.getWhoClicked() instanceof Player p ? p : null, event.getCursor(), containerName);
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.restrictionService().isRestrictedTopContainer(event.getView())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Set<Integer> rawSlots = event.getRawSlots();
        boolean touchesTop = false;
        for (int rawSlot : rawSlots) {
            if (rawSlot < top.getSize()) {
                touchesTop = true;
                break;
            }
        }
        if (touchesTop && plugin.restrictionService().isDiaryOrNestedDiary(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (plugin.restrictionService().isRestrictedDestination(event.getDestination())
                && plugin.restrictionService().isDiaryOrNestedDiary(event.getItem())) {
            event.setCancelled(true);
        }
    }

    private void fireContainerAttempt(Player player, ItemStack stack, String containerType) {
        if (player == null || stack == null) {
            return;
        }
        Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(player, stack.clone(), containerType));
    }
}
