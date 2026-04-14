package com.p2wn.diary.listeners;

import com.p2wn.diary.item.DiaryItem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Blocks disenchanting Diaries via grindstones.
 */
public class GrindstoneGuardListener implements Listener {

    /** Prevent producing any grindstone result if a Diary is involved. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareGrindstoneEvent e) {
        GrindstoneInventory inv = e.getInventory();
        ItemStack upper = inv.getUpperItem();
        ItemStack lower = inv.getLowerItem();

        if ((upper != null && DiaryItem.isDiary(upper)) || (lower != null && DiaryItem.isDiary(lower))) {
            e.setResult(null);
        }
    }

    /** Safety: block placing or taking a diary in grindstone inventory. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof GrindstoneInventory)) return;

        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();

        if ((current != null && DiaryItem.isDiary(current)) || (cursor != null && DiaryItem.isDiary(cursor))) {
            e.setCancelled(true);
        }
    }
}
