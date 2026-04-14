package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.Set;

public class ShulkerGuardListener implements Listener {

    private boolean enabled() {
        return DiaryPlugin.get().configManager().cfg().getBoolean("restrictions.shulkers", false);
    }

    private boolean isShulkerTop(InventoryView view) {
        if (view == null) return false;
        Inventory top = view.getTopInventory();
        if (top == null) return false;
        InventoryHolder holder = top.getHolder();
        return holder instanceof ShulkerBox;
    }

    private boolean isDiaryOrBundleWithDiary(ItemStack item) {
        if (item == null) return false;
        if (DiaryItem.isDiary(item)) return true;
        if (item.getType() == Material.BUNDLE && item.hasItemMeta() && item.getItemMeta() instanceof BundleMeta bm) {
            for (ItemStack inside : bm.getItems()) {
                if (DiaryItem.isDiary(inside)) return true;
            }
        }
        return false;
    }

    private ItemStack getHotbarOrOffhandItem(InventoryClickEvent e) {
        int button = e.getHotbarButton();
        if (button == -1) {
            return e.getWhoClicked().getInventory().getItemInOffHand();
        }
        if (button < 0 || button > 8) {
            return null;
        }
        return e.getWhoClicked().getInventory().getItem(button);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!enabled()) return;
        if (!isShulkerTop(e.getView())) return;

        final InventoryAction action = e.getAction();
        boolean topSlot = (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory()));
        if (topSlot
                && e.getClick() == ClickType.SWAP_OFFHAND
                && isDiaryOrBundleWithDiary(e.getWhoClicked().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
            return;
        }

        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (topSlot && isDiaryOrBundleWithDiary(e.getCursor())) {
                    e.setCancelled(true);
                }
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (!topSlot && isDiaryOrBundleWithDiary(e.getCurrentItem())) {
                    e.setCancelled(true);
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (topSlot) {
                    ItemStack hotbar = getHotbarOrOffhandItem(e);
                    if (isDiaryOrBundleWithDiary(hotbar)) {
                        e.setCancelled(true);
                    }
                }
            }
            case COLLECT_TO_CURSOR -> {
                if (isDiaryOrBundleWithDiary(e.getCursor())) e.setCancelled(true);
            }
            default -> { /* no-op */ }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!enabled()) return;
        if (!isShulkerTop(e.getView())) return;

        final Inventory top = e.getView().getTopInventory();
        if (top == null) return;

        Set<Integer> rawSlots = e.getRawSlots();
        boolean touchesTop = false;
        int topSize = top.getSize();
        for (int raw : rawSlots) {
            if (raw < topSize) { touchesTop = true; break; }
        }
        if (!touchesTop) return;

        if (isDiaryOrBundleWithDiary(e.getOldCursor())) {
            e.setCancelled(true);
        }
    }

    // Prevent hopper pipelines from inserting diaries (or bundles-with-diary) into shulkers
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (!enabled()) return;
        Inventory dest = e.getDestination();
        if (dest != null && dest.getHolder() instanceof ShulkerBox) {
            if (isDiaryOrBundleWithDiary(e.getItem())) {
                e.setCancelled(true);
            }
        }
    }
}
