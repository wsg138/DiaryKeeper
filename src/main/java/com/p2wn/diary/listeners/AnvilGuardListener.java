package com.p2wn.diary.listeners;

import com.p2wn.diary.item.DiaryItem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;   // âœ… correct event class
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

/**
 * Blocks renaming/lore edits to Diaries via anvils.
 * - Clears the anvil result if a diary is present.
 * - Cancels clicks that would take a modified diary out of the anvil.
 */
public class AnvilGuardListener implements Listener {

    /** Prevent producing any anvil result if a Diary is involved. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareAnvilEvent e) {         // âœ… correct event name
        AnvilInventory inv = e.getInventory();           // âœ… exists on PrepareAnvilEvent
        ItemStack left = inv.getFirstItem();
        ItemStack right = inv.getSecondItem();

        if ((left != null && DiaryItem.isDiary(left)) || (right != null && DiaryItem.isDiary(right))) {
            e.setResult(null);                           // âœ… valid on PrepareAnvilEvent
        }
    }

    /** Safety: if something slipped through, block taking any diary out of the anvil. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory)) return;

        ItemStack current = e.getCurrentItem();          // âœ… method exists
        ItemStack cursor  = e.getCursor();

        if ((current != null && DiaryItem.isDiary(current)) || (cursor != null && DiaryItem.isDiary(cursor))) {
            e.setCancelled(true);
        }
    }
}
