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
import java.util.ArrayDeque;
import java.util.Deque;

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

    @Test
    void trackedIdentityMigrationUsesObservationTimePreservesAliasesAndSurvivesRestart() throws Exception {
        UUID owner = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("identities." + owner + ".name", "OldJavaName");
        yaml.set("identities." + owner + ".aliases", List.of("OlderAlias"));
        yaml.set("identities." + owner + ".lastSeen", 100L);
        yaml.set("trackedDiaries.diary.ownerUuid", owner.toString());
        yaml.set("trackedDiaries.diary.ownerName", "ExactBedrockName");
        yaml.set("trackedDiaries.diary.snapshotUpdatedAt", 200L);
        yaml.save(temp.resolve("diaries.yml").toFile());

        DiaryStore first = store();
        first.load();
        assertEquals("ExactBedrockName", first.identityForTesting(owner).currentName());
        assertTrue(first.identityForTesting(owner).aliases().containsAll(
                List.of("OldJavaName", "OlderAlias", "ExactBedrockName")));
        first.flushDurably().join();

        DiaryStore restarted = store();
        restarted.load();
        assertEquals(owner, restarted.resolveStoredPlayer("olderalias").uuid());
        assertEquals(owner, restarted.resolveStoredPlayer(owner.toString()).uuid());
        assertEquals("ExactBedrockName", restarted.identityForTesting(owner).currentName());
    }

    @Test
    void olderTrackedNameBecomesAliasAndAmbiguousAliasNeverGuesses() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("identities." + first + ".name", "NewName");
        yaml.set("identities." + first + ".aliases", List.of("SharedAlias"));
        yaml.set("identities." + first + ".lastSeen", 500L);
        yaml.set("identities." + second + ".name", "OtherName");
        yaml.set("identities." + second + ".aliases", List.of("sharedalias"));
        yaml.set("identities." + second + ".lastSeen", 500L);
        yaml.set("trackedDiaries.diary.ownerUuid", first.toString());
        yaml.set("trackedDiaries.diary.ownerName", "OldTrackedName");
        yaml.set("trackedDiaries.diary.snapshotUpdatedAt", 100L);
        yaml.save(temp.resolve("diaries.yml").toFile());

        DiaryStore store = store();
        store.load();
        assertEquals("NewName", store.identityForTesting(first).currentName());
        assertTrue(store.identityForTesting(first).aliases().contains("OldTrackedName"));
        assertEquals(IdentityResolution.Status.AMBIGUOUS,
                store.resolveStoredPlayer("SHAREDALIAS").status());
    }

    @Test
    void claimedReleaseIsNotQueuedUntilBothDurableStagesCompleteAndRestartKeepsOneEntry() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        FileConfiguration config = mock(FileConfiguration.class);
        Deque<Runnable> async = new ArrayDeque<>();
        Deque<Runnable> main = new ArrayDeque<>();
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            async.addLast(call.getArgument(1)); return null;
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            main.addLast(call.getArgument(1)); return null;
        });
        DiaryStore store = new DiaryStore(plugin);
        UUID player = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class, withSettings().serializable());
        when(item.getType()).thenReturn(org.bukkit.Material.BUNDLE);
        when(item.clone()).thenReturn(item);
        store.queueDelivery(player, DeliveryReason.VOID_RETURN, item, delivery);
        store.flushDurably();
        async.removeFirst().run();
        assertTrue(store.claimDelivery(player, delivery));
        store.flushDurably();
        async.removeFirst().run();

        var release = store.releaseDeliveryClaimDurably(player, delivery);
        assertEquals(DeliveryLifecycle.RELEASE_PENDING, store.getDeliveryEntry(delivery).delivery().lifecycle());
        async.removeFirst().run();
        assertEquals(DeliveryLifecycle.RELEASE_PENDING, store.getDeliveryEntry(delivery).delivery().lifecycle());
        main.removeFirst().run();
        assertEquals(DeliveryLifecycle.QUEUED, store.getDeliveryEntry(delivery).delivery().lifecycle());
        assertFalse(release.isDone());
        async.removeFirst().run();
        main.removeFirst().run();
        assertTrue(release.join());

        DiaryStore restarted = new DiaryStore(plugin);
        restarted.load();
        assertEquals(1, restarted.getDeliveryEntries().size());
        assertEquals(DeliveryLifecycle.QUEUED, restarted.getDeliveryEntry(delivery).delivery().lifecycle());
    }

    @Test
    void deliveryRetentionPrunesOnlyDeliveredAndLegacyRecordsGainCompatibleTimestamps() throws Exception {
        UUID player = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class, withSettings().serializable());
        when(item.getType()).thenReturn(org.bukkit.Material.BUNDLE);
        when(item.clone()).thenReturn(item);
        String encoded = com.p2wn.diary.util.ItemIO.toBase64(item);
        UUID delivered = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        writeLegacyDelivery(yaml, player, 0, delivered, "DELIVERED", encoded);
        yaml.set("pendingDeliveries." + player + ".0.deliveredAt", 1L);
        writeLegacyDelivery(yaml, player, 1, queued, "QUEUED", encoded);
        writeLegacyDelivery(yaml, player, 2, pending, "RELEASE_PENDING", encoded);
        yaml.save(temp.resolve("diaries.yml").toFile());

        DiaryStore store = store();
        store.load();
        assertTrue(store.getDeliveryEntry(queued).delivery().createdAt() > 0L);
        assertTrue(store.getDeliveryEntry(pending).delivery().createdAt() > 0L);
        store.pruneRetainedState();
        assertNull(store.getDeliveryEntry(delivered));
        assertNotNull(store.getDeliveryEntry(queued));
        assertNotNull(store.getDeliveryEntry(pending));
    }

    @Test
    void restartPreservesClaimedAndReleasePendingAsSingleNonDeliverableRecords() {
        UUID player = UUID.randomUUID();
        UUID claimedId = UUID.randomUUID();
        UUID pendingId = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class, withSettings().serializable());
        when(item.getType()).thenReturn(org.bukkit.Material.BUNDLE);
        when(item.clone()).thenReturn(item);
        DiaryStore first = store();
        first.queueDelivery(player, DeliveryReason.VOID_RETURN, item, claimedId);
        first.queueDelivery(player, DeliveryReason.RESTORE_OWNER, item, pendingId);
        assertTrue(first.claimDelivery(player, claimedId));
        assertTrue(first.claimDelivery(player, pendingId));
        first.flushDurably().join();
        assertThrows(CompletionException.class,
                () -> first.releaseDeliveryClaimDurably(player, pendingId).join());

        DiaryStore restarted = store();
        restarted.load();
        assertEquals(2, restarted.getDeliveryEntries().size());
        assertEquals(DeliveryLifecycle.CLAIMED,
                restarted.getDeliveryEntry(claimedId).delivery().lifecycle());
        assertEquals(DeliveryLifecycle.RELEASE_PENDING,
                restarted.getDeliveryEntry(pendingId).delivery().lifecycle());
        assertTrue(restarted.getPendingDeliveries(player, 10).isEmpty());
    }

    @Test
    void startupRecoveryDurablyReturnsPersistedReleasePendingToQueuedExactlyOnce() {
        UUID player = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ItemStack item = mock(ItemStack.class, withSettings().serializable());
        when(item.getType()).thenReturn(org.bukkit.Material.BUNDLE);
        when(item.clone()).thenReturn(item);

        DiaryStore first = store();
        first.queueDelivery(player, DeliveryReason.VOID_RETURN, item, delivery);
        assertTrue(first.claimDelivery(player, delivery));
        first.flushDurably().join();
        assertThrows(CompletionException.class,
                () -> first.releaseDeliveryClaimDurably(player, delivery).join());

        DiaryStore recovered = store();
        recovered.load();
        assertEquals(DeliveryLifecycle.RELEASE_PENDING, recovered.getDeliveryEntry(delivery).delivery().lifecycle());
        assertTrue(recovered.recoverInterruptedDeliveryReleases().join());
        assertEquals(DeliveryLifecycle.QUEUED, recovered.getDeliveryEntry(delivery).delivery().lifecycle());

        DiaryStore restarted = store();
        restarted.load();
        assertEquals(1, restarted.getDeliveryEntries().size());
        assertEquals(delivery, restarted.getDeliveryEntries().getFirst().delivery().token());
        assertEquals(DeliveryLifecycle.QUEUED, restarted.getDeliveryEntries().getFirst().delivery().lifecycle());
        assertEquals(1, restarted.getPendingDeliveries(player, 10).size());
    }

    private void writeLegacyDelivery(YamlConfiguration yaml, UUID player, int index, UUID token,
                                     String lifecycle, String encoded) {
        String base = "pendingDeliveries." + player + "." + index;
        yaml.set(base + ".reason", DeliveryReason.VOID_RETURN.name());
        yaml.set(base + ".itemBase64", encoded);
        yaml.set(base + ".token", token.toString());
        yaml.set(base + ".lifecycle", lifecycle);
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
