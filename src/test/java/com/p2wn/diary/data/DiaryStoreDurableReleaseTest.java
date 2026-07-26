package com.p2wn.diary.data;

import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiaryStoreDurableReleaseTest {
    @TempDir Path temp;

    @Test
    void successfulReleaseDoesNotExposeRetryUntilQueuedSnapshotIsDurable() {
        try (Fixture f = new Fixture(temp)) {
            f.persistClaim();
            CompletableFuture<Boolean> release = f.store.releaseDeliveryClaimDurably(f.player, f.delivery);
            assertEquals(DeliveryLifecycle.RELEASE_PENDING, f.lifecycle());
            assertTrue(f.store.getPendingDeliveries(f.player, 10).isEmpty());

            f.runAsync();
            assertEquals("RELEASE_PENDING", f.writer.lastLifecycle);
            f.runMain();
            assertEquals(DeliveryLifecycle.QUEUED, f.lifecycle());
            assertFalse(release.isDone());
            assertEquals(1, f.store.getPendingDeliveries(f.player, 10).size());

            f.runAsync();
            assertEquals("QUEUED", f.writer.lastLifecycle);
            assertFalse(release.isDone());
            f.runMain();
            assertTrue(release.join());
        }
    }

    @Test
    void failedReleaseRemainsVisibleAndRetryCanLaterSucceed() {
        try (Fixture f = new Fixture(temp)) {
            f.persistClaim();
            f.writer.failuresRemaining = 1;
            CompletableFuture<Boolean> failed = f.store.releaseDeliveryClaimDurably(f.player, f.delivery);
            f.runAsync();
            f.runMain();
            assertThrows(CompletionException.class, failed::join);
            assertEquals(DeliveryLifecycle.RELEASE_PENDING, f.lifecycle());
            assertNotNull(f.store.getDeliveryEntry(f.delivery).delivery().lastPersistenceError());
            assertTrue(f.store.getPendingDeliveries(f.player, 10).isEmpty());

            CompletableFuture<Boolean> retry = f.store.retryDeliveryDurably(f.delivery);
            f.runAsync();
            f.runMain();
            f.runAsync();
            f.runMain();
            assertTrue(retry.join());
            assertEquals(DeliveryLifecycle.QUEUED, f.lifecycle());
        }
    }

    @Test
    void repeatedFailuresNeverBecomeDeliverableAndKeepExactDeliveryId() {
        try (Fixture f = new Fixture(temp)) {
            f.persistClaim();
            for (int attempt = 0; attempt < 3; attempt++) {
                f.writer.failuresRemaining = 1;
                CompletableFuture<Boolean> result = attempt == 0
                        ? f.store.releaseDeliveryClaimDurably(f.player, f.delivery)
                        : f.store.retryDeliveryDurably(f.delivery);
                f.runAsync();
                f.runMain();
                assertThrows(CompletionException.class, result::join);
                assertEquals(DeliveryLifecycle.RELEASE_PENDING, f.lifecycle());
                assertEquals(f.delivery, f.store.getDeliveryEntries().getFirst().delivery().token());
                assertTrue(f.store.getPendingDeliveries(f.player, 10).isEmpty());
            }
            assertEquals(1, f.store.getDeliveryEntries().size());
        }
    }

    @Test
    void disabledPluginCompletesExceptionallyWithoutSchedulingMainThreadWork() {
        try (Fixture f = new Fixture(temp)) {
            f.persistClaim();
            CompletableFuture<Boolean> result = f.store.releaseDeliveryClaimDurably(f.player, f.delivery);
            when(f.plugin.isEnabled()).thenReturn(false);
            f.runAsync();
            assertThrows(CompletionException.class, result::join);
            assertTrue(f.main.isEmpty());
            assertEquals(DeliveryLifecycle.RELEASE_PENDING, f.lifecycle());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Plugin plugin = mock(Plugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Deque<Runnable> async = new ArrayDeque<>();
        final Deque<Runnable> main = new ArrayDeque<>();
        final RecordingWriter writer = new RecordingWriter();
        final DiaryStore store;
        final UUID player = UUID.randomUUID();
        final UUID delivery = UUID.randomUUID();

        Fixture(Path temp) {
            Server server = mock(Server.class);
            FileConfiguration config = mock(FileConfiguration.class);
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
            store = new DiaryStore(plugin, writer);
            ItemStack item = mock(ItemStack.class, withSettings().serializable());
            when(item.getType()).thenReturn(org.bukkit.Material.BUNDLE);
            when(item.clone()).thenReturn(item);
            store.queueDelivery(player, DeliveryReason.VOID_RETURN, item, delivery);
        }

        void persistClaim() {
            store.flushDurably(); runAsync();
            assertTrue(store.claimDelivery(player, delivery));
            store.flushDurably(); runAsync();
            assertEquals(DeliveryLifecycle.CLAIMED, lifecycle());
        }

        DeliveryLifecycle lifecycle() {
            return store.getDeliveryEntry(delivery).delivery().lifecycle();
        }

        void runAsync() { assertFalse(async.isEmpty()); async.removeFirst().run(); }
        void runMain() { assertFalse(main.isEmpty()); main.removeFirst().run(); }
        @Override public void close() { }
    }

    private static final class RecordingWriter implements DiaryStore.PersistenceWriter {
        int failuresRemaining;
        String lastLifecycle;

        @Override
        public void write(FileConfiguration data) throws IOException {
            if (failuresRemaining-- > 0) throw new IOException("injected save failure");
            lastLifecycle = data.getString("pendingDeliveries."
                    + data.getConfigurationSection("pendingDeliveries").getKeys(false).iterator().next()
                    + ".0.lifecycle");
        }
    }
}
