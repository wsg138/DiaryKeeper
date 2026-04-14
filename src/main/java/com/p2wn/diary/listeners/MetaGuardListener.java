package com.p2wn.diary.listeners;

import com.p2wn.diary.item.DiaryItem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

public class MetaGuardListener implements Listener {

    /** Re-assert canonical title/lore/glint after inventories are manipulated.
     *  Lightweight: runs only when an inventory closes, and only touches diaries. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (it == null) continue;
            if (DiaryItem.isDiary(it)) {
                DiaryItem.canonicalize(it);
            }
        }
    }
}
