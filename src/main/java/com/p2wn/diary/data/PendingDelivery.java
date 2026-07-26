package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public record PendingDelivery(DeliveryReason reason, ItemStack item, UUID token, DeliveryLifecycle lifecycle) {

    public PendingDelivery {
        item = item == null ? null : item.clone();
    }

    public PendingDelivery copy() {
        return new PendingDelivery(reason, item, token, lifecycle);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item) {
        this(reason, item, null, DeliveryLifecycle.QUEUED);
    }

    public PendingDelivery(DeliveryReason reason, ItemStack item, UUID token) {
        this(reason, item, token, DeliveryLifecycle.QUEUED);
    }
}
