from pathlib import Path
import re
import textwrap


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise RuntimeError(f"Expected text not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


# Release version.
replace_once("pom.xml", "<version>1.4.8</version>", "<version>1.4.9</version>")
replace_once("src/main/resources/plugin.yml", "version: 1.4.8", "version: 1.4.9")

# Protect intentional duplicate creation inside the core service. This closes the
# command path as well as every GUI/helper path.
purge_path = Path("src/main/java/com/p2wn/diary/logic/DiaryPurgeService.java")
purge = purge_path.read_text()
pattern = re.compile(
    r"    public void restoreDuplicate\(TrackedDiaryRecord record, Player requestedAdmin\) \{.*?\n    \}\n\n"
    r"(?=    public void onObservedCopy)",
    re.S,
)
replacement = textwrap.dedent("""
    public boolean restoreDuplicate(TrackedDiaryRecord record, Player requestedAdmin) {
        if (record == null || record.snapshot() == null) {
            return false;
        }
        PurgeOperation active = store.getActivePurgeOperation(record.diaryId());
        if (active != null && !active.terminal()) {
            plugin.getLogger().warning("[Diary Purge] Blocked intentional duplicate restore diary=" + record.diaryId()
                    + " activeOperation=" + active.operationId()
                    + " state=" + active.state()
                    + " requestedBy=" + adminLabel(requestedAdmin));
            return false;
        }
        UUID token = UUID.randomUUID();
        plugin.deliveryService().queue(record.ownerUuid(), DeliveryReason.RESTORE_DUPLICATE, record.snapshot(), token);
        analytics(DiaryAnalyticsEventType.RESTORE_DUPLICATE, null, requestedAdmin == null ? null : requestedAdmin.getUniqueId(),
                "intentional duplicate");
        plugin.getLogger().warning("[Diary Purge] Intentional duplicate restore diary=" + record.diaryId()
                + " requestedBy=" + adminLabel(requestedAdmin));
        return true;
    }

""").strip("\n") + "\n\n"
replacement = textwrap.indent(replacement, "    ")
# dedent above produces a method at column 0; indent to class scope.
replacement = replacement.replace("        public boolean", "    public boolean", 1)
replacement = replacement.replace("\n        if", "\n        if")
# Rebuild explicitly to avoid clever indentation changing Java formatting.
replacement = """    public boolean restoreDuplicate(TrackedDiaryRecord record, Player requestedAdmin) {
        if (record == null || record.snapshot() == null) {
            return false;
        }
        PurgeOperation active = store.getActivePurgeOperation(record.diaryId());
        if (active != null && !active.terminal()) {
            plugin.getLogger().warning("[Diary Purge] Blocked intentional duplicate restore diary=" + record.diaryId()
                    + " activeOperation=" + active.operationId()
                    + " state=" + active.state()
                    + " requestedBy=" + adminLabel(requestedAdmin));
            return false;
        }
        UUID token = UUID.randomUUID();
        plugin.deliveryService().queue(record.ownerUuid(), DeliveryReason.RESTORE_DUPLICATE, record.snapshot(), token);
        analytics(DiaryAnalyticsEventType.RESTORE_DUPLICATE, null, requestedAdmin == null ? null : requestedAdmin.getUniqueId(),
                "intentional duplicate");
        plugin.getLogger().warning("[Diary Purge] Intentional duplicate restore diary=" + record.diaryId()
                + " requestedBy=" + adminLabel(requestedAdmin));
        return true;
    }

"""
purge, count = pattern.subn(replacement, purge, count=1)
if count != 1:
    raise RuntimeError(f"Expected one restoreDuplicate method, replaced {count}")
purge_path.write_text(purge)

# Direct command must surface a block rather than falsely saying it queued a copy.
command_path = Path("src/main/java/com/p2wn/diary/commands/DiaryCommand.java")
command = command_path.read_text()
command_pattern = re.compile(
    r'        if \("duplicate"\.equals\(mode\)\) \{\n'
    r'.*?'
    r'            return true;\n'
    r'        \}\n',
    re.S,
)
command_replacement = """        if ("duplicate".equals(mode)) {
            boolean queued = plugin.diaryPurgeService().restoreDuplicate(
                    record, sender instanceof Player player ? player : null);
            sender.sendMessage(plugin.configManager().msg(
                    queued ? "restore.duplicate-started" : "restore.duplicate-blocked"));
            return true;
        }
"""
command, count = command_pattern.subn(command_replacement, command, count=1)
if count != 1:
    raise RuntimeError(f"Expected one duplicate command block, replaced {count}")
command_path.write_text(command)

replace_once(
    "src/main/resources/messages.yml",
    '  duplicate-started: "&cWarning: an additional diary copy was intentionally restored without purging existing copies."\n',
    '  duplicate-started: "&cWarning: an additional diary copy was intentionally restored without purging existing copies."\n'
    '  duplicate-blocked: "&cCannot create an extra diary copy while a purge is active. Finish or cancel that purge first, or use the in-game emergency recovery action."\n',
)

# Emergency recovery is a deliberate break-glass operation. Make its cleanup durable,
# invalidate stale location observations, and never reuse an old delivery token stored
# in a tracked snapshot.
admin_recovery_path = Path("src/main/java/com/p2wn/diary/logic/AdminRecoveryService.java")
admin_recovery_path.write_text("""package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DeliveryEntry;
import com.p2wn.diary.data.DeliveryLifecycle;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.TrackedDiaryRecord;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Administrative recovery helpers for cases where staff know the tracked
 * physical diary is gone and need one clean replacement.
 */
public final class AdminRecoveryService {

    public enum ForceGrantStatus {
        DELIVERED_NOW,
        QUEUED_FULL_INVENTORY,
        FAILED_TO_CANCEL_PURGE,
        MISSING_SNAPSHOT
    }

    public record ForceGrantResult(
            ForceGrantStatus status,
            int staleDeliveriesRemoved,
            UUID cancelledPurgeId
    ) {
        public boolean success() {
            return status == ForceGrantStatus.DELIVERED_NOW
                    || status == ForceGrantStatus.QUEUED_FULL_INVENTORY;
        }
    }

    private final DiaryPlugin plugin;

    public AdminRecoveryService(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Cancels an active destructive purge, clears stale open deliveries,
     * invalidates stale active location observations, and creates exactly one
     * clean saved copy for the executing administrator.
     *
     * <p>The diary owner metadata is not rewritten.</p>
     */
    public ForceGrantResult forceGiveToAdmin(TrackedDiaryRecord record, Player admin) {
        if (record == null || record.snapshot() == null) {
            return new ForceGrantResult(ForceGrantStatus.MISSING_SNAPSHOT, 0, null);
        }

        PurgeOperation active = plugin.diaryStore().getActivePurgeOperation(record.diaryId());
        UUID cancelledPurgeId = null;
        if (active != null && !active.restorationOccurred()) {
            boolean cancelled = plugin.diaryPurgeService().cancel(
                    active.operationId(),
                    "emergency GUI recovery by " + admin.getName()
            );
            if (!cancelled) {
                return new ForceGrantResult(ForceGrantStatus.FAILED_TO_CANCEL_PURGE, 0, null);
            }
            cancelledPurgeId = active.operationId();
        }

        int removed = 0;
        for (DeliveryEntry entry : plugin.diaryStore().getDeliveryEntries()) {
            String entryDiaryId = plugin.diaryService().getDiaryId(entry.delivery().item());
            if (record.diaryId().equals(entryDiaryId)
                    && entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED
                    && plugin.diaryStore().cancelDelivery(entry.delivery().token())) {
                removed++;
            }
        }

        // Emergency recovery means staff have explicitly asserted that prior tracked
        // physical locations are stale. Keep them as history but stop treating them as
        // active before establishing the replacement.
        plugin.diaryStore().markAllLocationsInactive(record.diaryId());
        plugin.diaryStore().flushNowBlocking("emergency recovery cleanup");

        ItemStack snapshot = record.snapshot();
        clearDeliveryToken(snapshot);

        if (admin.getInventory().addItem(snapshot).isEmpty()) {
            admin.updateInventory();
            plugin.diaryService().refreshOwnedDiaries(admin);
            plugin.diaryStore().flushNowBlocking("emergency recovery direct grant");
            plugin.getLogger().warning("[Diary Recovery] Force-granted diary=" + record.diaryId()
                    + " to admin=" + admin.getName() + "/" + admin.getUniqueId()
                    + " staleDeliveriesRemoved=" + removed
                    + (cancelledPurgeId == null ? "" : " cancelledPurge=" + cancelledPurgeId));
            return new ForceGrantResult(ForceGrantStatus.DELIVERED_NOW, removed, cancelledPurgeId);
        }

        UUID token = UUID.randomUUID();
        plugin.deliveryService().queue(
                admin.getUniqueId(),
                DeliveryReason.RESTORE_ADMIN,
                snapshot,
                token
        );
        plugin.diaryStore().flushNowBlocking("emergency recovery queued replacement");
        plugin.getLogger().warning("[Diary Recovery] Force-grant queued because admin inventory is full diary="
                + record.diaryId() + " admin=" + admin.getName() + "/" + admin.getUniqueId()
                + " delivery=" + token + " staleDeliveriesRemoved=" + removed
                + (cancelledPurgeId == null ? "" : " cancelledPurge=" + cancelledPurgeId));
        return new ForceGrantResult(ForceGrantStatus.QUEUED_FULL_INVENTORY, removed, cancelledPurgeId);
    }

    /**
     * Creates an owner-targeted duplicate only when the core purge service says
     * doing so is safe.
     */
    public boolean queueOwnerDuplicateIfSafe(TrackedDiaryRecord record, Player requestedAdmin) {
        return plugin.diaryPurgeService().restoreDuplicate(record, requestedAdmin);
    }

    private void clearDeliveryToken(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(plugin.diaryKeys().deliveryToken());
        item.setItemMeta(meta);
    }
}
""")

# Prevent drag-based item movement into the admin UI and don't try to reopen screens
# if the admin disconnects during a durable async delivery operation.
gui_path = Path("src/main/java/com/p2wn/diary/listeners/RestoreGuiListener.java")
gui = gui_path.read_text()
if "import org.bukkit.event.inventory.InventoryDragEvent;" not in gui:
    gui = gui.replace(
        "import org.bukkit.event.inventory.InventoryClickEvent;\n",
        "import org.bukkit.event.inventory.InventoryClickEvent;\nimport org.bukkit.event.inventory.InventoryDragEvent;\n",
        1,
    )
gui = gui.replace("import java.util.Map;\n", "")
if "public void onDrag(InventoryDragEvent event)" not in gui:
    marker = "    private void openDiaryMenu(Player player, TrackedDiaryRecord record) {\n"
    if marker not in gui:
        raise RuntimeError("GUI insertion marker missing")
    drag_handler = """    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

"""
    gui = gui.replace(marker, drag_handler + marker, 1)

callback = "            Bukkit.getScheduler().runTask(plugin, () -> {\n"
callback_with_guard = (
    callback
    + "                if (!player.isOnline()) {\n"
    + "                    return;\n"
    + "                }\n"
)
if "if (!player.isOnline())" not in gui:
    count = gui.count(callback)
    if count != 3:
        raise RuntimeError(f"Expected 3 async GUI callbacks, found {count}")
    gui = gui.replace(callback, callback_with_guard)
gui_path.write_text(gui)

# Regression tests for emergency recovery behavior.
Path("src/test/java/com/p2wn/diary/logic/AdminRecoveryServiceTest.java").write_text("""package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryKeys;
import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DeliveryEntry;
import com.p2wn.diary.data.DeliveryLifecycle;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.PendingDelivery;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.TrackedDiaryRecord;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AdminRecoveryServiceTest {

    @Test
    void forceGrantCancelsPurgeClearsOnlyOpenDeliveriesAndScrubsOldToken() {
        Fixture fixture = fixture();
        UUID operationId = UUID.randomUUID();
        PurgeOperation active = mock(PurgeOperation.class);
        when(active.operationId()).thenReturn(operationId);
        when(active.restorationOccurred()).thenReturn(false);
        when(fixture.store.getActivePurgeOperation("diary")).thenReturn(active);
        when(fixture.purge.cancel(eq(operationId), contains("emergency GUI recovery"))).thenReturn(true);

        ItemStack openItem = mockItem();
        ItemStack deliveredItem = mockItem();
        UUID openToken = UUID.randomUUID();
        UUID deliveredToken = UUID.randomUUID();
        PendingDelivery open = new PendingDelivery(
                DeliveryReason.RESTORE_DUPLICATE, openItem, openToken, DeliveryLifecycle.QUEUED);
        PendingDelivery delivered = new PendingDelivery(
                DeliveryReason.RESTORE_DUPLICATE, deliveredItem, deliveredToken,
                DeliveryLifecycle.DELIVERED, 1L, 0L, 2L, null);
        when(fixture.store.getDeliveryEntries()).thenReturn(List.of(
                new DeliveryEntry(fixture.ownerId, open),
                new DeliveryEntry(fixture.ownerId, delivered)));
        when(fixture.diaryService.getDiaryId(openItem)).thenReturn("diary");
        when(fixture.diaryService.getDiaryId(deliveredItem)).thenReturn("diary");
        when(fixture.store.cancelDelivery(openToken)).thenReturn(true);
        when(fixture.inventory.addItem(fixture.snapshot)).thenReturn(new HashMap<>());

        AdminRecoveryService.ForceGrantResult result = fixture.service.forceGiveToAdmin(
                fixture.record, fixture.admin);

        assertEquals(AdminRecoveryService.ForceGrantStatus.DELIVERED_NOW, result.status());
        assertEquals(1, result.staleDeliveriesRemoved());
        assertEquals(operationId, result.cancelledPurgeId());
        verify(fixture.store).cancelDelivery(openToken);
        verify(fixture.store, never()).cancelDelivery(deliveredToken);
        verify(fixture.store).markAllLocationsInactive("diary");
        verify(fixture.store).flushNowBlocking("emergency recovery cleanup");
        verify(fixture.store).flushNowBlocking("emergency recovery direct grant");
        verify(fixture.pdc).remove(fixture.deliveryKey);
        verify(fixture.diaryService).refreshOwnedDiaries(fixture.admin);
        verify(fixture.delivery, never()).queue(any(), any(), any(), any());
    }

    @Test
    void fullInventoryQueuesExactlyOneCleanReplacement() {
        Fixture fixture = fixture();
        when(fixture.store.getDeliveryEntries()).thenReturn(List.of());
        when(fixture.inventory.addItem(fixture.snapshot))
                .thenReturn(new HashMap<>(Map.of(0, fixture.snapshot)));

        AdminRecoveryService.ForceGrantResult result = fixture.service.forceGiveToAdmin(
                fixture.record, fixture.admin);

        assertEquals(AdminRecoveryService.ForceGrantStatus.QUEUED_FULL_INVENTORY, result.status());
        verify(fixture.store).markAllLocationsInactive("diary");
        verify(fixture.pdc).remove(fixture.deliveryKey);
        verify(fixture.delivery, times(1)).queue(
                eq(fixture.adminId), eq(DeliveryReason.RESTORE_ADMIN),
                eq(fixture.snapshot), any(UUID.class));
        verify(fixture.store).flushNowBlocking("emergency recovery queued replacement");
        verify(fixture.diaryService, never()).refreshOwnedDiaries(any());
    }

    private Fixture fixture() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        DiaryPurgeService purge = mock(DiaryPurgeService.class);
        DiaryService diaryService = mock(DiaryService.class);
        DeliveryService delivery = mock(DeliveryService.class);
        DiaryKeys keys = mock(DiaryKeys.class);
        NamespacedKey deliveryKey = mock(NamespacedKey.class);
        Player admin = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack snapshot = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        UUID adminId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        when(plugin.diaryStore()).thenReturn(store);
        when(plugin.diaryPurgeService()).thenReturn(purge);
        when(plugin.diaryService()).thenReturn(diaryService);
        when(plugin.deliveryService()).thenReturn(delivery);
        when(plugin.diaryKeys()).thenReturn(keys);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(keys.deliveryToken()).thenReturn(deliveryKey);
        when(admin.getName()).thenReturn("admin");
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getInventory()).thenReturn(inventory);
        when(snapshot.clone()).thenReturn(snapshot);
        when(snapshot.getType()).thenReturn(Material.WRITABLE_BOOK);
        when(snapshot.hasItemMeta()).thenReturn(true);
        when(snapshot.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);

        TrackedDiaryRecord record = new TrackedDiaryRecord(
                "diary", ownerId, "owner", snapshot, null, List.of(), 1L);
        return new Fixture(new AdminRecoveryService(plugin), store, purge, diaryService,
                delivery, admin, inventory, snapshot, pdc, deliveryKey, adminId, ownerId, record);
    }

    private ItemStack mockItem() {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        when(item.getType()).thenReturn(Material.WRITABLE_BOOK);
        return item;
    }

    private record Fixture(
            AdminRecoveryService service,
            DiaryStore store,
            DiaryPurgeService purge,
            DiaryService diaryService,
            DeliveryService delivery,
            Player admin,
            PlayerInventory inventory,
            ItemStack snapshot,
            PersistentDataContainer pdc,
            NamespacedKey deliveryKey,
            UUID adminId,
            UUID ownerId,
            TrackedDiaryRecord record
    ) { }
}
""")

# Regression test proving the protection is in the service itself.
purge_test_path = Path("src/test/java/com/p2wn/diary/logic/DiaryPurgeServiceTest.java")
purge_test = purge_test_path.read_text()
if "void intentionalDuplicateIsBlockedWhilePurgeIsActive()" not in purge_test:
    marker = "    @Test\n    void repairDiscoveryIncludesChestOnlyDuplicate() throws Exception {\n"
    if marker not in purge_test:
        raise RuntimeError("Purge test insertion marker missing")
    addition = """    @Test
    void intentionalDuplicateIsBlockedWhilePurgeIsActive() {
        Fixture fixture = fixture();
        Player admin = mock(Player.class);
        PurgeOperation active = operation(PurgeDestination.OWNER);
        when(fixture.store.getActivePurgeOperation("diary")).thenReturn(active);

        assertFalse(fixture.service.restoreDuplicate(record(), admin));
        verify(fixture.delivery, never()).queue(any(), any(), any(), any());
    }

"""
    purge_test = purge_test.replace(marker, addition + marker, 1)
purge_test_path.write_text(purge_test)

print("Release hardening patch applied successfully")
