package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.logic.DiaryRestoreService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RestoreGuiListener implements Listener {

    private static final String TITLE = "Restore Diary";

    private final DiaryPlugin plugin;
    private final Map<UUID, RestoreSession> sessions = new HashMap<>();

    public RestoreGuiListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void openRestoreGui(Player player, TrackedDiaryRecord record) {
        Inventory inventory = Bukkit.createInventory(new Holder(player.getUniqueId()), 9, TITLE);
        inventory.setItem(2, createButton(Material.LIME_WOOL, "Replace Existing", List.of(
                "Attempt to remove the tracked copy first.",
                "If it cannot be safely removed, restore is cancelled."
        )));
        inventory.setItem(6, createButton(Material.RED_WOOL, "Spawn Second Copy", List.of(
                "Keep the tracked copy where it is.",
                "Restore another copy to the owner."
        )));
        inventory.setItem(4, createButton(Material.BOOK, "Tracked Copy", List.of(
                record.lastKnownLocation() == null ? "No current location recorded." : record.lastKnownLocation().description()
        )));

        sessions.put(player.getUniqueId(), new RestoreSession(record));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);

        RestoreSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            player.closeInventory();
            return;
        }

        if (event.getRawSlot() == 2) {
            DiaryRestoreService.RemovalResult result = plugin.diaryRestoreService().removeCurrentTrackedCopy(session.record());
            if (result == DiaryRestoreService.RemovalResult.FAILED) {
                player.sendMessage(plugin.configManager().msg("restore.replace-failed"));
                player.closeInventory();
                return;
            }
            plugin.diaryRestoreService().restoreDiaryToOwner(session.record(), true);
            player.sendMessage(plugin.configManager().msg("restore.replace-success"));
        } else if (event.getRawSlot() == 6) {
            plugin.diaryRestoreService().restoreDiaryToOwner(session.record(), true);
            player.sendMessage(plugin.configManager().msg("restore.spawn-second-success"));
        }

        player.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof Holder) {
            sessions.remove(player.getUniqueId());
        }
    }

    private ItemStack createButton(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private record RestoreSession(TrackedDiaryRecord record) {}

    private record Holder(UUID playerId) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
