package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class BundleGuardListener implements Listener {

    private final DiaryPlugin plugin;

    public BundleGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.restrictionService().isBundleRestrictionEnabled()) {
            return;
        }

        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        InventoryAction action = event.getAction();

        if (action == InventoryAction.SWAP_WITH_CURSOR) {
            if (plugin.restrictionService().isDiaryOrNestedDiary(cursor) && current != null && current.getType() == org.bukkit.Material.BUNDLE) {
                event.setCancelled(true);
                return;
            }
            if (plugin.restrictionService().isDiaryOrNestedDiary(current) && cursor != null && cursor.getType() == org.bukkit.Material.BUNDLE) {
                event.setCancelled(true);
                return;
            }
        }

        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                if (plugin.restrictionService().isDiaryOrNestedDiary(cursor)
                        && current != null
                        && current.getType() == org.bukkit.Material.BUNDLE) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }
}
