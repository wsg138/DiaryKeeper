package com.p2wn.diary.logic;

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
            // The executing admin may not be the diary owner. refreshOwnedDiaries()
            // therefore cannot replace the physical-location and duplicate tracking
            // that must happen for every direct emergency grant.
            plugin.diaryTrackerService().trackPlayerInventory(admin);
            plugin.duplicateWatcher().refreshPlayerSnapshot(admin);
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
