package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.TrackedDiaryRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;

public final class RestoreGuiListener implements Listener {

    private enum Action { OWNER, ADMIN, DUPLICATE }

    private final DiaryPlugin plugin;

    public RestoreGuiListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void openRestoreGui(Player player, TrackedDiaryRecord record) {
        Inventory inventory = Bukkit.createInventory(new Holder(record, null, false), 9, "Restore Diary");
        inventory.setItem(0, button(Material.LIME_WOOL, "Purge and Return to Owner", List.of(
                "Remove every discoverable copy.",
                "Offline players and known chunks may remain pending.",
                "Restore only after purge work succeeds."
        )));
        inventory.setItem(2, button(Material.YELLOW_WOOL, "Purge and Give to Me", List.of(
                "The original owner does not change.",
                "Full inventory delivery is queued.",
                "Restore waits for purge completion."
        )));
        inventory.setItem(4, button(Material.RED_WOOL, "Spawn Additional Copy", List.of(
                "WARNING: intentionally creates another copy.",
                "No existing copy is removed."
        )));
        inventory.setItem(5, button(Material.BOOK, "Tracked Diary", List.of(
                "Owner: " + record.ownerName(),
                "ID: " + record.diaryId()
        )));
        inventory.setItem(6, button(Material.COMPASS, "Known Locations", List.of(
                "Active: " + record.activeLocationCount(),
                "Recent/history: " + record.locations().size()
        )));
        inventory.setItem(7, button(Material.HOPPER, "Pending Deliveries", List.of(
                Integer.toString(plugin.diaryStore().getPendingDeliveryCount(record.ownerUuid()))
        )));
        inventory.setItem(8, button(Material.BARRIER, "Cancel", List.of("Close without changes.")));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (holder.confirmation()) {
            handleConfirmation(player, holder, event.getRawSlot());
            return;
        }
        Action action = switch (event.getRawSlot()) {
            case 0 -> Action.OWNER;
            case 2 -> Action.ADMIN;
            case 4 -> Action.DUPLICATE;
            default -> null;
        };
        if (Objects.isNull(action)) {
            if (event.getRawSlot() == 8) {
                player.closeInventory();
            }
            return;
        }
        openConfirmation(player, holder.record(), action);
    }

    private void openConfirmation(Player player, TrackedDiaryRecord record, Action action) {
        Inventory inventory = Bukkit.createInventory(new Holder(record, action, true), 9, "Confirm Diary Action");
        String warning = action == Action.DUPLICATE
                ? "This intentionally creates another copy."
                : "This starts persistent purge work.";
        inventory.setItem(3, button(Material.LIME_WOOL, "Confirm", List.of(warning, "This action is audited.")));
        inventory.setItem(5, button(Material.BARRIER, "Go Back", List.of("Return to restore options.")));
        player.openInventory(inventory);
    }

    private void handleConfirmation(Player player, Holder holder, int slot) {
        if (slot == 5) {
            openRestoreGui(player, holder.record());
            return;
        }
        if (slot != 3 || holder.action() == null) {
            return;
        }
        if (holder.action() == Action.DUPLICATE) {
            plugin.diaryPurgeService().restoreDuplicate(holder.record(), player);
            player.sendMessage(plugin.configManager().msg("restore.duplicate-started"));
            player.closeInventory();
            return;
        }
        PurgeDestination destination = holder.action() == Action.OWNER
                ? PurgeDestination.OWNER : PurgeDestination.ADMIN;
        PurgeOperation operation = plugin.diaryPurgeService().begin(holder.record(), destination, player);
        if (operation.destination() != destination || !java.util.Objects.equals(operation.adminUuid(), player.getUniqueId())) {
            player.sendMessage("§eNo new purge started. Existing operation §f" + operation.operationId()
                    + " §edestination=§f" + operation.destination() + " §estate=§f" + operation.state());
            player.closeInventory();
            return;
        }
        player.sendMessage(plugin.configManager().msg("purge.started", java.util.Map.of(
                "operation", operation.operationId().toString(),
                "state", operation.state().name(),
                "players", Integer.toString(operation.pendingPlayers().size()),
                "chunks", Integer.toString(operation.pendingChunks())
        )));
        player.closeInventory();
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private record Holder(TrackedDiaryRecord record, Action action, boolean confirmation) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
