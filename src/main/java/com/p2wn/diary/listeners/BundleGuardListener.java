package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public class BundleGuardListener implements Listener {

    private boolean enabled() {
        var cfg = DiaryPlugin.get().configManager().cfg();
        if (cfg.contains("restrictions.bundles")) {
            return cfg.getBoolean("restrictions.bundles", true);
        }
        return cfg.getBoolean("restrictions.bundles_and_enderchest", true);
    }

    private boolean isBundle(ItemStack i) {
        return i != null && i.getType() == Material.BUNDLE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!enabled()) return;

        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        InventoryAction action = e.getAction();

        // Common bundling interactions:
        // - SWAP_WITH_CURSOR: cursor bundle + click diary OR cursor diary + click bundle
        // - PLACE_* onto a bundle slot with diary in cursor (client may try to insert)
        // We'll conservatively block any interaction where one side is bundle and the other is diary.

        // Cursor bundle + click diary  OR  cursor diary + click bundle
        if (action == InventoryAction.SWAP_WITH_CURSOR) {
            if ((isBundle(cursor) && DiaryItem.isDiary(current)) || (DiaryItem.isDiary(cursor) && isBundle(current))) {
                e.setCancelled(true);
                return;
            }
        }

        // Placing diary onto bundle slot (or vice versa)
        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME -> {
                if (DiaryItem.isDiary(cursor) && isBundle(current)) {
                    e.setCancelled(true);
                }
            }
            default -> { /* no-op */ }
        }
    }
}
