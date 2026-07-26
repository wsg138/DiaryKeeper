package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public record PendingDelivery(
        DeliveryReason reason,
        ItemStack item,
        UUID token,
        DeliveryLifecycle lifecycle,
        long createdAt,
        long claimedAt,
        long deliveredAt,
        String lastPersistenceError
) {

    public PendingDelivery {
        item = item == null ? null : item.clone();
    }

    public PendingDelivery copy() {
        return new PendingDelivery(reason, item, token, lifecycle, createdAt, claimedAt, deliveredAt,
                lastPersistenceError);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item) {
        this(reason, item, null, DeliveryLifecycle.QUEUED, now(), 0L, 0L, null);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item, UUID token) {
        this(reason, item, token, DeliveryLifecycle.QUEUED, now(), 0L, 0L, null);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item, UUID token, DeliveryLifecycle lifecycle) {
        this(reason, item, token, lifecycle, now(), 0L, 0L, null);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item, UUID token, DeliveryLifecycle lifecycle,
                           long claimedAt) {
        this(reason, item, token, lifecycle, now(), claimedAt, 0L, null);
    }

    private static long now() {
        return java.time.Instant.now().getEpochSecond();
    }
}
