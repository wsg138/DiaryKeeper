package com.p2wn.diary.logic;

import com.p2wn.diary.data.DiaryStore;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryServiceThreadingTest {
    @Test
    void durableCompletionUsesMainThreadAndCurrentGenerationRestartsExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.complete(true);
            verify(fixture.scheduler).runTask(eq(fixture.plugin), any(Runnable.class));
            fixture.mainTask.get().run();
            verify(fixture.scheduler, times(1)).runTaskTimer(eq(fixture.plugin), any(Runnable.class), anyLong(), anyLong());
        }
    }

    @Test
    void reloadBeforeCompletionCannotRestartOldGeneration() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.service.reloadSettings();
            fixture.complete(true);
            fixture.mainTask.get().run();
            verify(fixture.scheduler, never()).runTaskTimer(eq(fixture.plugin), any(Runnable.class), anyLong(), anyLong());
        }
    }

    @Test
    void shutdownBeforeCompletionCannotRestartDelivery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.service.shutdown();
            fixture.complete(true);
            fixture.mainTask.get().run();
            verify(fixture.scheduler, never()).runTaskTimer(eq(fixture.plugin), any(Runnable.class), anyLong(), anyLong());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Plugin plugin = mock(Plugin.class);
        final DiaryStore store = mock(DiaryStore.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final CompletableFuture<Boolean> release = new CompletableFuture<>();
        final AtomicReference<Runnable> mainTask = new AtomicReference<>();
        final DeliveryService service;
        final UUID player = UUID.randomUUID();
        final UUID delivery = UUID.randomUUID();
        final MockedStatic<Bukkit> bukkit;

        Fixture() throws Exception {
            Server server = mock(Server.class);
            FileConfiguration config = mock(FileConfiguration.class);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(plugin.isEnabled()).thenReturn(true);
            when(server.getScheduler()).thenReturn(scheduler);
            when(store.releaseDeliveryClaimDurably(player, delivery)).thenReturn(release);
            when(store.getPendingDeliveryCount(player)).thenReturn(1);
            when(store.getPlayersWithPendingDeliveries()).thenReturn(Set.of());
            when(config.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                mainTask.set(call.getArgument(1));
                return null;
            });
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            service = new DeliveryService(plugin, store);
            Method method = DeliveryService.class.getDeclaredMethod(
                    "releaseClaimDurably", UUID.class, UUID.class, long.class);
            method.setAccessible(true);
            method.invoke(service, player, delivery, 0L);
        }

        void complete(boolean value) {
            release.complete(value);
            assertNotNull(mainTask.get());
        }

        @Override public void close() { bukkit.close(); }
    }
}
