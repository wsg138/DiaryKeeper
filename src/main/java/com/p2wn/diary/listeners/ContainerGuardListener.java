package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryContainerAttemptEvent;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class ContainerGuardListener implements Listener {

    private static final Map<InventoryType, String> CONTAINER_KEYS = new EnumMap<>(InventoryType.class);
    static {
        CONTAINER_KEYS.put(InventoryType.CHEST, "restrictions.chests");
        CONTAINER_KEYS.put(InventoryType.BARREL, "restrictions.barrels");
        CONTAINER_KEYS.put(InventoryType.HOPPER, "restrictions.hoppers");
        CONTAINER_KEYS.put(InventoryType.DROPPER, "restrictions.droppers");
        CONTAINER_KEYS.put(InventoryType.DISPENSER, "restrictions.dispensers");
        CONTAINER_KEYS.put(InventoryType.FURNACE, "restrictions.furnaces");
        CONTAINER_KEYS.put(InventoryType.BLAST_FURNACE, "restrictions.furnaces");
        CONTAINER_KEYS.put(InventoryType.SMOKER, "restrictions.furnaces");
    }

    private boolean isBlockedTop(InventoryView view) {
        if (view == null) return false;
        Inventory top = view.getTopInventory();
        if (top == null) return false;
        String key = CONTAINER_KEYS.get(top.getType());
        if (key == null) return false;
        return DiaryPlugin.get().configManager().cfg().getBoolean(key, false);
    }

    private boolean isBlockedDestination(Inventory dest) {
        if (dest == null) return false;
        String key = CONTAINER_KEYS.get(dest.getType());
        if (key == null) return false;
        return DiaryPlugin.get().configManager().cfg().getBoolean(key, false);
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
        if (!isBlockedTop(e.getView())) return;

        final InventoryAction action = e.getAction();
        boolean topSlot = (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory()));
        String containerName = e.getView().getTopInventory().getType().name();
        if (topSlot
                && e.getClick() == ClickType.SWAP_OFFHAND
                && isDiaryOrBundleWithDiary(e.getWhoClicked().getInventory().getItemInOffHand())) {
            if (e.getWhoClicked() instanceof Player p)
                Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(p, e.getWhoClicked().getInventory().getItemInOffHand(), containerName));
            e.setCancelled(true);
            return;
        }

        switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (topSlot && isDiaryOrBundleWithDiary(e.getCursor())) {
                    if (e.getWhoClicked() instanceof Player p)
                        Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(p, e.getCursor(), containerName));
                    e.setCancelled(true);
                }
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (!topSlot && isDiaryOrBundleWithDiary(e.getCurrentItem())) {
                    if (e.getWhoClicked() instanceof Player p)
                        Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(p, e.getCurrentItem(), containerName));
                    e.setCancelled(true);
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (topSlot) {
                    ItemStack hotbar = getHotbarOrOffhandItem(e);
                    if (isDiaryOrBundleWithDiary(hotbar)) {
                        if (e.getWhoClicked() instanceof Player p)
                            Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(p, hotbar, containerName));
                        e.setCancelled(true);
                    }
                }
            }
            case COLLECT_TO_CURSOR -> {
                if (isDiaryOrBundleWithDiary(e.getCursor())) {
                    if (e.getWhoClicked() instanceof Player p)
                        Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(p, e.getCursor(), containerName));
                    e.setCancelled(true);
                }
            }
            default -> { /* no-op */ }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!isBlockedTop(e.getView())) return;

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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        if (!isBlockedDestination(e.getDestination())) return;
        if (isDiaryOrBundleWithDiary(e.getItem())) {
            e.setCancelled(true);
        }
    }
}
