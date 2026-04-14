package com.p2wn.diary.logic;

import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryLocationType;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingRemoval;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DiaryRestoreService {

    public enum RemovalResult {
        REMOVED,
        QUEUED,
        FAILED
    }

    private final ConfigManager configManager;
    private final DiaryStore diaryStore;
    private final DiaryItem diaryItem;
    private final DiaryService diaryService;
    private final DeliveryService deliveryService;
    private final DiaryTrackerService trackerService;

    public DiaryRestoreService(
            ConfigManager configManager,
            DiaryStore diaryStore,
            DiaryItem diaryItem,
            DiaryService diaryService,
            DeliveryService deliveryService,
            DiaryTrackerService trackerService
    ) {
        this.configManager = configManager;
        this.diaryStore = diaryStore;
        this.diaryItem = diaryItem;
        this.diaryService = diaryService;
        this.deliveryService = deliveryService;
        this.trackerService = trackerService;
    }

    public TrackedDiaryRecord getTrackedDiary(String diaryId) {
        return diaryStore.getTrackedDiary(diaryId);
    }

    public RemovalResult removeCurrentTrackedCopy(TrackedDiaryRecord record) {
        if (record == null || record.lastKnownLocation() == null) {
            return RemovalResult.FAILED;
        }

        return switch (record.lastKnownLocation().type()) {
            case PLAYER_INVENTORY, PLAYER_ENDER_CHEST -> removeFromPlayerScope(record);
            case BLOCK_CONTAINER -> removeFromBlockContainer(record);
            case GROUND -> removeGroundEntity(record);
            case DELIVERY_QUEUE -> removeQueuedDelivery(record);
            default -> RemovalResult.FAILED;
        };
    }

    public void restoreDiaryToOwner(TrackedDiaryRecord record, boolean announceQueued) {
        if (record == null || record.snapshot() == null) {
            return;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(record.ownerUuid());
        Player online = owner.getPlayer();
        if (online != null && online.isOnline()) {
            if (!online.getInventory().addItem(record.snapshot().clone()).isEmpty()) {
                deliveryService.queue(record.ownerUuid(), DeliveryReason.ADMIN_ISSUE, record.snapshot().clone());
                trackerService.trackQueuedDelivery(record.ownerUuid(), online.getName(), record.snapshot().clone());
            } else {
                trackerService.trackPlayerInventory(online);
            }
        } else {
            String name = owner.getName() != null ? owner.getName() : record.ownerUuid().toString();
            deliveryService.queue(record.ownerUuid(), DeliveryReason.ADMIN_ISSUE, record.snapshot().clone());
            trackerService.trackQueuedDelivery(record.ownerUuid(), name, record.snapshot().clone());
        }
    }

    public void processPendingRemovals(Player player) {
        List<PendingRemoval> pendingRemovals = diaryStore.getPendingRemovals(player.getUniqueId());
        if (pendingRemovals.isEmpty()) {
            return;
        }

        for (PendingRemoval pendingRemoval : pendingRemovals) {
            if (pendingRemoval.locationType() == DiaryLocationType.PLAYER_INVENTORY) {
                removeDiaryFromInventory(player.getInventory(), pendingRemoval.diaryId());
            } else if (pendingRemoval.locationType() == DiaryLocationType.PLAYER_ENDER_CHEST) {
                removeDiaryFromInventory(player.getEnderChest(), pendingRemoval.diaryId());
            }
        }

        diaryStore.clearPendingRemovals(player.getUniqueId());
        trackerService.trackPlayerInventory(player);
        trackerService.trackEnderChest(player);
    }

    private RemovalResult removeFromPlayerScope(TrackedDiaryRecord record) {
        UUID holderUuid = record.lastKnownLocation().holderUuid();
        if (holderUuid == null) {
            return RemovalResult.FAILED;
        }

        Player player = Bukkit.getPlayer(holderUuid);
        if (player != null && player.isOnline()) {
            boolean removed = switch (record.lastKnownLocation().type()) {
                case PLAYER_INVENTORY -> removeDiaryFromInventory(player.getInventory(), record.diaryId());
                case PLAYER_ENDER_CHEST -> removeDiaryFromInventory(player.getEnderChest(), record.diaryId());
                default -> false;
            };
            if (removed) {
                trackerService.trackPlayerInventory(player);
                trackerService.trackEnderChest(player);
                return RemovalResult.REMOVED;
            }
            return RemovalResult.FAILED;
        }

        diaryStore.queuePendingRemoval(holderUuid, new PendingRemoval(record.diaryId(), record.lastKnownLocation().type(), holderUuid));
        return RemovalResult.QUEUED;
    }

    private RemovalResult removeFromBlockContainer(TrackedDiaryRecord record) {
        String worldName = record.lastKnownLocation().worldName();
        Integer x = record.lastKnownLocation().x();
        Integer y = record.lastKnownLocation().y();
        Integer z = record.lastKnownLocation().z();
        if (worldName == null || x == null || y == null || z == null) {
            return RemovalResult.FAILED;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return RemovalResult.FAILED;
        }

        Block block = world.getBlockAt(x, y, z);
        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return RemovalResult.FAILED;
        }

        boolean removed = removeDiaryFromInventory(container.getInventory(), record.diaryId());
        if (removed) {
            trackerService.trackBlockInventory(block, container.getInventory(), List.of());
            state.update();
            return RemovalResult.REMOVED;
        }
        return RemovalResult.FAILED;
    }

    private RemovalResult removeGroundEntity(TrackedDiaryRecord record) {
        String worldName = record.lastKnownLocation().worldName();
        Integer x = record.lastKnownLocation().x();
        Integer y = record.lastKnownLocation().y();
        Integer z = record.lastKnownLocation().z();
        if (worldName == null || x == null || y == null || z == null) {
            return RemovalResult.FAILED;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return RemovalResult.FAILED;
        }

        for (Item entity : world.getEntitiesByClass(Item.class)) {
            Location location = entity.getLocation();
            if (location.getBlockX() == x && location.getBlockY() == y && location.getBlockZ() == z) {
                if (containsDiary(entity.getItemStack(), record.diaryId())) {
                    entity.remove();
                    return RemovalResult.REMOVED;
                }
            }
        }
        return RemovalResult.FAILED;
    }

    private RemovalResult removeQueuedDelivery(TrackedDiaryRecord record) {
        UUID holderUuid = record.lastKnownLocation().holderUuid();
        if (holderUuid == null) {
            return RemovalResult.FAILED;
        }
        return diaryStore.removePendingDeliveriesByDiaryId(holderUuid, record.diaryId())
                ? RemovalResult.REMOVED
                : RemovalResult.FAILED;
    }

    private boolean removeDiaryFromInventory(Inventory inventory, String diaryId) {
        ItemStack[] contents = inventory.getContents();
        boolean removedAny = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack updated = removeDiaryFromItem(contents[slot], diaryId);
            if (updated != contents[slot]) {
                inventory.setItem(slot, updated);
                removedAny = true;
            }
        }
        return removedAny;
    }

    private ItemStack removeDiaryFromItem(ItemStack stack, String diaryId) {
        if (stack == null || stack.getType().isAir()) {
            return stack;
        }

        if (diaryItem.isDiary(stack)) {
            return diaryId.equals(diaryItem.getDiaryId(stack)) ? null : stack;
        }

        if (stack.getType() == Material.BUNDLE && stack.hasItemMeta() && stack.getItemMeta() instanceof BundleMeta bundleMeta) {
            List<ItemStack> updatedItems = new ArrayList<>();
            boolean changed = false;
            for (ItemStack nested : bundleMeta.getItems()) {
                ItemStack updatedNested = removeDiaryFromItem(nested, diaryId);
                if (updatedNested != nested) {
                    changed = true;
                }
                if (updatedNested != null) {
                    updatedItems.add(updatedNested);
                }
            }
            if (!changed) {
                return stack;
            }
            ItemStack clone = stack.clone();
            BundleMeta cloneMeta = (BundleMeta) clone.getItemMeta();
            cloneMeta.setItems(updatedItems);
            clone.setItemMeta(cloneMeta);
            return clone;
        }

        if (stack.hasItemMeta() && stack.getItemMeta() instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            boolean changed = false;
            ItemStack[] shulkerContents = shulkerBox.getInventory().getContents();
            for (int i = 0; i < shulkerContents.length; i++) {
                ItemStack updatedNested = removeDiaryFromItem(shulkerContents[i], diaryId);
                if (updatedNested != shulkerContents[i]) {
                    shulkerBox.getInventory().setItem(i, updatedNested);
                    changed = true;
                }
            }
            if (!changed) {
                return stack;
            }
            ItemStack clone = stack.clone();
            BlockStateMeta cloneMeta = (BlockStateMeta) clone.getItemMeta();
            cloneMeta.setBlockState(shulkerBox);
            clone.setItemMeta(cloneMeta);
            return clone;
        }

        return stack;
    }

    private boolean containsDiary(ItemStack stack, String diaryId) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (diaryItem.isDiary(stack)) {
            return diaryId.equals(diaryItem.getDiaryId(stack));
        }
        if (stack.getType() == Material.BUNDLE && stack.hasItemMeta() && stack.getItemMeta() instanceof BundleMeta bundleMeta) {
            for (ItemStack nested : bundleMeta.getItems()) {
                if (containsDiary(nested, diaryId)) {
                    return true;
                }
            }
        }
        if (stack.hasItemMeta() && stack.getItemMeta() instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            for (ItemStack nested : shulkerBox.getInventory().getContents()) {
                if (containsDiary(nested, diaryId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
