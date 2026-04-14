package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.Set;

public final class ShulkerGuardListener implements Listener {

    private final DiaryPlugin plugin;

    public ShulkerGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.restrictionService().isShulkerRestrictionEnabled()
                || !plugin.restrictionService().isShulkerTop(event.getView())) {
            return;
        }

        boolean topSlot = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());

        if (topSlot
                && event.getClick() == ClickType.SWAP_OFFHAND
                && plugin.restrictionService().isDiaryOrNestedDiary(event.getWhoClicked().getInventory().getItemInOffHand())) {
            event.setCancelled(true);
            return;
        }

        switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (topSlot && plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
                    event.setCancelled(true);
                }
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (!topSlot && plugin.restrictionService().isDiaryOrNestedDiary(event.getCurrentItem())) {
                    event.setCancelled(true);
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (topSlot && plugin.restrictionService().isDiaryOrNestedDiary(plugin.restrictionService().getHotbarOrOffhandItem(event))) {
                    event.setCancelled(true);
                }
            }
            case COLLECT_TO_CURSOR -> {
                if (plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.restrictionService().isShulkerRestrictionEnabled()
                || !plugin.restrictionService().isShulkerTop(event.getView())) {
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
        if (!plugin.restrictionService().isShulkerRestrictionEnabled()) {
            return;
        }
        if (event.getDestination() != null
                && event.getDestination().getHolder() instanceof org.bukkit.block.ShulkerBox
                && plugin.restrictionService().isDiaryOrNestedDiary(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
