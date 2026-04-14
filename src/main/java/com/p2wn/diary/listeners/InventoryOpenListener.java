package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;

public class InventoryOpenListener implements Listener {

    private final DiaryPlugin plugin;

    public InventoryOpenListener(DiaryPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        plugin.duplicateWatcher().onInventoryOpen(e.getPlayer(), e.getInventory());
    }
}
