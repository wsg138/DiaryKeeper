package com.p2wn.diary.data;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SnapshotItemCacheTest {
    private static final String DIARY_ID = "diary";
    private static final String SERIALIZED_FIRST = "item:1";

    private static ItemStack item(int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    @Test
    void reusesUnchangedItemsAndInvalidatesEachIdentityIndependently() throws IOException {
        AtomicInteger encodes = new AtomicInteger();
        SnapshotItemCache cache = new SnapshotItemCache(item -> {
            encodes.incrementAndGet();
            return "item:" + item.getAmount();
        });
        ItemStack first = item(1);
        ItemStack second = item(2);
        UUID player = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        UUID purge = UUID.randomUUID();

        assertEquals(SERIALIZED_FIRST, cache.tracked(DIARY_ID, first));
        assertEquals(SERIALIZED_FIRST, cache.tracked(DIARY_ID, first));
        assertEquals(SERIALIZED_FIRST, cache.delivery(player, delivery, first));
        assertEquals(SERIALIZED_FIRST, cache.delivery(player, delivery, first));
        assertEquals(SERIALIZED_FIRST, cache.purge(purge, first));
        assertEquals(SERIALIZED_FIRST, cache.purge(purge, first));
        assertEquals(3, encodes.get());

        cache.invalidateTracked(DIARY_ID);
        cache.invalidateDelivery(player, delivery);
        cache.invalidatePurge(purge);
        assertEquals("item:2", cache.tracked(DIARY_ID, second));
        assertEquals("item:2", cache.delivery(player, delivery, second));
        assertEquals("item:2", cache.purge(purge, second));
        assertEquals(6, encodes.get());
        assertEquals(3, cache.hits());
        assertEquals(6, cache.misses());
    }

    @Test
    void failureNeverCachesPartialEncodingAndCleanupAllowsTokenReuse() {
        AtomicInteger attempts = new AtomicInteger();
        SnapshotItemCache cache = new SnapshotItemCache(item -> {
            if (attempts.getAndIncrement() == 0) throw new IOException("injected error");
            return "valid";
        });
        UUID player = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ItemStack item = item(1);
        assertThrows(IOException.class, () -> cache.delivery(player, delivery, item));
        assertFalse(cache.hasDelivery(player, delivery));
        assertDoesNotThrow(() -> cache.delivery(player, delivery, item));
        assertTrue(cache.hasDelivery(player, delivery));
        cache.retain(Set.of(), Set.of(), Set.of());
        assertFalse(cache.hasDelivery(player, delivery));
    }

    @Test
    void deliveryTokensAreScopedByPlayer() throws IOException {
        SnapshotItemCache cache = new SnapshotItemCache(item -> "amount:" + item.getAmount());
        UUID token = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        assertEquals("amount:1", cache.delivery(firstPlayer, token, item(1)));
        assertEquals("amount:2", cache.delivery(secondPlayer, token, item(2)));
    }

    @Test
    void cacheIsClearedOnReloadAndNullTokensAreNeverMemoized() throws IOException {
        AtomicInteger encodes = new AtomicInteger();
        SnapshotItemCache cache = new SnapshotItemCache(item -> {
            encodes.incrementAndGet();
            return "value";
        });
        ItemStack item = item(1);
        UUID player = UUID.randomUUID();
        cache.delivery(player, null, item);
        cache.delivery(player, null, item);
        assertEquals(2, encodes.get());
        cache.tracked("a", item);
        cache.clear();
        assertFalse(cache.hasTracked("a"));
        cache.tracked("a", item);
        assertEquals(4, encodes.get());
    }
}
