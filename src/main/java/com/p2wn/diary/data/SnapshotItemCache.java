package com.p2wn.diary.data;

import com.p2wn.diary.util.ItemIO;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Main-thread-only cache of encoded, privately owned item snapshots.
 * Callers must invalidate an entry before replacing its source item.
 */
final class SnapshotItemCache {
    @FunctionalInterface
    interface Encoder {
        String encode(ItemStack item) throws IOException;
    }

    private final Encoder encoder;
    private final Map<String, String> trackedEncodings = new HashMap<>();
    record DeliveryKey(UUID playerId, UUID token) { }

    private final Map<DeliveryKey, String> deliveries = new HashMap<>();
    private final Map<UUID, String> purges = new HashMap<>();
    private long cacheHits;
    private long cacheMisses;
    private long accumulatedEncodeNanos;

    SnapshotItemCache() {
        this(ItemIO::toBase64);
    }

    SnapshotItemCache(Encoder encoder) {
        this.encoder = encoder;
    }

    String tracked(String id, ItemStack item) throws IOException {
        return encode(trackedEncodings, id, item);
    }

    String delivery(UUID playerId, UUID token, ItemStack item) throws IOException {
        return token == null ? uncached(item) : encode(deliveries, new DeliveryKey(playerId, token), item);
    }

    String purge(UUID operationId, ItemStack item) throws IOException {
        return encode(purges, operationId, item);
    }

    private <K> String encode(Map<K, String> cache, K key, ItemStack item) throws IOException {
        String value = cache.get(key);
        if (value != null) {
            cacheHits++;
            return value;
        }
        value = uncached(item);
        cache.put(key, value);
        return value;
    }

    private String uncached(ItemStack item) throws IOException {
        cacheMisses++;
        long startedAtNanos = System.nanoTime();
        String encoded = encoder.encode(item);
        accumulatedEncodeNanos += System.nanoTime() - startedAtNanos;
        return encoded;
    }

    boolean hasTracked(String id) {
        return trackedEncodings.containsKey(id);
    }

    boolean hasDelivery(UUID playerId, UUID token) {
        return token != null && deliveries.containsKey(new DeliveryKey(playerId, token));
    }

    boolean hasPurge(UUID operationId) {
        return purges.containsKey(operationId);
    }

    void invalidateTracked(String id) {
        trackedEncodings.remove(id);
    }

    void invalidateDelivery(UUID playerId, UUID id) {
        deliveries.remove(new DeliveryKey(playerId, id));
    }

    void invalidatePurge(UUID id) {
        purges.remove(id);
    }

    void retain(Set<String> trackedIds, Set<DeliveryKey> deliveryIds, Set<UUID> purgeIds) {
        trackedEncodings.keySet().retainAll(trackedIds);
        deliveries.keySet().retainAll(deliveryIds);
        purges.keySet().retainAll(purgeIds);
    }

    void clear() {
        trackedEncodings.clear();
        deliveries.clear();
        purges.clear();
    }

    long hits() {
        return cacheHits;
    }

    long misses() {
        return cacheMisses;
    }

    long encodingNanos() {
        return accumulatedEncodeNanos;
    }
}
