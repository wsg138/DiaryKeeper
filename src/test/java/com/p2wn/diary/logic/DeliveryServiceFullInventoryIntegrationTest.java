package com.p2wn.diary.logic;

import com.p2wn.diary.data.DeliveryLifecycle;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DiaryStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryServiceFullInventoryIntegrationTest {
    @TempDir Path temp;

    @BeforeEach void initializeBukkitRegistries() {
        MockBukkit.mock();
    }

    @AfterEach void closeBukkitRegistries() {
        MockBukkit.unmock();
    }

    @Test
    void fullInventoryReleaseIsDurableForeignThreadSafeAndRecoverableExactlyOnce() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.queueAndPersist();
            assertEquals(DeliveryLifecycle.QUEUED, f.lifecycle());

            f.service.tick();
            assertEquals(DeliveryLifecycle.CLAIMED, f.lifecycle());
            assertEquals(1, f.async.size());
            f.runAsyncOnForeignThread();
            assertEquals(DeliveryLifecycle.CLAIMED, f.lifecycle());
            assertEquals(1, f.main.size());

            f.runMain();
            assertEquals(DeliveryLifecycle.RELEASE_PENDING, f.lifecycle());
            assertTrue(f.store.getPendingDeliveries(f.playerId, 10).isEmpty(),
                    "release-pending delivery must not be eligible");
            verify(f.inventory, times(1)).addItem(any(ItemStack.class));
            verify(f.analytics, never()).record(eq(DiaryAnalyticsEventType.DELIVERED_FROM_QUEUE),
                    any(), any(), any(), any());

            f.finishDurableRelease();
            assertEquals(DeliveryLifecycle.QUEUED, f.lifecycle());
            assertEquals(1, f.store.getPendingDeliveries(f.playerId, 10).size());

            f.service.tick();
            f.runAsyncOnForeignThread();
            f.runMain();
            assertEquals(DeliveryLifecycle.RELEASE_PENDING, f.lifecycle());
            verify(f.inventory, times(2)).addItem(any(ItemStack.class));
            verify(f.analytics, never()).record(eq(DiaryAnalyticsEventType.DELIVERED_FROM_QUEUE),
                    any(), any(), any(), any());
            f.finishDurableRelease();

            DiaryStore restarted = new DiaryStore(f.plugin);
            restarted.load();
            assertEquals(1, restarted.getDeliveryEntries().size());
            assertEquals(f.deliveryId, restarted.getDeliveryEntries().getFirst().delivery().token());
            assertEquals(DeliveryLifecycle.QUEUED,
                    restarted.getDeliveryEntries().getFirst().delivery().lifecycle());
            assertEquals(1, restarted.getPendingDeliveries(f.playerId, 10).size());
        }
    }

    @Test
    void shutdownWhileActualYamlSaveIsLatchedMakesCompletionStaleButDiskRecoverable() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.queueAndPersist();
            f.service.tick();
            Runnable actualSave = f.async.removeFirst();
            CountDownLatch saveEntered = new CountDownLatch(1);
            CountDownLatch releaseSave = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                saveEntered.countDown();
                await(releaseSave);
                actualSave.run();
            }, "latched-diaries-yaml-writer");
            writer.start();
            assertTrue(saveEntered.await(1, TimeUnit.SECONDS));

            CountDownLatch shutdownStarted = new CountDownLatch(1);
            Thread shutdown = new Thread(() -> {
                shutdownStarted.countDown();
                f.service.shutdown();
            }, "delivery-shutdown");
            shutdown.start();
            assertTrue(shutdownStarted.await(1, TimeUnit.SECONDS));
            releaseSave.countDown();
            writer.join();
            shutdown.join();
            f.runAllMain();

            verify(f.inventory, never()).addItem(any(ItemStack.class));
            verify(f.scheduler, never()).runTaskTimer(eq(f.plugin), any(Runnable.class), anyLong(), anyLong());
            DiaryStore restarted = new DiaryStore(f.plugin);
            restarted.load();
            assertEquals(1, restarted.getDeliveryEntries().size());
            assertEquals(f.deliveryId, restarted.getDeliveryEntries().getFirst().delivery().token());
            assertNotEquals(DeliveryLifecycle.DELIVERED,
                    restarted.getDeliveryEntries().getFirst().delivery().lifecycle());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Plugin plugin = mock(Plugin.class);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final FileConfiguration config = mock(FileConfiguration.class);
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final Inventory enderChest = mock(Inventory.class);
        final DiaryAnalyticsStore analytics = mock(DiaryAnalyticsStore.class);
        final Deque<Runnable> async = new ArrayDeque<>();
        final Deque<Runnable> main = new ArrayDeque<>();
        final UUID playerId = UUID.randomUUID();
        final UUID deliveryId = UUID.randomUUID();
        final ItemStack queuedItem;
        final DiaryStore store;
        final DeliveryService service;
        final MockedStatic<Bukkit> bukkit;

        Fixture(Path dataFolder) {
            queuedItem = new ItemStack(Material.WRITABLE_BOOK);
            ItemFactory itemFactory = Bukkit.getItemFactory();
            UnsafeValues unsafeValues = Bukkit.getUnsafe();
            when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(plugin.isEnabled()).thenReturn(true);
            when(server.getScheduler()).thenReturn(scheduler);
            when(config.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
            when(config.getLong(anyString(), anyLong())).thenAnswer(call -> call.getArgument(1));
            when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                async.addLast(call.getArgument(1));
                return null;
            });
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                main.addLast(call.getArgument(1));
                return null;
            });
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.getName()).thenReturn("FullInventory");
            when(player.isOnline()).thenReturn(true);
            when(player.getInventory()).thenReturn(inventory);
            when(player.getEnderChest()).thenReturn(enderChest);
            when(inventory.getContents()).thenReturn(new ItemStack[0]);
            when(enderChest.getContents()).thenReturn(new ItemStack[0]);
            when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
                HashMap<Integer, ItemStack> leftovers = new HashMap<>();
                leftovers.put(0, call.getArgument(0));
                return leftovers;
            });
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getItemFactory).thenReturn(itemFactory);
            bukkit.when(Bukkit::getUnsafe).thenReturn(unsafeValues);
            store = new DiaryStore(plugin);
            service = new DeliveryService(plugin, store, new DeliveryService.MainThreadExecutor() {
                @Override public void execute(Runnable task) { main.addLast(task); }
                @Override public void executeLater(Runnable task, long delayTicks) { main.addLast(task); }
            });
            service.setAnalyticsStore(analytics);
        }

        void queueAndPersist() throws Exception {
            store.queueDelivery(playerId, DeliveryReason.VOID_RETURN,
                    queuedItem, deliveryId);
            store.flushDurably();
            runAsyncOnForeignThread();
        }

        DeliveryLifecycle lifecycle() {
            return store.getDeliveryEntry(deliveryId).delivery().lifecycle();
        }

        void finishDurableRelease() throws Exception {
            runAsyncOnForeignThread();
            runMain();
            runAsyncOnForeignThread();
            runAllMain();
        }

        void runAsyncOnForeignThread() throws Exception {
            assertFalse(async.isEmpty());
            Runnable task = async.removeFirst();
            CountDownLatch done = new CountDownLatch(1);
            Thread thread = new Thread(() -> {
                task.run();
                done.countDown();
            }, "diaries-yaml-writer");
            thread.start();
            assertTrue(done.await(1, TimeUnit.SECONDS));
            thread.join();
        }

        void runMain() {
            assertFalse(main.isEmpty());
            main.removeFirst().run();
        }

        void runAllMain() {
            while (!main.isEmpty()) main.removeFirst().run();
        }

        @Override public void close() {
            bukkit.close();
        }
    }
}
