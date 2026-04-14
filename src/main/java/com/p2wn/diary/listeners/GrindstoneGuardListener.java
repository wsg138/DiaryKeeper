package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;

public final class GrindstoneGuardListener implements Listener {

    private final DiaryPlugin plugin;

    public GrindstoneGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepare(PrepareGrindstoneEvent event) {
        GrindstoneInventory inventory = event.getInventory();
        ItemStack upper = inventory.getUpperItem();
        ItemStack lower = inventory.getLowerItem();
        if (plugin.diaryService().isDiary(upper) || plugin.diaryService().isDiary(lower)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof GrindstoneInventory)) {
            return;
        }
        if (plugin.diaryService().isDiary(event.getCurrentItem()) || plugin.diaryService().isDiary(event.getCursor())) {
            event.setCancelled(true);
        }
    }
}
