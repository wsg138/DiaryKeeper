package com.p2wn.diary.data;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Reproducible synthetic CPU-only microbenchmark. No SMP/MSPT conclusions can be
 * drawn from this fixture; filesystem latency is explicitly excluded.
 */
class DiarySnapshotBenchmarkTest {
    @TempDir Path directory;

    @Test
    void repeatedUnchangedSavesReportColdVsCachedPercentiles() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        });

        SnapshotItemCache cache = new SnapshotItemCache();
        DiaryStore store = new DiaryStore(plugin, data -> { }, cache);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 64; i++) {
            ItemStack item = mock(ItemStack.class, withSettings().serializable());
            when(item.getType()).thenReturn(Material.WRITABLE_BOOK);
            when(item.clone()).thenReturn(item);
            store.queueDelivery(player, DeliveryReason.VOID_RETURN, item, UUID.randomUUID());
        }
        store.flushIfDirty();

        long[] uncached = new long[30];
        long[] cached = new long[30];
        for (int i = 0; i < uncached.length; i++) {
            cache.clear();
            store.recordDiaryEdit(player);
            store.flushIfDirty();
            uncached[i] = store.lastSnapshotMetrics().elapsedNanos();
            assertEquals(64, store.lastSnapshotMetrics().encodings());
        }
        for (int i = 0; i < cached.length; i++) {
            store.recordDiaryEdit(player);
            store.flushIfDirty();
            cached[i] = store.lastSnapshotMetrics().elapsedNanos();
            assertEquals(0, store.lastSnapshotMetrics().encodings());
        }
        System.out.println("DiaryKeeper synthetic snapshot benchmark: 64 pending items, "
                + "30 samples per mode, Java 21, excludes file write. "
                + "Uncached p50/p95/p99 ms=" + percentiles(uncached)
                + " cached p50/p95/p99 ms=" + percentiles(cached));
        assertEquals(64, store.lastSnapshotMetrics().deliveryRecords());
    }

    private static String percentiles(long[] values) {
        long[] ordered = values.clone();
        Arrays.sort(ordered);
        return millis(ordered[14]) + "/" + millis(ordered[28]) + "/" + millis(ordered[29]);
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
