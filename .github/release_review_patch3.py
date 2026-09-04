from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise RuntimeError(f"Expected text not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


store_path = "src/main/java/com/p2wn/diary/data/DiaryStore.java"

# These methods operate on active/pending delivery work. DELIVERED entries are retained
# audit history and must not be treated as purgeable queued copies.
replace_once(
    store_path,
    '''        boolean removed = record.pendingDeliveries.removeIf(delivery -> diaryId.equals(extractDiaryId(delivery.item())));
''',
    '''        boolean removed = record.pendingDeliveries.removeIf(delivery ->
                delivery.lifecycle() != DeliveryLifecycle.DELIVERED
                        && diaryId.equals(extractDiaryId(delivery.item())));
''')
replace_once(
    store_path,
    '''            record.pendingDeliveries.removeIf(delivery -> diaryId.equals(extractDiaryId(delivery.item())));
''',
    '''            record.pendingDeliveries.removeIf(delivery ->
                    delivery.lifecycle() != DeliveryLifecycle.DELIVERED
                            && diaryId.equals(extractDiaryId(delivery.item())));
''')
replace_once(
    store_path,
    '''        return record != null && record.pendingDeliveries.stream()
                .anyMatch(delivery -> diaryId.equals(extractDiaryId(delivery.item())));
''',
    '''        return record != null && record.pendingDeliveries.stream()
                .anyMatch(delivery -> delivery.lifecycle() != DeliveryLifecycle.DELIVERED
                        && diaryId.equals(extractDiaryId(delivery.item())));
''')

# Regression coverage: a purge-queue cleanup removes an open entry while keeping a
# delivered entry for the same diary available in the audit list.
test_path = Path("src/test/java/com/p2wn/diary/data/DiaryStoreTest.java")
test = test_path.read_text()
if "void purgeQueueCleanupPreservesDeliveredAuditHistory()" not in test:
    marker = '''    @Test
    void deliveredEntryDoesNotBlockTheNextQueuedDelivery() {
'''
    if marker not in test:
        raise RuntimeError("DiaryStore test insertion marker missing")
    addition = '''    @Test
    void purgeQueueCleanupPreservesDeliveredAuditHistory() {
        DiaryStore store = store();
        UUID player = UUID.randomUUID();
        UUID queuedToken = UUID.randomUUID();
        UUID deliveredToken = UUID.randomUUID();
        org.bukkit.NamespacedKey diaryKey = new org.bukkit.NamespacedKey("diarykeeper", "diary_id");
        ItemStack item = mock(ItemStack.class);
        org.bukkit.inventory.meta.ItemMeta meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        org.bukkit.persistence.PersistentDataContainer pdc = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(item.getType()).thenReturn(org.bukkit.Material.WRITABLE_BOOK);
        when(item.clone()).thenReturn(item);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.getKeys()).thenReturn(java.util.Set.of(diaryKey));
        when(pdc.get(diaryKey, org.bukkit.persistence.PersistentDataType.STRING)).thenReturn("diary");

        store.queueDelivery(player, DeliveryReason.RESTORE_DUPLICATE, item, queuedToken);
        store.queueDelivery(player, DeliveryReason.RESTORE_OWNER, item, deliveredToken);
        assertTrue(store.claimDelivery(player, deliveredToken));
        assertTrue(store.markDeliveryDelivered(player, deliveredToken));

        assertEquals(1, store.removeAllPendingDeliveriesByDiaryId("diary"));
        assertNull(store.getDeliveryEntry(queuedToken));
        DeliveryEntry retained = store.getDeliveryEntry(deliveredToken);
        assertNotNull(retained);
        assertEquals(DeliveryLifecycle.DELIVERED, retained.delivery().lifecycle());
        assertFalse(store.hasPendingDelivery(player, "diary"));
    }

'''
    test = test.replace(marker, addition + marker, 1)
test_path.write_text(test)

print("Audit-history preservation patch applied successfully")
