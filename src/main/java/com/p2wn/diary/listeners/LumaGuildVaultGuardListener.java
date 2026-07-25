package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryContainerAttemptEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LumaGuildVaultGuardListener implements Listener {

    private static final String CONTAINER_NAME = "LUMAGUILDS_VAULT";

    private final DiaryPlugin plugin;
    private final Map<String, Long> lastAlertAt = new HashMap<>();

    public LumaGuildVaultGuardListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.restrictionService().isLumaGuildVaultTop(event.getView())) {
            return;
        }

        boolean topSlot = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());

        if (topSlot
                && event.getClick() == ClickType.SWAP_OFFHAND
                && plugin.restrictionService().isDiaryOrNestedDiary(event.getWhoClicked().getInventory().getItemInOffHand())) {
            block(event, event.getWhoClicked().getInventory().getItemInOffHand());
            return;
        }

        switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> {
                if (topSlot && plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
                    block(event, event.getCursor());
                }
            }
            case MOVE_TO_OTHER_INVENTORY -> {
                if (!topSlot && plugin.restrictionService().isDiaryOrNestedDiary(event.getCurrentItem())) {
                    block(event, event.getCurrentItem());
                }
            }
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                if (topSlot) {
                    ItemStack hotbar = plugin.restrictionService().getHotbarOrOffhandItem(event);
                    if (plugin.restrictionService().isDiaryOrNestedDiary(hotbar)) {
                        block(event, hotbar);
                    }
                }
            }
            case COLLECT_TO_CURSOR -> {
                if (plugin.restrictionService().isDiaryOrNestedDiary(event.getCursor())) {
                    block(event, event.getCursor());
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.restrictionService().isLumaGuildVaultTop(event.getView())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Set<Integer> rawSlots = event.getRawSlots();
        boolean touchesTop = false;
        for (int rawSlot : rawSlots) {
            if (rawSlot < top.getSize()) {
                touchesTop = true;
                break;
            }
        }
        if (touchesTop && plugin.restrictionService().isDiaryOrNestedDiary(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        if (!plugin.restrictionService().isLumaGuildVault(inventory) || !containsDiary(inventory)) {
            return;
        }

        Player player = event.getPlayer() instanceof Player p ? p : null;
        String viewer = player == null ? "unknown" : player.getName();
        String vaultName = vaultName(inventory);
        String alertKey = vaultName + ":" + viewer;
        if (!shouldAlert(alertKey)) {
            return;
        }

        String message = plugin.configManager().msg("staff.guild-vault-diary-alert", Map.of(
                "player", viewer,
                "vault", vaultName
        ));
        plugin.getLogger().warning("[Diary] " + viewer + " opened " + vaultName + " containing a diary or diary-containing item.");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("diary.notify")) {
                online.sendMessage(message);
            }
        }
    }

    private void block(InventoryClickEvent event, ItemStack stack) {
        if (event.getWhoClicked() instanceof Player player && stack != null) {
            Bukkit.getPluginManager().callEvent(new DiaryContainerAttemptEvent(player, stack.clone(), CONTAINER_NAME));
        }
        event.setCancelled(true);
    }

    private boolean containsDiary(Inventory inventory) {
        for (ItemStack stack : inventory.getContents()) {
            if (plugin.restrictionService().isDiaryOrNestedDiary(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldAlert(String key) {
        long now = System.currentTimeMillis();
        long debounceMs = Math.max(1L, plugin.configManager().cfg().getLong("duplicates.debounce-seconds", 60L)) * 1000L;
        Long last = lastAlertAt.get(key);
        if (last != null && now - last < debounceMs) {
            return false;
        }
        lastAlertAt.put(key, now);
        return true;
    }

    private String vaultName(Inventory inventory) {
        Object holder = inventory.getHolder();
        if (holder == null) {
            return "guild vault";
        }
        try {
            Object guildName = holder.getClass().getMethod("getGuildName").invoke(holder);
            if (guildName instanceof String name && !name.isBlank()) {
                return name + " Vault";
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Object guildId = holder.getClass().getMethod("getGuildId").invoke(holder);
            if (guildId instanceof UUID id) {
                return "Guild Vault " + id;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return "guild vault";
    }
}
