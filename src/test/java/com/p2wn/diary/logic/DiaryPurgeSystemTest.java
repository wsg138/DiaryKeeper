package com.p2wn.diary.logic;

import com.p2wn.diary.data.DiaryLocationRecord;
import com.p2wn.diary.data.DiaryLocationType;
import com.p2wn.diary.data.PurgeChunkTarget;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.PurgeState;
import com.p2wn.diary.data.TrackedDiaryRecord;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class DiaryPurgeSystemTest {

    private static final String TARGET = "diary-target";

    @Test
    void removesTwoCopiesInOneOnlinePlayerInventory() {
        ItemStack first = diary(TARGET);
        ItemStack second = diary(TARGET);
        Inventory inventory = inventory(first, second);
        assertEquals(2, purger(first, second).purgeInventory(inventory, TARGET));
        verify(inventory).setItem(0, null);
        verify(inventory).setItem(1, null);
    }

    @Test
    void removesInventoryAndEnderChestCopies() {
        ItemStack inventoryCopy = diary(TARGET);
        ItemStack enderCopy = diary(TARGET);
        assertEquals(1, purger(inventoryCopy, enderCopy).purgeInventory(inventory(inventoryCopy), TARGET));
        assertEquals(1, purger(inventoryCopy, enderCopy).purgeInventory(inventory(enderCopy), TARGET));
    }

    @Test
    void removesCopyInsideShulker() {
        ItemStack nested = diary(TARGET);
        ItemStack shulker = shulker(nested);
        assertEquals(1, purger(nested).purge(shulker, TARGET, 0).removed());
    }

    @Test
    void removesCopyInsideBundle() {
        ItemStack nested = diary(TARGET);
        ItemStack bundle = bundle(nested);
        assertEquals(1, purger(nested).purge(bundle, TARGET, 0).removed());
    }

    @Test
    void removesMultipleCopiesInNestedSupportedContainers() {
        ItemStack first = diary(TARGET);
        ItemStack second = diary(TARGET);
        ItemStack nested = bundle(shulker(first, second));
        assertEquals(2, purger(first, second).purge(nested, TARGET, 0).removed());
    }

    @Test
    void removesCopyInLoadedChestInventory() {
        ItemStack copy = diary(TARGET);
        assertEquals(1, purger(copy).purgeInventory(inventory(copy), TARGET));
    }

    @Test
    void removesGroundItemStack() {
        ItemStack copy = diary(TARGET);
        DiaryItemPurger.Result result = purger(copy).purge(copy, TARGET, 0);
        assertNull(result.item());
        assertEquals(1, result.removed());
    }

    @Test
    void recordsOfflinePlayerPendingPurge() {
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        UUID offline = UUID.randomUUID();
        operation.pendingPlayers().add(offline);
        assertTrue(operation.pendingPlayers().contains(offline));
    }

    @Test
    void recordsUnloadedKnownChunkProgress() {
        PurgeChunkTarget target = new PurgeChunkTarget(UUID.randomUUID(), "world", 4, -2, 64, 70, -17);
        target.fail("timeout");
        assertFalse(target.completed());
        assertEquals(1, target.attempts());
        assertEquals("timeout", target.error());
    }

    @Test
    void countsCopiesRemovedFromDeliveryQueue() {
        PurgeOperation operation = operation(PurgeDestination.NONE);
        operation.setPendingDeliveriesRemoved(2);
        operation.addRemoved("delivery_queue", 2);
        assertEquals(2, operation.pendingDeliveriesRemoved());
        assertEquals(2, operation.totalRemoved());
    }

    @Test
    void recordsAdminRestoreWithAvailableSpace() {
        PurgeOperation operation = operation(PurgeDestination.ADMIN);
        UUID admin = operation.adminUuid();
        operation.setRestorationOccurred(true);
        operation.setReplacementHolder(admin);
        assertEquals(admin, operation.replacementHolder());
    }

    @Test
    void adminRestoreWithFullInventoryCanRemainQueuedIdempotently() {
        PurgeOperation operation = operation(PurgeDestination.ADMIN);
        assertFalse(operation.restorationOccurred());
        operation.setState(PurgeState.READY_TO_RESTORE);
        assertEquals(PurgeState.READY_TO_RESTORE, operation.state());
    }

    @Test
    void ownerOfflineDuringRestoreRemainsPending() {
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        operation.pendingPlayers().add(operation.ownerUuid());
        operation.setState(PurgeState.WAITING_FOR_OFFLINE_PLAYERS);
        assertFalse(operation.pendingPlayers().isEmpty());
    }

    @Test
    void serverRestartStateCanBeReconstructed() {
        PurgeOperation original = operation(PurgeDestination.OWNER);
        PurgeOperation loaded = new PurgeOperation(original.operationId(), original.diaryId(),
                original.ownerUuid(), original.adminUuid(), original.destination(),
                original.startedAt(), original.snapshot());
        loaded.setState(PurgeState.WAITING_FOR_OFFLINE_PLAYERS);
        assertEquals(original.operationId(), loaded.operationId());
        assertEquals(PurgeState.WAITING_FOR_OFFLINE_PLAYERS, loaded.state());
    }

    @Test
    void rerunningSameOperationCanObserveRestorationFlag() {
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        operation.setRestorationOccurred(true);
        operation.setState(PurgeState.COMPLETED);
        assertTrue(operation.restorationOccurred());
        assertTrue(operation.terminal());
    }

    @Test
    void chunkLoadFailureIsRetainedForRetryAndAudit() {
        PurgeChunkTarget target = new PurgeChunkTarget(null, "missing", 0, 0, null, null, null);
        target.fail("world unavailable");
        assertEquals(1, target.attempts());
        assertNotNull(target.error());
    }

    @Test
    void playerJoiningActivePurgeCanBeMarkedComplete() {
        PurgeOperation operation = operation(PurgeDestination.NONE);
        UUID player = UUID.randomUUID();
        operation.pendingPlayers().add(player);
        assertTrue(operation.pendingPlayers().remove(player));
        assertTrue(operation.pendingPlayers().isEmpty());
    }

    @Test
    void duplicateAfterCompletionIsInsideWatchWindow() {
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        operation.setState(PurgeState.COMPLETED);
        operation.setWatchUntil(Long.MAX_VALUE);
        assertTrue(operation.watchUntil() > operation.startedAt());
    }

    @Test
    void consoleCannotRepresentAdminDestinationHolder() {
        PurgeOperation operation = new PurgeOperation(UUID.randomUUID(), TARGET, UUID.randomUUID(),
                null, PurgeDestination.ADMIN, 1L, mock(ItemStack.class));
        assertNull(operation.adminUuid());
    }

    @Test
    void purgeOnlyModeHasNoRestoreDestination() {
        assertEquals(PurgeDestination.NONE, operation(PurgeDestination.NONE).destination());
    }

    @Test
    void partialPurgeDoesNotImplyRestoration() {
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        operation.errors().add("chunk timeout");
        operation.setState(PurgeState.PARTIAL);
        assertFalse(operation.restorationOccurred());
    }

    @Test
    void restoreSnapshotIsClonedAndPreserved() {
        ItemStack snapshot = mock(ItemStack.class);
        ItemStack clone = mock(ItemStack.class);
        when(snapshot.clone()).thenReturn(clone);
        when(clone.clone()).thenReturn(clone);
        PurgeOperation operation = new PurgeOperation(UUID.randomUUID(), TARGET, UUID.randomUUID(),
                null, PurgeDestination.OWNER, 1L, snapshot);
        assertSame(clone, operation.snapshot());
        verify(snapshot).clone();
    }

    @Test
    void retainsMultipleOldTrackedLocationsForOneDiary() {
        DiaryLocationRecord first = location(1, true);
        DiaryLocationRecord second = location(2, true);
        TrackedDiaryRecord record = new TrackedDiaryRecord(TARGET, UUID.randomUUID(), "owner",
                mock(ItemStack.class), second, List.of(first, second), 5L);
        assertEquals(2, record.locations().size());
        assertEquals(2, record.activeLocationCount());
    }

    @Test
    void staleLocationsAreNotCountedAsConfirmedCopies() {
        DiaryLocationRecord active = location(1, true);
        DiaryLocationRecord stale = location(2, false);
        TrackedDiaryRecord record = new TrackedDiaryRecord(TARGET, UUID.randomUUID(), "owner",
                mock(ItemStack.class), active, List.of(active, stale), 5L);
        assertEquals(1, record.activeLocationCount());
        assertEquals(2, record.locations().size());
    }

    private DiaryItemPurger purger(ItemStack... diaries) {
        Set<ItemStack> matches = Set.of(diaries);
        return new DiaryItemPurger(matches::contains, ignored -> TARGET, 4);
    }

    private ItemStack diary(String id) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getAmount()).thenReturn(1);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    private Inventory inventory(ItemStack... contents) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(contents);
        return inventory;
    }

    private ItemStack bundle(ItemStack... contents) {
        ItemStack original = mock(ItemStack.class);
        ItemStack clone = mock(ItemStack.class);
        BundleMeta originalMeta = mock(BundleMeta.class);
        BundleMeta cloneMeta = mock(BundleMeta.class);
        when(original.getType()).thenReturn(Material.BUNDLE);
        when(original.hasItemMeta()).thenReturn(true);
        when(original.getItemMeta()).thenReturn(originalMeta);
        when(originalMeta.getItems()).thenReturn(List.of(contents));
        when(original.clone()).thenReturn(clone);
        when(clone.getItemMeta()).thenReturn(cloneMeta);
        return original;
    }

    private ItemStack shulker(ItemStack... contents) {
        ItemStack original = mock(ItemStack.class);
        ItemStack clone = mock(ItemStack.class);
        BlockStateMeta originalMeta = mock(BlockStateMeta.class);
        BlockStateMeta cloneMeta = mock(BlockStateMeta.class);
        ShulkerBox state = mock(ShulkerBox.class);
        Inventory inventory = inventory(contents);
        when(original.getType()).thenReturn(Material.SHULKER_BOX);
        when(original.hasItemMeta()).thenReturn(true);
        when(original.getItemMeta()).thenReturn(originalMeta);
        when(originalMeta.getBlockState()).thenReturn(state);
        when(state.getInventory()).thenReturn(inventory);
        when(original.clone()).thenReturn(clone);
        when(clone.getItemMeta()).thenReturn(cloneMeta);
        return original;
    }

    private PurgeOperation operation(PurgeDestination destination) {
        ItemStack snapshot = mock(ItemStack.class);
        when(snapshot.clone()).thenReturn(snapshot);
        return new PurgeOperation(UUID.randomUUID(), TARGET, UUID.randomUUID(), UUID.randomUUID(),
                destination, 1L, snapshot);
    }

    private DiaryLocationRecord location(int slot, boolean active) {
        return new DiaryLocationRecord(DiaryLocationType.PLAYER_INVENTORY, "inventory",
                UUID.randomUUID(), "holder", null, null, null, null, null,
                null, null, List.of(), "inventory", slot, 1L, 2L, active);
    }
}
