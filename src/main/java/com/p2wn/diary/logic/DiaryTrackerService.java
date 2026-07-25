package com.p2wn.diary.logic;

import com.p2wn.diary.data.DiaryLocationRecord;
import com.p2wn.diary.data.DiaryLocationType;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingDelivery;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class DiaryTrackerService {

    private final DiaryStore diaryStore;
    private final DiaryItem diaryItem;
    private PerformanceMonitor performanceMonitor;

    public DiaryTrackerService(DiaryStore diaryStore, DiaryItem diaryItem) {
        this.diaryStore = diaryStore;
        this.diaryItem = diaryItem;
    }

    public void setPerformanceMonitor(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }

    public void trackPlayerInventory(Player player) {
        countInventoryScan();
        scanInventory(player.getInventory(), inventoryLocation(
                DiaryLocationType.PLAYER_INVENTORY,
                "in " + player.getName() + "'s inventory",
                player.getUniqueId(),
                player.getName(),
                null,
                null,
                List.of()
        ));
    }

    public void trackEnderChest(Player player) {
        countContainerScan();
        scanInventory(player.getEnderChest(), inventoryLocation(
                DiaryLocationType.PLAYER_ENDER_CHEST,
                "in " + player.getName() + "'s ender chest",
                player.getUniqueId(),
                player.getName(),
                null,
                null,
                List.of()
        ));
    }

    public void trackInventoryView(Player player, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Player holderPlayer) {
            if (holderPlayer.getUniqueId().equals(player.getUniqueId())) {
                trackPlayerInventory(player);
            }
            return;
        }
        if (inventory.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) {
            trackEnderChest(player);
            return;
        }
        if (holder instanceof BlockState state) {
            trackBlockInventory(state.getBlock(), inventory, List.of());
            return;
        }
        if (holder instanceof DoubleChest doubleChest) {
            Location location = doubleChest.getLocation();
            if (location != null && location.getBlock() != null) {
                trackBlockInventory(location.getBlock(), inventory, List.of());
            }
        }
    }

    public void trackBlockInventory(Block block, Inventory inventory, List<String> nestedPath) {
        if (block == null || inventory == null) {
            return;
        }
        String containerName = prettifyMaterial(block.getType());
        String description = nestedDescription(nestedPath, "in a " + containerName + " at " + formatCoords(block.getLocation()));
        countContainerScan();
        scanInventory(inventory, inventoryLocation(
                DiaryLocationType.BLOCK_CONTAINER,
                description,
                null,
                null,
                block.getWorld(),
                block.getLocation(),
                nestedPath
        ));
    }

    public void trackGroundItem(Item item) {
        if (item == null || item.isDead()) {
            return;
        }
        Location location = item.getLocation();
        List<String> nestedPath = List.of();
        scanItemStack(item.getItemStack(), locationRecord(
                DiaryLocationType.GROUND,
                nestedDescription(nestedPath, "on the ground at " + formatCoords(location)),
                null,
                null,
                location.getWorld(),
                location,
                null,
                item.getUniqueId(),
                nestedPath
        ));
    }

    public void trackQueuedDelivery(UUID playerId, String playerName, ItemStack item) {
        DiaryLocationRecord base = locationRecord(
                DiaryLocationType.DELIVERY_QUEUE,
                "queued for delivery to " + playerName,
                playerId,
                playerName,
                null,
                null,
                null,
                null,
                List.of()
        );
        scanItemStack(item, withSlot(base, Math.max(0, diaryStore.getPendingDeliveryCount(playerId) - 1)));
    }

    public void refreshQueuedDeliveries(UUID playerId, String playerName) {
        Set<String> observed = new HashSet<>();
        List<PendingDelivery> deliveries = diaryStore.getPendingDeliveries(playerId, Integer.MAX_VALUE);
        for (int slot = 0; slot < deliveries.size(); slot++) {
            DiaryLocationRecord base = locationRecord(
                    DiaryLocationType.DELIVERY_QUEUE,
                    "queued for delivery to " + playerName,
                    playerId, playerName, null, null, null, null, List.of());
            scanItemStack(deliveries.get(slot).item(), withSlot(base, slot), observed);
        }
        diaryStore.markTrackedScopeInactive(DiaryLocationType.DELIVERY_QUEUE, playerId,
                null, null, null, null, observed);
    }

    public void trackSnapshotOnly(ItemStack item) {
        if (!diaryItem.isDiary(item)) {
            return;
        }
        String diaryId = diaryItem.getDiaryId(item);
        if (diaryId == null) {
            return;
        }
        var existing = diaryStore.getTrackedDiary(diaryId);
        if (existing != null && existing.lastKnownLocation() != null) {
            UUID ownerId = diaryItem.getOwner(item);
            diaryStore.updateTrackedSnapshot(diaryId, ownerId, resolveOwnerName(ownerId), item.clone());
        }
    }

    private void scanInventory(Inventory inventory, DiaryLocationRecord baseLocation) {
        Set<String> observed = new HashSet<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            scanItemStack(contents[slot], withSlot(baseLocation, slot), observed);
        }
        diaryStore.markTrackedScopeInactive(baseLocation.type(), baseLocation.holderUuid(),
                baseLocation.worldUuid(), baseLocation.x(), baseLocation.y(), baseLocation.z(), observed);
    }

    public void markGroundItemInactive(UUID entityUuid) {
        diaryStore.markEntityLocationInactive(entityUuid);
    }

    private void scanItemStack(ItemStack stack, DiaryLocationRecord currentLocation) {
        scanItemStack(stack, currentLocation, null);
    }

    private void scanItemStack(ItemStack stack, DiaryLocationRecord currentLocation, Set<String> observed) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }

        if (diaryItem.isDiary(stack)) {
            String diaryId = diaryItem.getDiaryId(stack);
            UUID ownerId = diaryItem.getOwner(stack);
            if (diaryId != null) {
                diaryStore.updateTrackedDiary(diaryId, ownerId, resolveOwnerName(ownerId), stack.clone(), currentLocation);
                if (observed != null) {
                    observed.add(currentLocation.identityKey());
                }
            }
            return;
        }

        if (stack.getType() == Material.BUNDLE && stack.hasItemMeta() && stack.getItemMeta() instanceof BundleMeta bundleMeta) {
            List<ItemStack> items = bundleMeta.getItems();
            for (int index = 0; index < items.size(); index++) {
                scanItemStack(items.get(index), withNested(currentLocation, "inside bundle slot " + index), observed);
            }
            return;
        }

        if (stack.hasItemMeta() && stack.getItemMeta() instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            if (performanceMonitor != null) {
                performanceMonitor.shulkerScanned();
            }
            ItemStack[] items = shulkerBox.getInventory().getContents();
            for (int index = 0; index < items.length; index++) {
                scanItemStack(items[index], withNested(currentLocation, "inside shulker slot " + index), observed);
            }
        }
    }

    private DiaryLocationRecord withSlot(DiaryLocationRecord base, int slot) {
        String scope = base.type().name().toLowerCase(Locale.ROOT);
        return new DiaryLocationRecord(base.type(), base.description(), base.holderUuid(), base.holderName(),
                base.worldUuid(), base.worldName(), base.x(), base.y(), base.z(), base.containerType(),
                base.entityUuid(), base.nestedPath(), scope, slot,
                Instant.now().getEpochSecond(), Instant.now().getEpochSecond(), true);
    }

    private DiaryLocationRecord withNested(DiaryLocationRecord base, String nestedSegment) {
        List<String> path = new ArrayList<>(base.nestedPath());
        path.add(nestedSegment);
        return new DiaryLocationRecord(
                base.type(),
                nestedDescription(path, tailDescription(base.description())),
                base.holderUuid(),
                base.holderName(),
                base.worldUuid(),
                base.worldName(),
                base.x(),
                base.y(),
                base.z(),
                base.containerType(),
                base.entityUuid(),
                path,
                base.inventoryScope(),
                base.slot(),
                Instant.now().getEpochSecond(),
                Instant.now().getEpochSecond(),
                true
        );
    }

    private DiaryLocationRecord inventoryLocation(
            DiaryLocationType type,
            String description,
            UUID holderUuid,
            String holderName,
            World world,
            Location location,
            List<String> nestedPath
    ) {
        return locationRecord(type, description, holderUuid, holderName, world, location, null, null, nestedPath);
    }

    private DiaryLocationRecord locationRecord(
            DiaryLocationType type,
            String description,
            UUID holderUuid,
            String holderName,
            World world,
            Location location,
            String containerType,
            UUID entityUuid,
            List<String> nestedPath
    ) {
        return new DiaryLocationRecord(
                type,
                description,
                holderUuid,
                holderName,
                world == null ? null : world.getUID(),
                world == null ? null : world.getName(),
                location == null ? null : location.getBlockX(),
                location == null ? null : location.getBlockY(),
                location == null ? null : location.getBlockZ(),
                containerType,
                entityUuid,
                nestedPath,
                type.name().toLowerCase(Locale.ROOT),
                null,
                Instant.now().getEpochSecond(),
                Instant.now().getEpochSecond(),
                true
        );
    }

    private String nestedDescription(List<String> nestedPath, String tail) {
        if (nestedPath.isEmpty()) {
            return tail;
        }
        return String.join(", ", nestedPath) + ", " + tail;
    }

    private String tailDescription(String description) {
        int index = description.lastIndexOf(", ");
        return index >= 0 ? description.substring(index + 2) : description;
    }

    private String formatCoords(Location location) {
        return location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ() + " in " + location.getWorld().getName();
    }

    private String prettifyMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String resolveOwnerName(UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
        return owner.getName();
    }

    private void countInventoryScan() {
        if (performanceMonitor != null) {
            performanceMonitor.inventoryScanned();
        }
    }

    private void countContainerScan() {
        if (performanceMonitor != null) {
            performanceMonitor.containerScanned();
        }
    }
}
