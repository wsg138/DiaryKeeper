package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryKeys;
import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DeliveryEntry;
import com.p2wn.diary.data.DeliveryLifecycle;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingDelivery;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.TrackedDiaryRecord;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminRecoveryServiceTest {

    @Test
    void forceGrantCancelsPurgeClearsOnlyOpenDeliveriesAndScrubsOldToken() {
        Fixture fixture = fixture();
        UUID operationId = UUID.randomUUID();
        PurgeOperation active = mock(PurgeOperation.class);
        when(active.operationId()).thenReturn(operationId);
        when(active.restorationOccurred()).thenReturn(false);
        when(fixture.store.getActivePurgeOperation("diary")).thenReturn(active);
        when(fixture.purge.cancel(eq(operationId), contains("emergency GUI recovery"))).thenReturn(true);

        ItemStack openItem = mockItem();
        ItemStack deliveredItem = mockItem();
        UUID openToken = UUID.randomUUID();
        UUID deliveredToken = UUID.randomUUID();
        PendingDelivery open = new PendingDelivery(
                DeliveryReason.RESTORE_DUPLICATE, openItem, openToken, DeliveryLifecycle.QUEUED);
        PendingDelivery delivered = new PendingDelivery(
                DeliveryReason.RESTORE_DUPLICATE, deliveredItem, deliveredToken,
                DeliveryLifecycle.DELIVERED, 1L, 0L, 2L, null);
        when(fixture.store.getDeliveryEntries()).thenReturn(List.of(
                new DeliveryEntry(fixture.ownerId, open),
                new DeliveryEntry(fixture.ownerId, delivered)));
        when(fixture.diaryService.getDiaryId(openItem)).thenReturn("diary");
        when(fixture.diaryService.getDiaryId(deliveredItem)).thenReturn("diary");
        when(fixture.store.cancelDelivery(openToken)).thenReturn(true);
        when(fixture.inventory.addItem(fixture.snapshot)).thenReturn(new HashMap<>());

        AdminRecoveryService.ForceGrantResult result = fixture.service.forceGiveToAdmin(
                fixture.record, fixture.admin);

        assertEquals(AdminRecoveryService.ForceGrantStatus.DELIVERED_NOW, result.status());
        assertEquals(1, result.staleDeliveriesRemoved());
        assertEquals(operationId, result.cancelledPurgeId());
        verify(fixture.store).cancelDelivery(openToken);
        verify(fixture.store, never()).cancelDelivery(deliveredToken);
        verify(fixture.store).markAllLocationsInactive("diary");
        verify(fixture.store).flushNowBlocking("emergency recovery cleanup");
        verify(fixture.store).flushNowBlocking("emergency recovery direct grant");
        verify(fixture.pdc).remove(fixture.deliveryKey);
        verify(fixture.tracker).trackPlayerInventory(fixture.admin);
        verify(fixture.duplicateWatcher).refreshPlayerSnapshot(fixture.admin);
        verify(fixture.diaryService).refreshOwnedDiaries(fixture.admin);
        verify(fixture.delivery, never()).queue(any(), any(), any(), any());
    }

    @Test
    void fullInventoryQueuesExactlyOneCleanReplacement() {
        Fixture fixture = fixture();
        when(fixture.store.getDeliveryEntries()).thenReturn(List.of());
        when(fixture.inventory.addItem(fixture.snapshot))
                .thenReturn(new HashMap<>(Map.of(0, fixture.snapshot)));

        AdminRecoveryService.ForceGrantResult result = fixture.service.forceGiveToAdmin(
                fixture.record, fixture.admin);

        assertEquals(AdminRecoveryService.ForceGrantStatus.QUEUED_FULL_INVENTORY, result.status());
        verify(fixture.store).markAllLocationsInactive("diary");
        verify(fixture.pdc).remove(fixture.deliveryKey);
        verify(fixture.delivery, times(1)).queue(
                eq(fixture.adminId), eq(DeliveryReason.RESTORE_ADMIN),
                eq(fixture.snapshot), any(UUID.class));
        verify(fixture.store).flushNowBlocking("emergency recovery queued replacement");
        verify(fixture.tracker, never()).trackPlayerInventory(any());
        verify(fixture.duplicateWatcher, never()).refreshPlayerSnapshot(any());
        verify(fixture.diaryService, never()).refreshOwnedDiaries(any());
    }

    private Fixture fixture() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        DiaryPurgeService purge = mock(DiaryPurgeService.class);
        DiaryService diaryService = mock(DiaryService.class);
        DeliveryService delivery = mock(DeliveryService.class);
        DiaryTrackerService tracker = mock(DiaryTrackerService.class);
        DuplicateWatcher duplicateWatcher = mock(DuplicateWatcher.class);
        DiaryKeys keys = mock(DiaryKeys.class);
        NamespacedKey deliveryKey = mock(NamespacedKey.class);
        Player admin = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack snapshot = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        UUID adminId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        when(plugin.diaryStore()).thenReturn(store);
        when(plugin.diaryPurgeService()).thenReturn(purge);
        when(plugin.diaryService()).thenReturn(diaryService);
        when(plugin.deliveryService()).thenReturn(delivery);
        when(plugin.diaryTrackerService()).thenReturn(tracker);
        when(plugin.duplicateWatcher()).thenReturn(duplicateWatcher);
        when(plugin.diaryKeys()).thenReturn(keys);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(keys.deliveryToken()).thenReturn(deliveryKey);
        when(admin.getName()).thenReturn("admin");
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getInventory()).thenReturn(inventory);
        when(snapshot.clone()).thenReturn(snapshot);
        when(snapshot.getType()).thenReturn(Material.WRITABLE_BOOK);
        when(snapshot.hasItemMeta()).thenReturn(true);
        when(snapshot.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);

        TrackedDiaryRecord record = new TrackedDiaryRecord(
                "diary", ownerId, "owner", snapshot, null, List.of(), 1L);
        return new Fixture(new AdminRecoveryService(plugin), store, purge, diaryService,
                delivery, tracker, duplicateWatcher, admin, inventory, snapshot, pdc,
                deliveryKey, adminId, ownerId, record);
    }

    private ItemStack mockItem() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(Material.WRITABLE_BOOK);
        return item;
    }

    private record Fixture(
            AdminRecoveryService service,
            DiaryStore store,
            DiaryPurgeService purge,
            DiaryService diaryService,
            DeliveryService delivery,
            DiaryTrackerService tracker,
            DuplicateWatcher duplicateWatcher,
            Player admin,
            PlayerInventory inventory,
            ItemStack snapshot,
            PersistentDataContainer pdc,
            NamespacedKey deliveryKey,
            UUID adminId,
            UUID ownerId,
            TrackedDiaryRecord record
    ) { }
}
