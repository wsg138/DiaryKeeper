package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public record PendingDelivery(DeliveryReason reason, ItemStack item, UUID token, DeliveryLifecycle lifecycle, long claimedAt) {

    public PendingDelivery {
        item = item == null ? null : item.clone();
    }

    public PendingDelivery copy() {
        return new PendingDelivery(reason, item, token, lifecycle, claimedAt);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item) {
        this(reason, item, null, DeliveryLifecycle.QUEUED, 0L);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item, UUID token) {
        this(reason, item, token, DeliveryLifecycle.QUEUED, 0L);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item, UUID token, DeliveryLifecycle lifecycle) {
        this(reason, item, token, lifecycle, 0L);
    }
}
