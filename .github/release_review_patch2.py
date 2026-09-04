from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise RuntimeError(f"Expected text not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


# A successful command-line retry must wake the delivery worker after the delivery
# has been durably returned to QUEUED.
replace_once(
    "src/main/java/com/p2wn/diary/commands/DiaryCommand.java",
    '''        CompletableFuture<Boolean> result = switch (args[3].toLowerCase(Locale.ROOT)) {
            case "retry" -> plugin.diaryStore().retryDeliveryDurably(deliveryId);
            case "delivered" -> plugin.diaryStore().markDeliveryDeliveredDurably(deliveryId);
            case "cancel" -> plugin.diaryStore().cancelDeliveryDurably(deliveryId);
            default -> CompletableFuture.completedFuture(false);
        };
        result.whenComplete((changed, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                    failure == null && Boolean.TRUE.equals(changed)
                            ? "Delivery update durably saved."
                            : "Delivery update failed and was not confirmed durable."));
        });
''',
    '''        String deliveryAction = args[3].toLowerCase(Locale.ROOT);
        CompletableFuture<Boolean> result = switch (deliveryAction) {
            case "retry" -> plugin.diaryStore().retryDeliveryDurably(deliveryId);
            case "delivered" -> plugin.diaryStore().markDeliveryDeliveredDurably(deliveryId);
            case "cancel" -> plugin.diaryStore().cancelDeliveryDurably(deliveryId);
            default -> CompletableFuture.completedFuture(false);
        };
        result.whenComplete((changed, failure) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean success = failure == null && Boolean.TRUE.equals(changed);
                if (success && "retry".equals(deliveryAction)) {
                    plugin.deliveryService().requestDelivery(entry.playerId());
                }
                sender.sendMessage(success
                        ? "Delivery update durably saved."
                        : "Delivery update failed and was not confirmed durable.");
            });
        });
''')

# FAILED purge operations are resumable in DiaryPurgeService.resume(), just like
# CANCELLED operations. Expose that backend capability consistently in the GUI.
replace_once(
    "src/main/java/com/p2wn/diary/listeners/RestoreGuiListener.java",
    '''        } else if (operation.state() == PurgeState.CANCELLED && !operation.restorationOccurred()) {
            inventory.setItem(22, button(Material.CLOCK,
                    title("Resume Cancelled Purge", NamedTextColor.YELLOW),
                    List.of(text("Restarts this retained operation.", NamedTextColor.GRAY), click("Click to resume"))));
        }
''',
    '''        } else if ((operation.state() == PurgeState.CANCELLED || operation.state() == PurgeState.FAILED)
                && !operation.restorationOccurred()) {
            inventory.setItem(22, button(Material.CLOCK,
                    title("Resume " + pretty(operation.state().name()) + " Purge", NamedTextColor.YELLOW),
                    List.of(text("Restarts this retained operation.", NamedTextColor.GRAY), click("Click to resume"))));
        }
''')
replace_once(
    "src/main/java/com/p2wn/diary/listeners/RestoreGuiListener.java",
    '''        if (slot == 20 || (slot == 22 && operation.state() == PurgeState.CANCELLED)) {
''',
    '''        if (slot == 20 || (slot == 22
                && (operation.state() == PurgeState.CANCELLED || operation.state() == PurgeState.FAILED))) {
''')

# Extend the existing command test so this wake-up behavior cannot regress.
test_path = Path("src/test/java/com/p2wn/diary/commands/DiaryCommandDeliveryTest.java")
test = test_path.read_text()
replace_old = '''        f.run("deliveries", "resolve", queued.delivery().token().toString(), "retry");
        assertEquals(List.of("Delivery update durably saved."), f.messages);

        f.messages.clear();
'''
replace_new = '''        f.run("deliveries", "resolve", queued.delivery().token().toString(), "retry");
        assertEquals(List.of("Delivery update durably saved."), f.messages);
        verify(f.delivery).requestDelivery(queued.playerId());

        f.messages.clear();
'''
if replace_old not in test:
    raise RuntimeError("Delivery command test assertion marker missing")
test = test.replace(replace_old, replace_new, 1)
replace_old = '''        final DiaryService service = mock(DiaryService.class);
        final CommandSender sender = mock(CommandSender.class);
'''
replace_new = '''        final DiaryService service = mock(DiaryService.class);
        final com.p2wn.diary.logic.DeliveryService delivery = mock(com.p2wn.diary.logic.DeliveryService.class);
        final CommandSender sender = mock(CommandSender.class);
'''
if replace_old not in test:
    raise RuntimeError("Delivery test fixture field marker missing")
test = test.replace(replace_old, replace_new, 1)
replace_old = '''            when(plugin.diaryStore()).thenReturn(store);
            when(plugin.diaryService()).thenReturn(service);
            when(plugin.isEnabled()).thenReturn(true);
'''
replace_new = '''            when(plugin.diaryStore()).thenReturn(store);
            when(plugin.diaryService()).thenReturn(service);
            when(plugin.deliveryService()).thenReturn(delivery);
            when(plugin.isEnabled()).thenReturn(true);
'''
if replace_old not in test:
    raise RuntimeError("Delivery test fixture setup marker missing")
test = test.replace(replace_old, replace_new, 1)
test_path.write_text(test)

print("Second-pass management consistency patch applied successfully")
