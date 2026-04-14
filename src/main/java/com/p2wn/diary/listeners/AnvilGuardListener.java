package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

public final class AnvilGuardListener implements Listener {

    private final DiaryPlugin plugin;

    public AnvilGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        ItemStack left = inventory.getFirstItem();
        ItemStack right = inventory.getSecondItem();
        if (plugin.diaryService().isDiary(left) || plugin.diaryService().isDiary(right)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory)) {
            return;
        }
        if (plugin.diaryService().isDiary(event.getCurrentItem()) || plugin.diaryService().isDiary(event.getCursor())) {
            event.setCancelled(true);
        }
    }
}
