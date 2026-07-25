package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DiaryPurgeServiceTest {

    @Test
    void competingRequestsReturnTheSingleActiveOperation() {
        Fixture fixture = fixture();
        PurgeOperation active = operation(PurgeDestination.OWNER);
        when(fixture.store.getActivePurgeOperation("diary")).thenReturn(active);
        when(fixture.store.getPurgeOperation(active.operationId())).thenReturn(active);
        when(fixture.store.getActivePurgeOperations()).thenReturn(List.of(active));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            assertSame(active, fixture.service.begin(record(), PurgeDestination.OWNER, null));
            assertSame(active, fixture.service.begin(record(), PurgeDestination.ADMIN, mock(Player.class)));
            assertSame(active, fixture.service.begin(record(), PurgeDestination.NONE, null));
        }
        verify(fixture.store, never()).addPurgeOperation(any());
    }

    @Test
    void intentionalDuplicateAlwaysCreatesASeparateTokenizedDelivery() {
        Fixture fixture = fixture();
        Player admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());
        OfflinePlayer offline = mock(OfflinePlayer.class);
        TrackedDiaryRecord record = record();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(offline);
            fixture.service.restoreDuplicate(record, admin);
        }
        verify(fixture.delivery).queue(eq(record.ownerUuid()), eq(com.p2wn.diary.data.DeliveryReason.RESTORE_DUPLICATE),
                any(ItemStack.class), any(UUID.class));
    }

    @Test
    void repairDiscoveryIncludesChestOnlyDuplicate() throws Exception {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);
        ItemStack diary = mock(ItemStack.class);
        org.bukkit.Material material = mock(org.bukkit.Material.class);
        when(material.isAir()).thenReturn(false);
        when(diary.getType()).thenReturn(material);
        when(diaryItem.isDiary(diary)).thenReturn(true);
        when(diaryItem.getDiaryId(diary)).thenReturn("diary");
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{diary});
        Container chest = mock(Container.class);
        when(chest.getInventory()).thenReturn(inventory);
        Chunk chunk = mock(Chunk.class);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[]{chest});
        when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);

        Method scan = DuplicateWatcher.class.getDeclaredMethod("scanChunkItems", Chunk.class, int.class, int.class);
        scan.setAccessible(true);
        Object result = scan.invoke(watcher, chunk, 0, 100);
        Method occurrences = result.getClass().getDeclaredMethod("occurrences");
        occurrences.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertEquals(1, ((List<?>) occurrences.invoke(result)).size());
    }

    @Test
    void fullInventoryKeepsTokenizedRestoreInThePersistentQueue() {
        Plugin plugin = mock(Plugin.class);
        DiaryStore store = mock(DiaryStore.class);
        FileConfiguration config = mock(FileConfiguration.class);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        ItemStack diary = mock(ItemStack.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(store.getPlayersWithPendingDeliveries()).thenReturn(Set.of(playerId));
        when(diary.clone()).thenReturn(diary);
        com.p2wn.diary.data.PendingDelivery pending = new com.p2wn.diary.data.PendingDelivery(
                com.p2wn.diary.data.DeliveryReason.RESTORE_ADMIN, diary, UUID.randomUUID());
        when(store.getPendingDeliveries(playerId, 2)).thenReturn(List.of(pending));
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(diary)).thenReturn(new java.util.HashMap<>(Map.of(0, diary)));
        DeliveryService service = new DeliveryService(plugin, store);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            service.tick();
        }
        verify(store, never()).removeFirstPendingDeliveries(any(), anyInt());
    }

    private Fixture fixture() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        DeliveryService delivery = mock(DeliveryService.class);
        DiaryAnalyticsStore analytics = mock(DiaryAnalyticsStore.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.diaryStore()).thenReturn(store);
        when(plugin.diaryItem()).thenReturn(diaryItem);
        when(plugin.deliveryService()).thenReturn(delivery);
        when(plugin.diaryAnalyticsStore()).thenReturn(analytics);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        return new Fixture(store, delivery, new DiaryPurgeService(plugin));
    }

    private TrackedDiaryRecord record() {
        ItemStack snapshot = mock(ItemStack.class);
        when(snapshot.clone()).thenReturn(snapshot);
        return new TrackedDiaryRecord("diary", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "owner", snapshot, null, List.of(), 1L);
    }

    private PurgeOperation operation(PurgeDestination destination) {
        ItemStack snapshot = mock(ItemStack.class);
        when(snapshot.clone()).thenReturn(snapshot);
        return new PurgeOperation(UUID.randomUUID(), "diary", record().ownerUuid(), null,
                destination, 1L, snapshot);
    }

    private record Fixture(DiaryStore store, DeliveryService delivery, DiaryPurgeService service) {
    }
}
