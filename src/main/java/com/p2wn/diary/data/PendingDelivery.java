package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;

public record PendingDelivery(DeliveryReason reason, ItemStack item) {

    public PendingDelivery {
        item = item == null ? null : item.clone();
    }

    public PendingDelivery copy() {
        return new PendingDelivery(reason, item);
    }
}
