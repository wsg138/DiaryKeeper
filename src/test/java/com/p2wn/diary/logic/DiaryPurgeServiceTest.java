package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.DiaryKeys;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeChunkTarget;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
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
    void completedChunkTargetIsResetAndRequeuedWhenChunkLoadsAgain() throws Exception {
        Fixture fixture = fixture();
        UUID worldId = UUID.randomUUID();
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        PurgeChunkTarget target = new PurgeChunkTarget(worldId, "world", 3, -4, null, null, null);
        operation.addChunkTarget(target);
        target.complete();
        when(fixture.store.getActivePurgeOperations()).thenReturn(List.of(operation));
        Chunk chunk = mock(Chunk.class);
        World world = mock(World.class);
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(3);
        when(chunk.getZ()).thenReturn(-4);
        when(world.getUID()).thenReturn(worldId);
        when(world.getName()).thenReturn("world");
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(org.bukkit.scheduler.BukkitTask.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            fixture.service.onChunkLoad(chunk);
        }

        assertFalse(target.completed());
        assertTrue(operation.verificationRequired());
        assertEquals(1, operation.chunkTargets().size());
        assertEquals(1, fixture.service.queuedChunkCount());
    }

    @Test
    void asyncChunkLoadDoesNotAddTicketBeforeFutureCompletes() throws Exception {
        Fixture fixture = fixture();
        PurgeOperation operation = operation(PurgeDestination.OWNER);
        UUID worldId = UUID.randomUUID();
        PurgeChunkTarget target = new PurgeChunkTarget(worldId, "world", 1, 1, null, null, null);
        World world = mock(World.class);
        CompletableFuture<Chunk> future = new CompletableFuture<>();
        when(world.getChunkAtAsync(1, 1, true)).thenReturn(future);
        fixture.service.requestAsyncChunkLoad(operation, target, world);
        verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
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
            fixture.service.restoreDuplicate(record, admin);
        }
        org.mockito.ArgumentCaptor<UUID> tokens = org.mockito.ArgumentCaptor.forClass(UUID.class);
        verify(fixture.delivery, times(2)).queue(eq(record.ownerUuid()), eq(com.p2wn.diary.data.DeliveryReason.RESTORE_DUPLICATE),
                any(ItemStack.class), tokens.capture());
        assertNotEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1));
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
        when(chest.getWorld()).thenReturn(world);
        when(chest.getX()).thenReturn(0);
        when(chest.getY()).thenReturn(64);
        when(chest.getZ()).thenReturn(0);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[]{chest});
        when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);

        DuplicateWatcher.ChunkScanResult result = watcher.scanChunkItems(chunk, 0, 100);
        assertEquals(1, result.occurrences().size());
    }

    @Test
    void chunkContainerSnapshotsAreReplacedWithoutTouchingAnotherChunk() throws Exception {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);
        Chunk first = chunkWithDiary(UUID.randomUUID(), 1, 2, diaryItem);
        Chunk second = chunkWithDiary(UUID.randomUUID(), 3, 4, diaryItem);
        watcher.scanChunkItems(first, 0, 100);
        watcher.scanChunkItems(second, 0, 100);
        when(first.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[0]);
        watcher.scanChunkItems(first, 0, 100);

        Set<String> keys = watcher.blockContainerSnapshotKeys();
        assertEquals(1, keys.size());
        assertEquals(1, keys.size());
    }

    @Test
    void diaryEnteringPreviouslyScannedChunkReplacesItsEmptySnapshot() throws Exception {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);
        Chunk chunk = chunkWithDiary(UUID.randomUUID(), 5, 6, diaryItem);
        when(chunk.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[0]);
        watcher.scanChunkItems(chunk, 0, 100);
        assertTrue(watcher.blockContainerSnapshotKeys().isEmpty());

        Chunk populated = chunkWithDiary(chunk.getWorld().getUID(), 5, 6, diaryItem);
        watcher.scanChunkItems(populated, 0, 100);
        assertEquals(1, watcher.blockContainerSnapshotKeys().size());
    }

    @Test
    void holderBasedDoubleChestRefreshesEachHalfWithoutCombinedSnapshot() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);

        ItemStack diary = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(diary.getType()).thenReturn(material);
        when(diaryItem.isDiary(diary)).thenReturn(true);
        when(diaryItem.getDiaryId(diary)).thenReturn("diary");

        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Chest leftChest = chestHalf(world, 10);
        Chest rightChest = chestHalf(world, 11);
        Inventory left = mock(Inventory.class);
        Inventory right = mock(Inventory.class);
        when(left.getHolder()).thenReturn(leftChest);
        when(right.getHolder()).thenReturn(rightChest);
        when(leftChest.getBlockInventory()).thenReturn(left);
        when(rightChest.getBlockInventory()).thenReturn(right);
        when(left.getContents()).thenReturn(new ItemStack[]{diary});
        when(right.getContents()).thenReturn(new ItemStack[0]);

        DoubleChest doubleChest = mock(DoubleChest.class);
        when(doubleChest.getLeftSide()).thenReturn(leftChest);
        when(doubleChest.getRightSide()).thenReturn(rightChest);
        Inventory combined = mock(Inventory.class);
        when(combined.getHolder()).thenReturn(doubleChest);

        watcher.refreshContainerSnapshot(combined);
        assertEquals(1, watcher.blockContainerSnapshotKeys().size());
        assertEquals(1, watcher.authoritativeOccurrenceCount("diary"));

        when(right.getContents()).thenReturn(new ItemStack[]{diary});
        watcher.refreshContainerSnapshot(combined);
        assertEquals(2, watcher.blockContainerSnapshotKeys().size());
        assertEquals(2, watcher.authoritativeOccurrenceCount("diary"));

        when(left.getContents()).thenReturn(new ItemStack[0]);
        watcher.refreshContainerSnapshot(combined);
        assertEquals(1, watcher.blockContainerSnapshotKeys().size());
        assertEquals(1, watcher.authoritativeOccurrenceCount("diary"));
    }

    @Test
    void doubleChestInventoryCountsPhysicalStacksWithoutDuplicatingTheCombinedView() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        when(yaml.getBoolean("duplicates.warn-on-container-open", true)).thenReturn(true);
        when(yaml.getBoolean("duplicates.staff-notify", true)).thenReturn(false);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.diaryPurgeService()).thenReturn(mock(DiaryPurgeService.class));
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);

        ItemStack first = mock(ItemStack.class);
        ItemStack second = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(first.getType()).thenReturn(material);
        when(second.getType()).thenReturn(material);
        when(diaryItem.isDiary(first)).thenReturn(true);
        when(diaryItem.isDiary(second)).thenReturn(true);
        when(diaryItem.getDiaryId(first)).thenReturn("diary");
        when(diaryItem.getDiaryId(second)).thenReturn("diary");

        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Chest leftChest = chestHalf(world, 20);
        Chest rightChest = chestHalf(world, 21);
        Inventory left = mock(Inventory.class);
        Inventory right = mock(Inventory.class);
        when(left.getHolder()).thenReturn(leftChest);
        when(right.getHolder()).thenReturn(rightChest);
        when(leftChest.getBlockInventory()).thenReturn(left);
        when(rightChest.getBlockInventory()).thenReturn(right);
        when(left.getContents()).thenReturn(new ItemStack[]{first});
        when(right.getContents()).thenReturn(new ItemStack[0]);
        DoubleChestInventory combined = mock(DoubleChestInventory.class);
        when(combined.getLeftSide()).thenReturn(left);
        when(combined.getRightSide()).thenReturn(right);

        watcher.refreshContainerSnapshot(combined);
        assertEquals(1, watcher.authoritativeOccurrenceCount("diary"));

        PluginManager pluginManager = mock(PluginManager.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            watcher.onInventoryOpen(null, combined);
            verify(pluginManager, never()).callEvent(any(com.p2wn.diary.events.DiaryDuplicateWarningEvent.class));

            when(right.getContents()).thenReturn(new ItemStack[]{second});
            watcher.onInventoryOpen(null, combined);
            assertEquals(2, watcher.authoritativeOccurrenceCount("diary"));
            org.mockito.ArgumentCaptor<com.p2wn.diary.events.DiaryDuplicateWarningEvent> event =
                    org.mockito.ArgumentCaptor.forClass(com.p2wn.diary.events.DiaryDuplicateWarningEvent.class);
            verify(pluginManager).callEvent(event.capture());
            assertEquals(2, event.getValue().getDuplicateCount());
        }
    }

    @Test
    void negativeCoordinateChunkUnloadRemovesContainerSnapshot() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);

        ItemStack diary = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(diary.getType()).thenReturn(material);
        when(diaryItem.isDiary(diary)).thenReturn(true);
        when(diaryItem.getDiaryId(diary)).thenReturn("diary");
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{diary});
        Container chest = mock(Container.class);
        when(chest.getInventory()).thenReturn(inventory);
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(chest.getWorld()).thenReturn(world);
        when(chest.getX()).thenReturn(-1);
        when(chest.getY()).thenReturn(64);
        when(chest.getZ()).thenReturn(-1);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(-1);
        when(chunk.getZ()).thenReturn(-1);
        when(chunk.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[]{chest});
        when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);

        watcher.scanChunkItems(chunk, 0, 100);
        assertEquals(1, watcher.blockContainerSnapshotKeys().size());
        watcher.onChunkUnload(chunk);
        assertTrue(watcher.blockContainerSnapshotKeys().isEmpty());
    }

    @Test
    void startupSweepClearsContainerSnapshotsBeforeRescan() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        when(config.cfg()).thenReturn(yaml);
        when(yaml.getBoolean("duplicate-scan.enabled", true)).thenReturn(false);
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);
        watcher.scanChunkItems(chunkWithDiary(UUID.randomUUID(), 2, 3, diaryItem), 0, 100);
        assertEquals(1, watcher.blockContainerSnapshotKeys().size());

        watcher.sweepStartup();

        assertTrue(watcher.blockContainerSnapshotKeys().isEmpty());
    }

    @Test
    void globalScanDoesNotWarnWhileContainerSnapshotsArePartial() throws Exception {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        DiaryItem diaryItem = mock(DiaryItem.class);
        Server server = mock(Server.class);
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(config.cfg()).thenReturn(yaml);
        when(yaml.getBoolean("duplicate-scan.enabled", true)).thenReturn(true);
        when(yaml.getBoolean("duplicates.warn-on-chunk-load", true)).thenReturn(true);
        when(yaml.getBoolean("duplicates.staff-notify", true)).thenReturn(false);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.diaryPurgeService()).thenReturn(mock(DiaryPurgeService.class));
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, diaryItem);

        ItemStack diary = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(diary.getType()).thenReturn(material);
        when(diaryItem.isDiary(diary)).thenReturn(true);
        when(diaryItem.getDiaryId(diary)).thenReturn("diary");

        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        Chunk staleChunk = chunkWithContents(world, 1, 0, new ItemStack[]{diary});
        Chunk currentChunk = chunkWithContents(world, 2, 0, new ItemStack[]{diary});
        watcher.scanChunkItems(staleChunk, 0, 100);
        Container staleContainer = (Container) staleChunk.getTileEntities()[0];
        when(staleContainer.getInventory().getContents()).thenReturn(new ItemStack[0]);
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{currentChunk, staleChunk});
        when(world.isChunkLoaded(1, 0)).thenReturn(true);
        when(world.isChunkLoaded(2, 0)).thenReturn(true);
        when(world.getChunkAt(1, 0)).thenReturn(staleChunk);
        when(world.getChunkAt(2, 0)).thenReturn(currentChunk);
        PluginManager pluginManager = mock(PluginManager.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world));
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            watcher.queueGlobalScan();
            var scanTick = DuplicateWatcher.class.getDeclaredMethod("scanTick");
            scanTick.setAccessible(true);
            scanTick.invoke(watcher);
            verify(pluginManager, never()).callEvent(any(com.p2wn.diary.events.DiaryDuplicateWarningEvent.class));
            scanTick.invoke(watcher);
            verify(pluginManager, never()).callEvent(any(com.p2wn.diary.events.DiaryDuplicateWarningEvent.class));

            when(staleContainer.getInventory().getContents()).thenReturn(new ItemStack[]{diary});
            watcher.queueGlobalScan();
            scanTick.invoke(watcher);
            verify(pluginManager, never()).callEvent(any(com.p2wn.diary.events.DiaryDuplicateWarningEvent.class));
            scanTick.invoke(watcher);
            org.mockito.ArgumentCaptor<com.p2wn.diary.events.DiaryDuplicateWarningEvent> event =
                    org.mockito.ArgumentCaptor.forClass(com.p2wn.diary.events.DiaryDuplicateWarningEvent.class);
            verify(pluginManager).callEvent(event.capture());
            assertEquals(2, event.getValue().getDuplicateCount());
        }
    }

    @Test
    void globalScanRequestedDuringSweepRunsAfterCurrentGeneration() throws Exception {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        ConfigManager config = mock(ConfigManager.class);
        FileConfiguration yaml = mock(FileConfiguration.class);
        Server server = mock(Server.class);
        org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        BukkitTask scanTask = mock(BukkitTask.class);
        when(config.cfg()).thenReturn(yaml);
        when(yaml.getBoolean("duplicate-scan.enabled", true)).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(any(), any(Runnable.class), anyLong(), anyLong())).thenReturn(scanTask);
        DuplicateWatcher watcher = new DuplicateWatcher(plugin, config, mock(DiaryItem.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            bukkit.when(Bukkit::getWorlds).thenReturn(List.of());
            watcher.queueGlobalScan();
            watcher.queueGlobalScan();
            var scanTick = DuplicateWatcher.class.getDeclaredMethod("scanTick");
            scanTick.setAccessible(true);
            scanTick.invoke(watcher);
            verify(scanTask, never()).cancel();
            scanTick.invoke(watcher);
            verify(scanTask).cancel();
        }
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
        when(plugin.isEnabled()).thenReturn(true);
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(store.getPlayersWithPendingDeliveries()).thenReturn(Set.of(playerId));
        when(diary.clone()).thenReturn(diary);
        UUID deliveryId = UUID.randomUUID();
        when(store.claimDelivery(playerId, deliveryId)).thenReturn(true);
        when(store.flushDurably()).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));
        when(store.releaseDeliveryClaimDurably(playerId, deliveryId))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        com.p2wn.diary.data.PendingDelivery pending = new com.p2wn.diary.data.PendingDelivery(
                com.p2wn.diary.data.DeliveryReason.RESTORE_ADMIN, diary, deliveryId);
        com.p2wn.diary.data.PendingDelivery claimed = new com.p2wn.diary.data.PendingDelivery(
                com.p2wn.diary.data.DeliveryReason.RESTORE_ADMIN, diary, deliveryId,
                com.p2wn.diary.data.DeliveryLifecycle.CLAIMED);
        com.p2wn.diary.data.DeliveryEntry entry = new com.p2wn.diary.data.DeliveryEntry(playerId, claimed);
        when(store.getDeliveryEntry(deliveryId)).thenReturn(entry);
        when(store.getPendingDeliveries(playerId, 2)).thenReturn(List.of(pending));
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(diary)).thenReturn(new java.util.HashMap<>(Map.of(0, diary)));
        DeliveryService service = new DeliveryService(plugin, store);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            org.bukkit.scheduler.BukkitScheduler scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run(); return mock(org.bukkit.scheduler.BukkitTask.class);
            });
            service.tick();
        }
        verify(inventory).addItem(diary);
        verify(store).releaseDeliveryClaimDurably(playerId, deliveryId);
    }

    @Test
    void deliveredTokenInEnderChestPreventsRestartRedelivery() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        FileConfiguration config = mock(FileConfiguration.class);
        DiaryKeys keys = mock(DiaryKeys.class);
        NamespacedKey deliveryKey = mock(NamespacedKey.class);
        UUID playerId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        Player player = mock(Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        Inventory enderChest = mock(Inventory.class);
        ItemStack delivered = tokenizedItem(token, keys, deliveryKey);
        ItemStack queued = mock(ItemStack.class);
        when(queued.clone()).thenReturn(queued);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(plugin.diaryKeys()).thenReturn(keys);
        when(keys.deliveryToken()).thenReturn(deliveryKey);
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("player");
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(enderChest.getContents()).thenReturn(new ItemStack[]{delivered});
        when(store.getPlayersWithPendingDeliveries()).thenReturn(Set.of(playerId));
        com.p2wn.diary.data.PendingDelivery pending = new com.p2wn.diary.data.PendingDelivery(
                com.p2wn.diary.data.DeliveryReason.RESTORE_OWNER, queued, token);
        when(store.getPendingDeliveries(playerId, 2)).thenReturn(List.of(pending));
        DeliveryService service = new DeliveryService(plugin, store);
        service.setDiaryService(mock(DiaryService.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            service.tick();
        }

        verify(store).confirmDeliveryPresent(playerId, token);
        verify(inventory, never()).addItem(any());
    }

    @Test
    void deliveredTokenNestedInBundleOrShulkerIsDetected() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        FileConfiguration config = mock(FileConfiguration.class);
        DiaryKeys keys = mock(DiaryKeys.class);
        NamespacedKey deliveryKey = mock(NamespacedKey.class);
        UUID token = UUID.randomUUID();
        Player player = mock(Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        Inventory enderChest = mock(Inventory.class);
        ItemStack delivered = tokenizedItem(token, keys, deliveryKey);
        ItemStack bundle = mock(ItemStack.class);
        BundleMeta bundleMeta = mock(BundleMeta.class);
        ItemStack shulker = mock(ItemStack.class);
        BlockStateMeta shulkerMeta = mock(BlockStateMeta.class);
        ShulkerBox shulkerBox = mock(ShulkerBox.class);
        Inventory shulkerInventory = mock(Inventory.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(plugin.diaryKeys()).thenReturn(keys);
        when(keys.deliveryToken()).thenReturn(deliveryKey);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        when(inventory.getContents()).thenReturn(new ItemStack[]{bundle});
        when(enderChest.getContents()).thenReturn(new ItemStack[]{shulker});
        when(bundle.getType()).thenReturn(Material.BUNDLE);
        when(bundle.hasItemMeta()).thenReturn(true);
        when(bundle.getItemMeta()).thenReturn(bundleMeta);
        when(bundleMeta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(bundleMeta.getItems()).thenReturn(List.of());
        when(shulker.getType()).thenReturn(Material.SHULKER_BOX);
        when(shulker.hasItemMeta()).thenReturn(true);
        when(shulker.getItemMeta()).thenReturn(shulkerMeta);
        when(shulkerMeta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(shulkerMeta.getBlockState()).thenReturn(shulkerBox);
        when(shulkerBox.getInventory()).thenReturn(shulkerInventory);
        when(shulkerInventory.getContents()).thenReturn(new ItemStack[]{delivered});

        DeliveryService service = new DeliveryService(plugin, store);
        assertTrue(service.hasDeliveredToken(player, token));
        when(enderChest.getContents()).thenReturn(new ItemStack[0]);
        when(bundleMeta.getItems()).thenReturn(List.of(delivered));
        assertTrue(service.hasDeliveredToken(player, token));
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

    private Chunk chunkWithDiary(UUID worldId, int x, int z, DiaryItem diaryItem) {
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
        when(world.getUID()).thenReturn(worldId);
        when(chest.getWorld()).thenReturn(world);
        when(chest.getX()).thenReturn(x * 16);
        when(chest.getY()).thenReturn(64);
        when(chest.getZ()).thenReturn(z * 16);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        when(chunk.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[]{chest});
        when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);
        return chunk;
    }

    private Chunk chunkWithContents(World world, int x, int z, ItemStack[] contents) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(contents);
        Container container = mock(Container.class);
        when(container.getInventory()).thenReturn(inventory);
        when(container.getWorld()).thenReturn(world);
        when(container.getX()).thenReturn(x * 16);
        when(container.getY()).thenReturn(64);
        when(container.getZ()).thenReturn(z * 16);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(x);
        when(chunk.getZ()).thenReturn(z);
        when(chunk.getTileEntities()).thenReturn(new org.bukkit.block.BlockState[]{container});
        when(chunk.getEntities()).thenReturn(new org.bukkit.entity.Entity[0]);
        return chunk;
    }

    private Chest chestHalf(World world, int x) {
        Chest chest = mock(Chest.class);
        when(chest.getWorld()).thenReturn(world);
        when(chest.getX()).thenReturn(x);
        when(chest.getY()).thenReturn(64);
        when(chest.getZ()).thenReturn(0);
        return chest;
    }

    private ItemStack tokenizedItem(UUID token, DiaryKeys keys, NamespacedKey deliveryKey) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.WRITABLE_BOOK);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(deliveryKey, org.bukkit.persistence.PersistentDataType.STRING)).thenReturn(token.toString());
        return item;
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
