package com.p2wn.diary.commands;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.*;
import com.p2wn.diary.logic.DiaryService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiaryCommandDeliveryTest {
    @Test
    void defaultFilteringSortingAndPaginationAreDeterministic() {
        try (Fixture f = new Fixture()) {
        List<DeliveryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 55; i++) entries.add(f.entry(DeliveryLifecycle.DELIVERED, 100 + i, 0, 200 + i, null));
        DeliveryEntry claimNew = f.entry(DeliveryLifecycle.CLAIMED, 5, 30, 0, null);
        DeliveryEntry claimOld = f.entry(DeliveryLifecycle.CLAIMED, 4, 20, 0, "disk warning");
        entries.add(claimNew);
        entries.add(claimOld);
        for (int i = 0; i < 12; i++) entries.add(f.entry(DeliveryLifecycle.QUEUED, 40 + i, 0, 0, null));
        when(f.store.getDeliveryEntries()).thenReturn(entries);

        f.run("deliveries", "list");
        assertEquals(10, f.messages.size());
        assertTrue(f.messages.get(0).contains(claimOld.delivery().token().toString()));
        assertTrue(f.messages.get(0).contains("disk warning"));
        assertTrue(f.messages.get(1).contains(claimNew.delivery().token().toString()));
        assertTrue(f.messages.get(2).contains(entries.get(57).delivery().token().toString()));
        assertTrue(f.messages.stream().noneMatch(line -> line.contains("DELIVERED")));

        f.messages.clear();
        f.run("deliveries", "list", "open", "2");
        assertEquals(4, f.messages.size());
        assertTrue(f.messages.getFirst().contains(entries.get(65).delivery().token().toString()));
        }
    }

    @Test
    void everyFilterAndLifecycleStatusAreRendered() {
        try (Fixture f = new Fixture()) {
        DeliveryEntry queued = f.entry(DeliveryLifecycle.QUEUED, 1, 0, 0, null);
        DeliveryEntry claimed = f.entry(DeliveryLifecycle.CLAIMED, 2, 3, 0, null);
        DeliveryEntry pending = f.entry(DeliveryLifecycle.RELEASE_PENDING, 4, 5, 0, "unresolved");
        DeliveryEntry delivered = f.entry(DeliveryLifecycle.DELIVERED, 6, 7, 8, null);
        when(f.store.getDeliveryEntries()).thenReturn(List.of(delivered, queued, pending, claimed));

        f.assertFilter("queued", List.of(queued));
        f.assertFilter("claimed", List.of(claimed, pending));
        f.assertFilter("delivered", List.of(delivered));
        f.assertFilter("all", List.of(claimed, pending, queued, delivered));

        for (DeliveryEntry entry : List.of(queued, claimed, pending, delivered)) {
            f.messages.clear();
            when(f.store.getDeliveryEntry(entry.delivery().token())).thenReturn(entry);
            f.run("deliveries", "status", entry.delivery().token().toString());
            assertEquals(1, f.messages.size());
            assertTrue(f.messages.getFirst().contains(entry.delivery().lifecycle().name()));
        }
        }
    }

    @Test
    void durableAdministrationReportsActualCompletionResult() {
        try (Fixture f = new Fixture()) {
        DeliveryEntry queued = f.entry(DeliveryLifecycle.QUEUED, 1, 0, 0, null);
        when(f.store.getDeliveryEntry(queued.delivery().token())).thenReturn(queued);
        when(f.store.retryDeliveryDurably(queued.delivery().token()))
                .thenReturn(CompletableFuture.completedFuture(true));
        f.run("deliveries", "resolve", queued.delivery().token().toString(), "retry");
        assertEquals(List.of("Delivery update durably saved."), f.messages);

        f.messages.clear();
        when(f.store.cancelDeliveryDurably(queued.delivery().token()))
                .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("injected")));
        f.run("deliveries", "resolve", queued.delivery().token().toString(), "cancel");
        assertEquals(List.of("Delivery update failed and was not confirmed durable."), f.messages);
        assertTrue(f.messages.stream().noneMatch(message -> message.equals("Delivery updated.")));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DiaryPlugin plugin = mock(DiaryPlugin.class);
        final DiaryStore store = mock(DiaryStore.class);
        final DiaryService service = mock(DiaryService.class);
        final CommandSender sender = mock(CommandSender.class);
        final Command command = mock(Command.class);
        final List<String> messages = new ArrayList<>();
        final DiaryCommand executor = new DiaryCommand(plugin);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);

        Fixture() {
            when(sender.hasPermission("diary.admin")).thenReturn(true);
            doAnswer(call -> { messages.add(call.getArgument(0)); return null; })
                    .when(sender).sendMessage(anyString());
            when(plugin.diaryStore()).thenReturn(store);
            when(plugin.diaryService()).thenReturn(service);
            when(plugin.isEnabled()).thenReturn(true);
            when(service.getDiaryId(any())).thenReturn("diary");
            OfflinePlayer offline = mock(OfflinePlayer.class);
            when(offline.getName()).thenReturn("Owner");
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(offline);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run(); return null;
            });
        }

        DeliveryEntry entry(DeliveryLifecycle lifecycle, long created, long claimed,
                            long delivered, String error) {
            ItemStack item = mock(ItemStack.class);
            UUID token = UUID.randomUUID();
            return new DeliveryEntry(UUID.randomUUID(), new PendingDelivery(
                    DeliveryReason.VOID_RETURN, item, token, lifecycle, created, claimed, delivered, error));
        }

        void run(String... args) {
            executor.onCommand(sender, command, "diary", args);
        }

        void assertFilter(String filter, List<DeliveryEntry> expected) {
            messages.clear();
            run("deliveries", "list", filter, "1");
            assertEquals(expected.size(), messages.size());
            for (int i = 0; i < expected.size(); i++) {
                assertTrue(messages.get(i).contains(expected.get(i).delivery().token().toString()));
            }
        }

        @Override public void close() { bukkit.close(); }
    }
}
