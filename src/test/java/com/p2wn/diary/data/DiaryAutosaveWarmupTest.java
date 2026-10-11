package com.p2wn.diary.data;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiaryAutosaveWarmupTest {
    private static final String OWNER_NAME = "owner";
    private static final String DIARY_ID = "diary";
    private static final int MAX_WARMUP_TICKS = 2048;
    private static final long WARMUP_PERIOD_TICKS = 1L;
    private static ItemStack item(int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getType()).thenReturn(Material.WRITABLE_BOOK);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }
    @TempDir Path directory;

    @Test
    void thousandsOfColdItemsUseOneBoundedWarmupAndOneConsistentSave() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 1024; i++) {
            harness.store.updateTrackedSnapshot("tracked-" + i, owner, OWNER_NAME, item(1));
            harness.store.queueDelivery(UUID.randomUUID(), DeliveryReason.VOID_RETURN,
                    item(1), UUID.randomUUID());
        }
        harness.fireAutosave();
        for (int tick = 0; tick < MAX_WARMUP_TICKS && harness.saves.get() == 0; tick++) {
            int before = harness.encodingCount.get();
            harness.fireWarmup();
            assertTrue(harness.encodingCount.get() - before <= 4);
        }
        assertEquals(2048, harness.encodingCount.get());
        assertEquals(1, harness.saves.get());
        assertEquals(1024, harness.lastSnapshot.get().getConfigurationSection("trackedDiaries")
                .getKeys(false).size());
    }

    @Test
    void mutationAfterPartialWarmupRequeuesStaleEncodingBeforeSave() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 9; i++) {
            harness.store.updateTrackedSnapshot("tracked-" + i, owner, OWNER_NAME, item(1));
        }
        harness.fireAutosave();
        harness.fireWarmup();
        harness.store.updateTrackedSnapshot("tracked-0", owner, OWNER_NAME, item(2));
        for (int i = 0; i < 10 && harness.saves.get() == 0; i++) harness.fireWarmup();
        assertEquals(1, harness.saves.get());
        assertEquals("2", harness.lastSnapshot.get().getString("trackedDiaries.tracked-0.snapshotBase64"));
    }

    @Test
    void runtimeEncoderFailureAllowsPeriodicAutosaveRetry() {
        Harness harness = new Harness(directory);
        harness.store.updateTrackedSnapshot(DIARY_ID, UUID.randomUUID(), OWNER_NAME, item(1));
        harness.rejectEncode.set(true);
        harness.fireAutosave();
        harness.fireWarmup();
        assertEquals(0, harness.saves.get());
        harness.rejectEncode.set(false);
        harness.fireAutosave();
        harness.fireWarmup();
        assertEquals(1, harness.saves.get());
        assertEquals("1", harness.lastSnapshot.get().getString("trackedDiaries.diary.snapshotBase64"));
    }

    @Test
    void runtimeEncoderFailureDoesNotMarkDurableFlushSuccessful() {
        Harness harness = new Harness(directory);
        harness.store.updateTrackedSnapshot(DIARY_ID, UUID.randomUUID(), OWNER_NAME, item(1));
        harness.rejectEncode.set(true);
        assertThrows(java.util.concurrent.CompletionException.class, () -> harness.store.flushDurably().join());
        assertEquals(0, harness.saves.get());
        harness.rejectEncode.set(false);
        harness.store.flushDurably();
        harness.runAsync();
        assertEquals(1, harness.saves.get());
    }

    @Test
    void rejectedAsyncSchedulingDoesNotStickAndCanBeRetried() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        harness.store.updateTrackedSnapshot(DIARY_ID, owner, OWNER_NAME, item(1));
        harness.rejectAsync.set(true);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> harness.store.flushDurably().join());
        assertEquals(0, harness.saves.get());
        harness.rejectAsync.set(false);
        harness.store.flushDurably();
        harness.runAsync();
        assertEquals(1, harness.saves.get());
        assertEquals("1", harness.lastSnapshot.get().getString("trackedDiaries.diary.snapshotBase64"));
    }

    @Test
    void unchangedSnapshotsAndLifecycleChangesReuseEncodingButRealEditsInvalidate() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        ItemStack first = item(1);
        harness.store.updateTrackedSnapshot(DIARY_ID, owner, OWNER_NAME, first);
        harness.store.queueDelivery(owner, DeliveryReason.VOID_RETURN, first, token);
        harness.store.flushDurably();
        harness.runAsync();
        assertEquals(2, harness.encodingCount.get());

        harness.store.updateTrackedSnapshot(DIARY_ID, owner, OWNER_NAME, first);
        assertTrue(harness.store.claimDelivery(owner, token));
        harness.store.flushDurably();
        harness.runAsync();
        assertEquals(2, harness.encodingCount.get());

        harness.store.updateTrackedSnapshot(DIARY_ID, owner, OWNER_NAME, item(2));
        harness.store.flushDurably();
        harness.runAsync();
        assertEquals(3, harness.encodingCount.get());
        assertEquals("2", harness.lastSnapshot.get().getString("trackedDiaries.diary.snapshotBase64"));

        assertTrue(harness.store.cancelDelivery(token));
        harness.store.queueDelivery(owner, DeliveryReason.VOID_RETURN, item(2), token);
        harness.store.flushDurably();
        harness.runAsync();
        assertEquals(4, harness.encodingCount.get());
        assertEquals("2", harness.lastSnapshot.get()
                .getString("pendingDeliveries." + owner + ".0.itemBase64"));
    }

    @Test
    void largeDirtyAutosaveSpreadsEncodingAcrossTicksBeforeCommitting() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 11; i++) {
            harness.store.queueDelivery(owner, DeliveryReason.VOID_RETURN,
                    item(1), UUID.randomUUID());
        }
        harness.fireAutosave();
        assertEquals(0, harness.encodingCount.get());
        assertEquals(0, harness.saves.get());

        harness.fireWarmup();
        assertEquals(4, harness.encodingCount.get());
        assertEquals(0, harness.saves.get());
        harness.fireWarmup();
        assertEquals(8, harness.encodingCount.get());
        assertEquals(0, harness.saves.get());
        harness.fireWarmup();
        assertEquals(11, harness.encodingCount.get());
        assertEquals(1, harness.saves.get());

        harness.store.recordDiaryEdit(owner);
        harness.fireAutosave();
        assertEquals(2, harness.saves.get() + harness.scheduledAsync.size());
        harness.runAsync();
        assertEquals(2, harness.saves.get());
        assertEquals(11, harness.encodingCount.get());
    }

    @Test
    void changedDiaryDuringWarmupSerializesTheLatestItemAndNotTheOldVersion() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        harness.store.updateTrackedSnapshot("id", owner, OWNER_NAME, item(1));
        harness.fireAutosave();
        harness.store.updateTrackedSnapshot("id", owner, OWNER_NAME, item(2));
        harness.fireWarmup();
        harness.runAsync();
        assertEquals("2", harness.lastSnapshot.get().getString("trackedDiaries.id.snapshotBase64"));
    }

    @Test
    void durableRetryReschedulesSnapshotOnMainThreadAfterAsyncCompletion() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        harness.store.queueDelivery(owner, DeliveryReason.VOID_RETURN,
                item(1), UUID.randomUUID());
        var first = harness.store.flushDurably();
        harness.store.queueDelivery(owner, DeliveryReason.VOID_RETURN,
                item(1), UUID.randomUUID());
        var second = harness.store.flushDurably();
        harness.runAsync();
        assertTrue(first.isDone());
        assertFalse(second.isDone());
        assertEquals(0, harness.scheduledAsync.size());
        assertEquals(1, harness.scheduledMain.size());
        harness.runMain();
        harness.runAsync();
        assertDoesNotThrow(second::join);
        assertEquals(2, harness.saves.get());
        assertEquals(2, harness.lastSnapshot.get().getConfigurationSection("pendingDeliveries." + owner)
                .getKeys(false).size());
    }

    @Test
    void durableFlushBypassesWarmupButDoesNotLosePendingRecords() {
        Harness harness = new Harness(directory);
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 15; i++) {
            harness.store.queueDelivery(owner, DeliveryReason.VOID_RETURN,
                    item(1), UUID.randomUUID());
        }
        harness.fireAutosave();
        harness.store.flushDurably();
        assertEquals(15, harness.encodingCount.get());
        harness.runAsync();
        assertEquals(1, harness.saves.get());
        assertEquals(15, harness.lastSnapshot.get().getConfigurationSection("pendingDeliveries." + owner)
                .getKeys(false).size());
    }

    private static final class Harness {
        private final AtomicInteger encodingCount = new AtomicInteger();
        private final AtomicInteger saves = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean rejectAsync = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean rejectEncode = new java.util.concurrent.atomic.AtomicBoolean();
        private final AtomicReference<FileConfiguration> lastSnapshot = new AtomicReference<>();
        private final Deque<Runnable> scheduledAsync = new ArrayDeque<>();
        private final Deque<Runnable> scheduledMain = new ArrayDeque<>();
        private final AtomicReference<Runnable> autosave = new AtomicReference<>();
        private final AtomicReference<Runnable> warmup = new AtomicReference<>();
        private final DiaryStore store;

        private Harness(Path path) {
            Plugin plugin = mock(Plugin.class);
            Server server = mock(Server.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            FileConfiguration config = mock(FileConfiguration.class);
            when(plugin.getDataFolder()).thenReturn(path.toFile());
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(server.getScheduler()).thenReturn(scheduler);
            when(config.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
            when(config.getLong(anyString(), anyLong())).thenAnswer(call -> call.getArgument(1));
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                    .thenAnswer(call -> {
                        Runnable task = call.getArgument(1);
                        long period = call.getArgument(3);
                        if (period == WARMUP_PERIOD_TICKS) warmup.set(task);
                        else autosave.set(task);
                        return mock(BukkitTask.class);
                    });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                scheduledMain.addLast(call.getArgument(1));
                return mock(BukkitTask.class);
            });
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                if (rejectAsync.get()) throw new IllegalStateException("injected scheduler rejection");
                scheduledAsync.addLast(call.getArgument(1));
                return mock(BukkitTask.class);
            });
            SnapshotItemCache cache = new SnapshotItemCache(item -> {
                if (rejectEncode.get()) throw new IllegalStateException("injected encoder exception");
                encodingCount.incrementAndGet();
                return Integer.toString(item.getAmount());
            });
            store = new DiaryStore(plugin, data -> {
                lastSnapshot.set(data);
                saves.incrementAndGet();
            }, cache);
            store.reloadAutosave();
        }

        private void fireAutosave() {
            autosave.get().run();
        }

        private void fireWarmup() {
            warmup.get().run();
            runAsync();
        }

        private void runAsync() {
            while (!scheduledAsync.isEmpty()) scheduledAsync.removeFirst().run();
        }

        private void runMain() {
            while (!scheduledMain.isEmpty()) scheduledMain.removeFirst().run();
        }
    }
}
