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
 * Administrative recovery helpers that deliberately bypass the normal purge/restore
 * workflow. These actions are intended for cases where staff know the tracked copy is
 * gone and need one clean replacement without leaving stale delivery entries behind.
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
     * Cancels any still-destructive purge for this diary, clears stale open deliveries,
     * and gives exactly one saved snapshot to the executing administrator.
     *
     * <p>The diary's embedded owner data is not rewritten. If the admin inventory is
     * full, one replacement is queued for that admin after the stale entries have been
     * removed.</p>
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

        // The old duplicate action could leave several queued copies behind while a
        // purge repeatedly removed them. Remove only open entries so delivered audit
        // history is preserved.
        int removed = 0;
        for (DeliveryEntry entry : plugin.diaryStore().getDeliveryEntries()) {
            String entryDiaryId = plugin.diaryService().getDiaryId(entry.delivery().item());
            if (record.diaryId().equals(entryDiaryId)
                    && entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED
                    && plugin.diaryStore().cancelDelivery(entry.delivery().token())) {
                removed++;
            }
        }
        plugin.diaryStore().flushIfDirty();

        ItemStack snapshot = record.snapshot();
        if (admin.getInventory().addItem(snapshot).isEmpty()) {
            admin.updateInventory();
            plugin.diaryTrackerService().trackPlayerInventory(admin);
            plugin.duplicateWatcher().refreshPlayerSnapshot(admin);
            plugin.diaryService().refreshOwnedDiaries(admin);
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
        plugin.getLogger().warning("[Diary Recovery] Force-grant queued because admin inventory is full diary="
                + record.diaryId() + " admin=" + admin.getName() + "/" + admin.getUniqueId()
                + " delivery=" + token + " staleDeliveriesRemoved=" + removed
                + (cancelledPurgeId == null ? "" : " cancelledPurge=" + cancelledPurgeId));
        return new ForceGrantResult(ForceGrantStatus.QUEUED_FULL_INVENTORY, removed, cancelledPurgeId);
    }

    /**
     * Creates the legacy owner-targeted duplicate only when no active purge can eat it.
     */
    public boolean queueOwnerDuplicateIfSafe(TrackedDiaryRecord record, Player requestedAdmin) {
        if (record == null || record.snapshot() == null) {
            return false;
        }
        PurgeOperation active = plugin.diaryStore().getActivePurgeOperation(record.diaryId());
        if (active != null && !active.terminal()) {
            return false;
        }
        plugin.diaryPurgeService().restoreDuplicate(record, requestedAdmin);
        return true;
    }
}
