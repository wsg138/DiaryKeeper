package com.p2wn.diary.data;

import java.util.UUID;

public record DeliveryEntry(UUID playerId, PendingDelivery delivery) {
}
