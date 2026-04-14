package com.p2wn.diary.logic;

import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.EnumMap;
import java.util.Map;

public final class RestrictionService {

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

    private final ConfigManager configManager;
    private final DiaryItem diaryItem;

    public RestrictionService(ConfigManager configManager, DiaryItem diaryItem) {
        this.configManager = configManager;
        this.diaryItem = diaryItem;
    }

    public boolean isBundleRestrictionEnabled() {
        if (configManager.cfg().contains("restrictions.bundles")) {
            return configManager.cfg().getBoolean("restrictions.bundles", true);
        }
        return configManager.cfg().getBoolean("restrictions.bundles_and_enderchest", true);
    }

    public boolean isEnderChestRestrictionEnabled() {
        if (configManager.cfg().contains("restrictions.enderchest")) {
            return configManager.cfg().getBoolean("restrictions.enderchest", true);
        }
        return configManager.cfg().getBoolean("restrictions.bundles_and_enderchest", true);
    }

    public boolean isShulkerRestrictionEnabled() {
        return configManager.cfg().getBoolean("restrictions.shulkers", false);
    }

    public boolean isRestrictedInventoryType(InventoryType type) {
        String key = CONTAINER_KEYS.get(type);
        return key != null && configManager.cfg().getBoolean(key, false);
    }

    public boolean isRestrictedTopContainer(InventoryView view) {
        if (view == null || view.getTopInventory() == null) {
            return false;
        }
        return isRestrictedInventoryType(view.getTopInventory().getType());
    }

    public boolean isEnderChestTop(InventoryView view) {
        return view != null
                && view.getTopInventory() != null
                && view.getTopInventory().getType() == InventoryType.ENDER_CHEST;
    }

    public boolean isShulkerTop(InventoryView view) {
        if (view == null || view.getTopInventory() == null) {
            return false;
        }
        InventoryHolder holder = view.getTopInventory().getHolder();
        return holder instanceof ShulkerBox;
    }

    public boolean isRestrictedDestination(Inventory inventory) {
        return inventory != null && isRestrictedInventoryType(inventory.getType());
    }

    public boolean isDiaryOrNestedDiary(ItemStack item) {
        if (item == null) {
            return false;
        }
        if (diaryItem.isDiary(item)) {
            return true;
        }
        if (item.getType() == Material.BUNDLE && item.hasItemMeta() && item.getItemMeta() instanceof BundleMeta meta) {
            for (ItemStack nested : meta.getItems()) {
                if (diaryItem.isDiary(nested)) {
                    return true;
                }
            }
        }
        return false;
    }

    public ItemStack getHotbarOrOffhandItem(InventoryClickEvent event) {
        int button = event.getHotbarButton();
        if (button == -1) {
            return event.getWhoClicked().getInventory().getItemInOffHand();
        }
        if (button < 0 || button > 8) {
            return null;
        }
        return event.getWhoClicked().getInventory().getItem(button);
    }
}
