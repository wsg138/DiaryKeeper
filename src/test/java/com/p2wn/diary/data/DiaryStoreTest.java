package com.p2wn.diary.data;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiaryStoreTest {

    @TempDir
    Path temp;

    @Test
    void partialChunkProgressAndEveryOperationMutationArePersisted() {
        DiaryStore store = store();
        PurgeOperation operation = operation(1L);
        PurgeChunkTarget chunk = new PurgeChunkTarget(null, "world", 2, 3, null, null, null);
        operation.addChunkTarget(chunk);
        store.addPurgeOperation(operation);
        store.flushDurably().join();

        UUID player = UUID.randomUUID();
        operation.setState(PurgeState.PROCESSING_KNOWN_UNLOADED_CHUNKS);
        operation.addPendingPlayer(player);
        operation.completePlayer(player);
        operation.addRemoved("chunk", 2);
        operation.addError("retry");
        chunk.fail("timeout");
        store.flushDurably().join();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                temp.resolve("diaries.yml").toFile());
        String base = "purgeOperations." + operation.operationId();
        assertEquals("PROCESSING_KNOWN_UNLOADED_CHUNKS", yaml.getString(base + ".state"));
        assertEquals(2, yaml.getInt(base + ".removed.chunk"));
        assertEquals(List.of("retry"), yaml.getStringList(base + ".errors"));
        assertEquals(1, yaml.getInt(base + ".chunks.0.attempts"));
        assertEquals("timeout", yaml.getString(base + ".chunks.0.error"));
    }

    @Test
    void legacyPendingRemovalIsReconciledWhenPurgeOwnsTheDiary() {
        DiaryStore store = store();
        UUID player = UUID.randomUUID();
        store.queuePendingRemoval(player,
                new PendingRemoval("diary", DiaryLocationType.PLAYER_INVENTORY, player));
        store.reconcileLegacyPendingRemovals("diary");
        assertTrue(store.getPendingRemovals(player).isEmpty());
    }

    @Test
    void credibleOfflineHoldersOnlyIncludeActivePlayerInventoryScopes() {
        DiaryStore store = store();
        ItemStack snapshot = mock(ItemStack.class);
        when(snapshot.clone()).thenReturn(snapshot);
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        store.updateTrackedDiary("diary", UUID.randomUUID(), "owner", snapshot, location(active, DiaryLocationType.PLAYER_INVENTORY, true));
        store.updateTrackedDiary("diary", UUID.randomUUID(), "owner", snapshot, location(inactive, DiaryLocationType.PLAYER_ENDER_CHEST, false));
        store.queuePendingRemoval(queued, new PendingRemoval("diary", DiaryLocationType.PLAYER_INVENTORY, queued));
        assertEquals(java.util.Set.of(active), store.getCredibleOfflineHolders("diary"));
    }

    @Test
    void ambiguousPrefixIsRejected() {
        DiaryStore store = store();
        ItemStack snapshot = mock(ItemStack.class);
        when(snapshot.clone()).thenReturn(snapshot);
        store.updateTrackedDiary("same-prefix-one", UUID.randomUUID(), "one", snapshot, null);
        store.updateTrackedDiary("same-prefix-two", UUID.randomUUID(), "two", snapshot, null);
        assertTrue(store.isAmbiguousDiaryIdPrefix("same-prefix"));
        assertNull(store.findDiaryIdByExactOrPrefix("same-prefix"));
        assertEquals("same-prefix-one", store.findDiaryIdByExactOrPrefix("same-prefix-o"));
    }

    @Test
    void legacyWorldNameIsMigratedToUuid() throws Exception {
        UUID worldId = UUID.randomUUID();
        File file = temp.resolve("diaries.yml").toFile();
        YamlConfiguration yaml = new YamlConfiguration();
        String base = "trackedDiaries.diary.locations.0";
        yaml.set(base + ".type", DiaryLocationType.BLOCK_CONTAINER.name());
        yaml.set(base + ".world", "world");
        yaml.set(base + ".x", 16);
        yaml.set(base + ".y", 64);
        yaml.set(base + ".z", 16);
        yaml.set(base + ".active", true);
        yaml.save(file);
        DiaryStore store = store();
        World world = mock(World.class);
        when(world.getUID()).thenReturn(worldId);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            store.load();
        }
        assertEquals(worldId, store.getLocations("diary", false).getFirst().worldUuid());
    }

    @Test
    void completedOperationsArePrunedAfterRetention() {
        DiaryStore store = store();
        PurgeOperation old = operation(1L);
        old.setState(PurgeState.COMPLETED);
        old.setCompletedAt(1L);
        store.addPurgeOperation(old);
        store.pruneRetainedState();
        assertNull(store.getPurgeOperation(old.operationId()));
    }

    @Test
    void competingLegacyOperationsAreReducedToOneActiveOperation() {
        DiaryStore store = store();
        PurgeOperation owner = operation(1L);
        PurgeOperation admin = new PurgeOperation(UUID.randomUUID(), "diary", UUID.randomUUID(), UUID.randomUUID(),
                PurgeDestination.ADMIN, 2L, null);
        PurgeOperation purgeOnly = new PurgeOperation(UUID.randomUUID(), "diary", UUID.randomUUID(), null,
                PurgeDestination.NONE, 3L, null);
        store.addPurgeOperation(owner);
        store.addPurgeOperation(admin);
        store.addPurgeOperation(purgeOnly);
        store.reconcileCompetingPurgeOperations();
        assertEquals(1, store.getActivePurgeOperations().size());
        assertSame(owner, store.getActivePurgeOperations().getFirst());
    }

    @Test
    void saveFailureLeavesReadyOperationUndelivered() throws Exception {
        File notDirectory = temp.resolve("not-a-directory").toFile();
        assertTrue(notDirectory.createNewFile());
        DiaryStore store = store(notDirectory);
        PurgeOperation operation = operation(1L);
        operation.setState(PurgeState.READY_TO_RESTORE);
        store.addPurgeOperation(operation);
        assertThrows(CompletionException.class, () -> store.flushDurably().join());
        assertEquals(PurgeState.READY_TO_RESTORE, operation.state());
        assertFalse(operation.restorationOccurred());
        assertEquals(0, store.getTotalPendingDeliveryCount());
    }

    @Test
    void restartRetainsRestoreOutboxTokenAndDoesNotQueueItTwice() {
        DiaryStore first = store();
        UUID player = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        PurgeOperation operation = operation(1L);
        operation.setState(PurgeState.RESTORED);
        operation.setDeliveryToken(token);
        operation.setRestorationOccurred(true);
        operation.setReplacementHolder(player);
        first.addPurgeOperation(operation);
        first.flushDurably().join();

        DiaryStore restarted = store();
        restarted.load();
        PurgeOperation loaded = restarted.getPurgeOperation(operation.operationId());
        assertNotNull(loaded);
        assertTrue(loaded.restorationOccurred());
        assertEquals(token, loaded.deliveryToken());
        ItemStack diary = mock(ItemStack.class);
        when(diary.getType()).thenReturn(org.bukkit.Material.BUNDLE);
        when(diary.clone()).thenReturn(diary);
        restarted.queueDelivery(player, DeliveryReason.RESTORE_OWNER, diary, token);
        restarted.queueDelivery(player, DeliveryReason.RESTORE_OWNER, diary, token);
        assertEquals(1, restarted.getPendingDeliveryCount(player));
    }

    @Test
    void deliveredEntryDoesNotBlockTheNextQueuedDelivery() {
        DiaryStore store = store();
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(org.bukkit.Material.BUNDLE);
        when(item.clone()).thenReturn(item);
        store.queueDelivery(player, DeliveryReason.RESTORE_OWNER, item, first);
        store.queueDelivery(player, DeliveryReason.VOID_RETURN, item, second);
        assertTrue(store.claimDelivery(player, first));
        assertTrue(store.markDeliveryDelivered(player, first));
        assertEquals(second, store.getPendingDeliveries(player, 1).getFirst().token());
    }

    private DiaryStore store() {
        return store(temp.toFile());
    }

    private DiaryStore store(File dataFolder) {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.getLong(anyString(), anyLong())).thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        });
        return new DiaryStore(plugin);
    }

    private PurgeOperation operation(long startedAt) {
        return new PurgeOperation(UUID.randomUUID(), "diary", UUID.randomUUID(), null,
                PurgeDestination.NONE, startedAt, null);
    }

    private DiaryLocationRecord location(UUID holder, DiaryLocationType type, boolean active) {
        return new DiaryLocationRecord(type, "player", holder, "holder", null, null, null,
                null, null, null, null, List.<String>of(), "inventory", 0, 1L, 1L, active);
    }
}
