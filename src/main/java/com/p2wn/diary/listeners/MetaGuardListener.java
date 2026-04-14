package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

public final class MetaGuardListener implements Listener {

    private final DiaryPlugin plugin;

    public MetaGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        for (ItemStack stack : event.getInventory().getContents()) {
            plugin.diaryService().canonicalize(stack);
        }

        if (event.getPlayer() instanceof Player player) {
            plugin.duplicateWatcher().refreshPlayerSnapshot(player);
        }
    }
}
