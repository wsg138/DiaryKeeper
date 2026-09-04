package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DeliveryEntry;
import com.p2wn.diary.data.DeliveryLifecycle;
import com.p2wn.diary.data.DiaryLocationRecord;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.PurgeState;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.logic.AdminRecoveryService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class RestoreGuiListener implements Listener {

    private static final int LIST_PAGE_SIZE = 45;

    private enum Screen {
        DIARY,
        CONFIRM,
        DELIVERIES,
        DELIVERY_DETAIL,
        PURGES,
        PURGE_DETAIL,
        LOCATIONS
    }

    private enum Action {
        PURGE_OWNER,
        PURGE_ADMIN,
        FORCE_GIVE_ME,
        DUPLICATE_OWNER,
        CANCEL_ALL_DELIVERIES,
        DELIVERY_CANCEL,
        DELIVERY_MARK_DELIVERED,
        PURGE_CANCEL
    }

    private final DiaryPlugin plugin;
    private final AdminRecoveryService recoveryService;

    public RestoreGuiListener(DiaryPlugin plugin) {
        this.plugin = plugin;
        this.recoveryService = new AdminRecoveryService(plugin);
    }

    public void openRestoreGui(Player player, TrackedDiaryRecord record) {
        openDiaryMenu(player, fresh(record));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) {
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        switch (holder.screen()) {
            case DIARY -> handleDiaryClick(player, holder, slot);
            case CONFIRM -> handleConfirmationClick(player, holder, slot);
            case DELIVERIES -> handleDeliveryListClick(player, holder, slot);
            case DELIVERY_DETAIL -> handleDeliveryDetailClick(player, holder, slot);
            case PURGES -> handlePurgeListClick(player, holder, slot);
            case PURGE_DETAIL -> handlePurgeDetailClick(player, holder, slot);
            case LOCATIONS -> handleLocationClick(player, holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void openDiaryMenu(Player player, TrackedDiaryRecord record) {
        if (record == null || record.snapshot() == null) {
            player.closeInventory();
            player.sendMessage("§cThat diary no longer has a usable saved snapshot.");
            return;
        }

        PurgeOperation activePurge = plugin.diaryStore().getActivePurgeOperation(record.diaryId());
        List<DeliveryEntry> deliveries = deliveriesFor(record);
        long openDeliveries = deliveries.stream()
                .filter(entry -> entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED)
                .count();

        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.DIARY, 0, null, null),
                45,
                "Diary Admin • " + ownerName(record)
        );
        addFrame(inventory);

        inventory.setItem(4, button(Material.WRITABLE_BOOK,
                title("Diary: " + ownerName(record), NamedTextColor.GOLD),
                List.of(
                        kv("Diary ID", shortId(record.diaryId())),
                        kv("Owner UUID", shortId(record.ownerUuid())),
                        kv("Active locations", record.activeLocationCount()),
                        kv("Open deliveries", openDeliveries),
                        blank(),
                        text("All management actions for this diary are here.", NamedTextColor.GRAY)
                )));

        inventory.setItem(10, button(Material.LIME_WOOL,
                title("Clean Restore to Owner", NamedTextColor.GREEN),
                List.of(
                        text("Removes discoverable copies first.", NamedTextColor.GRAY),
                        text("Restores one saved copy to the owner.", NamedTextColor.GRAY),
                        blank(),
                        text("Best when duplicate copies may still exist.", NamedTextColor.WHITE),
                        click("Click to review")
                )));

        inventory.setItem(12, button(Material.YELLOW_WOOL,
                title("Purge + Give to Me", NamedTextColor.YELLOW),
                List.of(
                        text("Removes discoverable copies first.", NamedTextColor.GRAY),
                        text("Then gives the unchanged diary to you.", NamedTextColor.GRAY),
                        text("The diary owner does not change.", NamedTextColor.GRAY),
                        blank(),
                        click("Click to review")
                )));

        inventory.setItem(14, button(Material.EMERALD,
                title("Emergency: Give Me One Now", NamedTextColor.AQUA),
                List.of(
                        text("Use when the real diary is known to be gone.", NamedTextColor.WHITE),
                        blank(),
                        text("• Cancels an active destructive purge", NamedTextColor.GRAY),
                        text("• Clears stale queued copies for this diary", NamedTextColor.GRAY),
                        text("• Gives exactly one saved copy to you", NamedTextColor.GRAY),
                        text("• Keeps the original owner metadata", NamedTextColor.GRAY),
                        blank(),
                        click("Click to review")
                )));

        NamedTextColor duplicateColor = activePurge == null ? NamedTextColor.GOLD : NamedTextColor.RED;
        inventory.setItem(16, button(activePurge == null ? Material.ORANGE_WOOL : Material.RED_WOOL,
                title("Create Extra Owner Copy", duplicateColor),
                activePurge == null
                        ? List.of(
                                text("Queues one additional copy for the owner.", NamedTextColor.GRAY),
                                text("Existing copies are not removed.", NamedTextColor.GRAY),
                                blank(),
                                text("Only use this when a duplicate is intentional.", NamedTextColor.YELLOW),
                                click("Click to review")
                        )
                        : List.of(
                                text("Blocked while a purge is active.", NamedTextColor.RED),
                                text("The old GUI allowed this and the purge could", NamedTextColor.GRAY),
                                text("immediately remove the new queued copy.", NamedTextColor.GRAY),
                                blank(),
                                text("Cancel/finish the purge or use Emergency Give.", NamedTextColor.WHITE)
                        )));

        inventory.setItem(20, button(Material.COMPASS,
                title("Locations", NamedTextColor.AQUA),
                List.of(
                        kv("Active", record.activeLocationCount()),
                        kv("Tracked/history", record.locations().size()),
                        record.lastKnownLocation() == null
                                ? text("Last known: unknown", NamedTextColor.DARK_GRAY)
                                : text("Last known: " + trim(record.lastKnownLocation().description(), 42), NamedTextColor.GRAY),
                        blank(),
                        click("Click to browse")
                )));

        inventory.setItem(22, button(Material.CLOCK,
                title("Purge History", NamedTextColor.YELLOW),
                List.of(
                        kv("Operations", plugin.diaryStore().getPurgeOperationsForDiary(record.diaryId()).size()),
                        activePurge == null
                                ? text("No active purge.", NamedTextColor.GREEN)
                                : text("Active: " + pretty(activePurge.state().name()), stateColor(activePurge.state())),
                        blank(),
                        click("Click to view operations")
                )));

        inventory.setItem(24, button(Material.HOPPER,
                title("Deliveries", NamedTextColor.AQUA),
                List.of(
                        kv("Open", openDeliveries),
                        kv("Total retained", deliveries.size()),
                        blank(),
                        text("View state, recipient, reason and errors.", NamedTextColor.GRAY),
                        text("Retry, cancel or resolve individual deliveries.", NamedTextColor.GRAY),
                        click("Click to manage")
                )));

        if (activePurge != null) {
            inventory.setItem(30, purgeItem(activePurge, true));
        } else {
            inventory.setItem(30, button(Material.LIME_DYE,
                    title("No Active Purge", NamedTextColor.GREEN),
                    List.of(text("Nothing is currently removing copies of this diary.", NamedTextColor.GRAY))));
        }

        inventory.setItem(32, button(openDeliveries > 0 ? Material.REDSTONE_BLOCK : Material.GRAY_DYE,
                title("Cancel Open Deliveries", openDeliveries > 0 ? NamedTextColor.RED : NamedTextColor.GRAY),
                openDeliveries > 0
                        ? List.of(
                                text("Cancels all currently open deliveries", NamedTextColor.GRAY),
                                text("for this diary without touching delivered history.", NamedTextColor.GRAY),
                                blank(),
                                kv("Will cancel", openDeliveries),
                                click("Click to review")
                        )
                        : List.of(text("There are no open deliveries to cancel.", NamedTextColor.GRAY))));

        inventory.setItem(40, button(Material.SUNFLOWER,
                title("Refresh", NamedTextColor.YELLOW),
                List.of(text("Reload current delivery, purge and location data.", NamedTextColor.GRAY), click("Click to refresh"))));
        inventory.setItem(44, closeButton());

        player.openInventory(inventory);
    }

    private void handleDiaryClick(Player player, Holder holder, int slot) {
        TrackedDiaryRecord record = fresh(holder.record());
        switch (slot) {
            case 10 -> openConfirmation(player, record, Action.PURGE_OWNER, null);
            case 12 -> openConfirmation(player, record, Action.PURGE_ADMIN, null);
            case 14 -> openConfirmation(player, record, Action.FORCE_GIVE_ME, null);
            case 16 -> {
                PurgeOperation active = plugin.diaryStore().getActivePurgeOperation(record.diaryId());
                if (active != null) {
                    player.sendMessage("§cExtra-copy restore is blocked while this diary has an active purge. Use Emergency Give if the original is truly gone.");
                    openDiaryMenu(player, record);
                } else {
                    openConfirmation(player, record, Action.DUPLICATE_OWNER, null);
                }
            }
            case 20 -> openLocations(player, record, 0);
            case 22 -> openPurges(player, record, 0);
            case 24 -> openDeliveries(player, record, 0);
            case 30 -> {
                PurgeOperation active = plugin.diaryStore().getActivePurgeOperation(record.diaryId());
                if (active != null) {
                    openPurgeDetail(player, record, active.operationId());
                }
            }
            case 32 -> {
                if (openDeliveryCount(record) > 0) {
                    openConfirmation(player, record, Action.CANCEL_ALL_DELIVERIES, null);
                }
            }
            case 40 -> openDiaryMenu(player, record);
            case 44 -> player.closeInventory();
            default -> { }
        }
    }

    private void openConfirmation(Player player, TrackedDiaryRecord record, Action action, UUID selectedId) {
        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.CONFIRM, 0, selectedId, action),
                27,
                "Confirm Diary Action"
        );
        addFrame(inventory);

        inventory.setItem(13, confirmationSummary(record, action, selectedId));
        inventory.setItem(11, button(Material.LIME_CONCRETE,
                title("Confirm", NamedTextColor.GREEN),
                List.of(text("Perform the action shown in the center.", NamedTextColor.GRAY), click("Click to confirm"))));
        inventory.setItem(15, button(Material.RED_CONCRETE,
                title("Go Back", NamedTextColor.RED),
                List.of(text("Make no changes.", NamedTextColor.GRAY), click("Click to return"))));
        player.openInventory(inventory);
    }

    private ItemStack confirmationSummary(TrackedDiaryRecord record, Action action, UUID selectedId) {
        return switch (action) {
            case PURGE_OWNER -> button(Material.LIME_WOOL,
                    title("Clean Restore to Owner", NamedTextColor.GREEN),
                    List.of(
                            text("This starts persistent purge work.", NamedTextColor.GRAY),
                            text("The restore waits for the purge to finish.", NamedTextColor.GRAY),
                            blank(),
                            text("Destination: diary owner", NamedTextColor.WHITE)
                    ));
            case PURGE_ADMIN -> button(Material.YELLOW_WOOL,
                    title("Purge + Give to Me", NamedTextColor.YELLOW),
                    List.of(
                            text("This starts persistent purge work.", NamedTextColor.GRAY),
                            text("The restore waits for the purge to finish.", NamedTextColor.GRAY),
                            blank(),
                            text("Destination: your inventory", NamedTextColor.WHITE),
                            text("Owner metadata stays unchanged.", NamedTextColor.GRAY)
                    ));
            case FORCE_GIVE_ME -> button(Material.EMERALD,
                    title("Emergency Force Grant", NamedTextColor.AQUA),
                    List.of(
                            text("This is intentionally different from a normal restore.", NamedTextColor.WHITE),
                            blank(),
                            text("1. Cancel the active destructive purge", NamedTextColor.GRAY),
                            text("2. Clear stale delivery entries for this diary", NamedTextColor.GRAY),
                            text("3. Put one saved copy directly in your inventory", NamedTextColor.GRAY),
                            blank(),
                            text("Use only when you know the real copy is gone.", NamedTextColor.YELLOW)
                    ));
            case DUPLICATE_OWNER -> button(Material.ORANGE_WOOL,
                    title("Create Intentional Duplicate", NamedTextColor.GOLD),
                    List.of(
                            text("One additional copy will be queued for the owner.", NamedTextColor.GRAY),
                            text("No existing copy is removed.", NamedTextColor.GRAY),
                            blank(),
                            text("This is blocked if a purge becomes active.", NamedTextColor.YELLOW)
                    ));
            case CANCEL_ALL_DELIVERIES -> button(Material.REDSTONE_BLOCK,
                    title("Cancel All Open Deliveries", NamedTextColor.RED),
                    List.of(
                            kv("Open deliveries", openDeliveryCount(record)),
                            text("Delivered audit entries are left alone.", NamedTextColor.GRAY),
                            blank(),
                            text("Use this to clear stale queued/claimed copies.", NamedTextColor.WHITE)
                    ));
            case DELIVERY_CANCEL -> {
                DeliveryEntry entry = selectedId == null ? null : plugin.diaryStore().getDeliveryEntry(selectedId);
                yield entry == null
                        ? button(Material.BARRIER, title("Delivery Missing", NamedTextColor.RED), List.of(text("It may already have been resolved.", NamedTextColor.GRAY)))
                        : button(Material.RED_CONCRETE, title("Cancel Delivery", NamedTextColor.RED), deliveryLore(entry));
            }
            case DELIVERY_MARK_DELIVERED -> {
                DeliveryEntry entry = selectedId == null ? null : plugin.diaryStore().getDeliveryEntry(selectedId);
                yield entry == null
                        ? button(Material.BARRIER, title("Delivery Missing", NamedTextColor.RED), List.of(text("It may already have been resolved.", NamedTextColor.GRAY)))
                        : button(Material.LIME_CONCRETE, title("Mark Delivered", NamedTextColor.GREEN), List.of(
                                text("Administrative override.", NamedTextColor.YELLOW),
                                text("This tells DiaryKeeper to stop trying this delivery", NamedTextColor.GRAY),
                                text("even if the item is not actually present.", NamedTextColor.GRAY),
                                blank(),
                                kv("Delivery", shortId(entry.delivery().token()))
                        ));
            }
            case PURGE_CANCEL -> {
                PurgeOperation operation = selectedId == null ? null : plugin.diaryStore().getPurgeOperation(selectedId);
                yield operation == null
                        ? button(Material.BARRIER, title("Purge Missing", NamedTextColor.RED), List.of(text("The operation no longer exists.", NamedTextColor.GRAY)))
                        : button(Material.RED_CONCRETE, title("Cancel Purge", NamedTextColor.RED), List.of(
                                kv("Operation", shortId(operation.operationId())),
                                kv("State", pretty(operation.state().name())),
                                text("Stops remaining purge work without restoring a copy.", NamedTextColor.GRAY)
                        ));
            }
        };
    }

    private void handleConfirmationClick(Player player, Holder holder, int slot) {
        if (slot == 15) {
            returnFromConfirmation(player, holder);
            return;
        }
        if (slot != 11 || holder.action() == null) {
            return;
        }

        TrackedDiaryRecord record = fresh(holder.record());
        switch (holder.action()) {
            case PURGE_OWNER -> startPurge(player, record, PurgeDestination.OWNER);
            case PURGE_ADMIN -> startPurge(player, record, PurgeDestination.ADMIN);
            case FORCE_GIVE_ME -> performForceGive(player, record);
            case DUPLICATE_OWNER -> performOwnerDuplicate(player, record);
            case CANCEL_ALL_DELIVERIES -> cancelAllOpenDeliveries(player, record);
            case DELIVERY_CANCEL -> resolveDelivery(player, record, holder.selectedId(), false);
            case DELIVERY_MARK_DELIVERED -> resolveDelivery(player, record, holder.selectedId(), true);
            case PURGE_CANCEL -> cancelPurge(player, record, holder.selectedId());
        }
    }

    private void returnFromConfirmation(Player player, Holder holder) {
        if (holder.action() == Action.DELIVERY_CANCEL || holder.action() == Action.DELIVERY_MARK_DELIVERED) {
            openDeliveryDetail(player, fresh(holder.record()), holder.selectedId());
        } else if (holder.action() == Action.PURGE_CANCEL) {
            openPurgeDetail(player, fresh(holder.record()), holder.selectedId());
        } else {
            openDiaryMenu(player, fresh(holder.record()));
        }
    }

    private void startPurge(Player player, TrackedDiaryRecord record, PurgeDestination destination) {
        PurgeOperation operation = plugin.diaryPurgeService().begin(record, destination, player);
        if (operation == null) {
            player.sendMessage("§cThe purge could not be started.");
            openDiaryMenu(player, record);
            return;
        }
        if (operation.destination() != destination || !Objects.equals(operation.adminUuid(), player.getUniqueId())) {
            player.sendMessage("§eA purge is already active for this diary. Open Purge History to manage it.");
            openPurgeDetail(player, record, operation.operationId());
            return;
        }
        player.sendMessage("§aPurge started. §7You can watch or cancel it from the diary GUI.");
        openPurgeDetail(player, record, operation.operationId());
    }

    private void performForceGive(Player player, TrackedDiaryRecord record) {
        AdminRecoveryService.ForceGrantResult result = recoveryService.forceGiveToAdmin(record, player);
        switch (result.status()) {
            case DELIVERED_NOW -> player.sendMessage("§aDiary recovered directly to your inventory. §7Cleared "
                    + result.staleDeliveriesRemoved() + " stale delivery record(s)."
                    + (result.cancelledPurgeId() == null ? "" : " Cancelled the active purge."));
            case QUEUED_FULL_INVENTORY -> player.sendMessage("§eYour inventory is full. §7The stale deliveries were cleared and exactly one replacement is queued for you.");
            case FAILED_TO_CANCEL_PURGE -> player.sendMessage("§cCould not safely cancel the active purge, so no diary was created.");
            case MISSING_SNAPSHOT -> player.sendMessage("§cThere is no saved diary snapshot to recover.");
        }
        openDiaryMenu(player, fresh(record));
    }

    private void performOwnerDuplicate(Player player, TrackedDiaryRecord record) {
        if (!recoveryService.queueOwnerDuplicateIfSafe(record, player)) {
            player.sendMessage("§cThe duplicate was not queued. A purge is active or the saved snapshot is unavailable.");
            openDiaryMenu(player, fresh(record));
            return;
        }
        player.sendMessage("§eOne intentional additional copy was queued for the diary owner.");
        openDeliveries(player, fresh(record), 0);
    }

    private void openDeliveries(Player player, TrackedDiaryRecord record, int requestedPage) {
        List<DeliveryEntry> entries = deliveriesFor(record);
        int page = clampPage(requestedPage, entries.size());
        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.DELIVERIES, page, null, null),
                54,
                "Diary Deliveries • " + ownerName(record)
        );

        int start = page * LIST_PAGE_SIZE;
        int end = Math.min(start + LIST_PAGE_SIZE, entries.size());
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, deliveryItem(entries.get(i)));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.LIME_DYE,
                    title("No Delivery Records", NamedTextColor.GREEN),
                    List.of(text("There are no retained deliveries for this diary.", NamedTextColor.GRAY))));
        }

        addListNavigation(inventory, page, entries.size(), "Back to Diary");
        player.openInventory(inventory);
    }

    private void handleDeliveryListClick(Player player, Holder holder, int slot) {
        TrackedDiaryRecord record = fresh(holder.record());
        if (slot == 45 && holder.page() > 0) {
            openDeliveries(player, record, holder.page() - 1);
            return;
        }
        if (slot == 49) {
            openDiaryMenu(player, record);
            return;
        }
        if (slot == 53 && (holder.page() + 1) * LIST_PAGE_SIZE < deliveriesFor(record).size()) {
            openDeliveries(player, record, holder.page() + 1);
            return;
        }
        if (slot >= LIST_PAGE_SIZE) {
            return;
        }

        List<DeliveryEntry> entries = deliveriesFor(record);
        int index = holder.page() * LIST_PAGE_SIZE + slot;
        if (index >= 0 && index < entries.size()) {
            openDeliveryDetail(player, record, entries.get(index).delivery().token());
        }
    }

    private void openDeliveryDetail(Player player, TrackedDiaryRecord record, UUID deliveryId) {
        DeliveryEntry entry = deliveryId == null ? null : plugin.diaryStore().getDeliveryEntry(deliveryId);
        if (entry == null || !record.diaryId().equals(plugin.diaryService().getDiaryId(entry.delivery().item()))) {
            player.sendMessage("§eThat delivery no longer exists.");
            openDeliveries(player, record, 0);
            return;
        }

        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.DELIVERY_DETAIL, 0, deliveryId, null),
                45,
                "Delivery • " + shortId(deliveryId)
        );
        addFrame(inventory);
        inventory.setItem(13, deliveryItem(entry));

        if (entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED) {
            inventory.setItem(20, button(Material.CLOCK,
                    title("Retry Delivery", NamedTextColor.YELLOW),
                    List.of(
                            text("Releases a stuck claim when necessary", NamedTextColor.GRAY),
                            text("and asks the delivery worker to try again.", NamedTextColor.GRAY),
                            blank(),
                            click("Click to retry")
                    )));
            inventory.setItem(22, button(Material.LIME_CONCRETE,
                    title("Mark Delivered", NamedTextColor.GREEN),
                    List.of(
                            text("Administrative override.", NamedTextColor.YELLOW),
                            text("Use only if you know the item is already present.", NamedTextColor.GRAY),
                            click("Click to review")
                    )));
            inventory.setItem(24, button(Material.RED_CONCRETE,
                    title("Cancel Delivery", NamedTextColor.RED),
                    List.of(
                            text("Stops this queued/claimed delivery permanently.", NamedTextColor.GRAY),
                            click("Click to review")
                    )));
        } else {
            inventory.setItem(22, button(Material.LIME_DYE,
                    title("Already Delivered", NamedTextColor.GREEN),
                    List.of(text("This delivery is retained only as audit history.", NamedTextColor.GRAY))));
        }

        inventory.setItem(31, backButton("Back to Deliveries"));
        inventory.setItem(40, button(Material.SUNFLOWER,
                title("Refresh", NamedTextColor.YELLOW),
                List.of(click("Click to reload this delivery"))));
        player.openInventory(inventory);
    }

    private void handleDeliveryDetailClick(Player player, Holder holder, int slot) {
        TrackedDiaryRecord record = fresh(holder.record());
        DeliveryEntry entry = holder.selectedId() == null ? null : plugin.diaryStore().getDeliveryEntry(holder.selectedId());
        if (slot == 31) {
            openDeliveries(player, record, 0);
            return;
        }
        if (slot == 40) {
            openDeliveryDetail(player, record, holder.selectedId());
            return;
        }
        if (entry == null || entry.delivery().lifecycle() == DeliveryLifecycle.DELIVERED) {
            return;
        }
        switch (slot) {
            case 20 -> retryDelivery(player, record, entry);
            case 22 -> openConfirmation(player, record, Action.DELIVERY_MARK_DELIVERED, entry.delivery().token());
            case 24 -> openConfirmation(player, record, Action.DELIVERY_CANCEL, entry.delivery().token());
            default -> { }
        }
    }

    private void retryDelivery(Player player, TrackedDiaryRecord record, DeliveryEntry entry) {
        UUID deliveryId = entry.delivery().token();
        plugin.diaryStore().retryDeliveryDurably(deliveryId).whenComplete((changed, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (failure == null && Boolean.TRUE.equals(changed)) {
                    plugin.deliveryService().requestDelivery(entry.playerId());
                    player.sendMessage("§aDelivery reset and queued for another attempt.");
                } else {
                    player.sendMessage("§cDelivery retry could not be durably saved.");
                }
                openDeliveryDetail(player, fresh(record), deliveryId);
            });
        });
    }

    private void resolveDelivery(Player player, TrackedDiaryRecord record, UUID deliveryId, boolean delivered) {
        if (deliveryId == null) {
            openDeliveries(player, record, 0);
            return;
        }
        CompletableFuture<Boolean> future = delivered
                ? plugin.diaryStore().markDeliveryDeliveredDurably(deliveryId)
                : plugin.diaryStore().cancelDeliveryDurably(deliveryId);
        future.whenComplete((changed, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (failure == null && Boolean.TRUE.equals(changed)) {
                    player.sendMessage(delivered
                            ? "§aDelivery marked delivered."
                            : "§aDelivery cancelled.");
                } else {
                    player.sendMessage("§cDelivery update failed and was not confirmed durable.");
                }
                openDeliveries(player, fresh(record), 0);
            });
        });
    }

    private void cancelAllOpenDeliveries(Player player, TrackedDiaryRecord record) {
        List<DeliveryEntry> open = deliveriesFor(record).stream()
                .filter(entry -> entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED)
                .toList();
        if (open.isEmpty()) {
            player.sendMessage("§eThere are no open deliveries for this diary.");
            openDiaryMenu(player, record);
            return;
        }

        List<CompletableFuture<Boolean>> futures = open.stream()
                .map(entry -> plugin.diaryStore().cancelDeliveryDurably(entry.delivery().token()))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                long cancelled = futures.stream()
                        .filter(CompletableFuture::isDone)
                        .filter(future -> !future.isCompletedExceptionally())
                        .map(CompletableFuture::join)
                        .filter(Boolean.TRUE::equals)
                        .count();
                if (failure == null) {
                    player.sendMessage("§aCancelled " + cancelled + " open delivery record(s).");
                } else {
                    player.sendMessage("§eSome delivery cancellations failed. §7Cancelled " + cancelled + " of " + open.size() + ".");
                }
                openDeliveries(player, fresh(record), 0);
            });
        });
    }

    private void openPurges(Player player, TrackedDiaryRecord record, int requestedPage) {
        List<PurgeOperation> operations = purgeOperations(record);
        int page = clampPage(requestedPage, operations.size());
        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.PURGES, page, null, null),
                54,
                "Purge History • " + ownerName(record)
        );

        int start = page * LIST_PAGE_SIZE;
        int end = Math.min(start + LIST_PAGE_SIZE, operations.size());
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, purgeItem(operations.get(i), false));
        }
        if (operations.isEmpty()) {
            inventory.setItem(22, button(Material.LIME_DYE,
                    title("No Purge Operations", NamedTextColor.GREEN),
                    List.of(text("This diary has no retained purge history.", NamedTextColor.GRAY))));
        }

        addListNavigation(inventory, page, operations.size(), "Back to Diary");
        player.openInventory(inventory);
    }

    private void handlePurgeListClick(Player player, Holder holder, int slot) {
        TrackedDiaryRecord record = fresh(holder.record());
        List<PurgeOperation> operations = purgeOperations(record);
        if (slot == 45 && holder.page() > 0) {
            openPurges(player, record, holder.page() - 1);
            return;
        }
        if (slot == 49) {
            openDiaryMenu(player, record);
            return;
        }
        if (slot == 53 && (holder.page() + 1) * LIST_PAGE_SIZE < operations.size()) {
            openPurges(player, record, holder.page() + 1);
            return;
        }
        if (slot >= LIST_PAGE_SIZE) {
            return;
        }
        int index = holder.page() * LIST_PAGE_SIZE + slot;
        if (index >= 0 && index < operations.size()) {
            openPurgeDetail(player, record, operations.get(index).operationId());
        }
    }

    private void openPurgeDetail(Player player, TrackedDiaryRecord record, UUID operationId) {
        PurgeOperation operation = operationId == null ? null : plugin.diaryStore().getPurgeOperation(operationId);
        if (operation == null || !record.diaryId().equals(operation.diaryId())) {
            player.sendMessage("§eThat purge operation no longer exists.");
            openPurges(player, record, 0);
            return;
        }

        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.PURGE_DETAIL, 0, operationId, null),
                45,
                "Purge • " + shortId(operationId)
        );
        addFrame(inventory);
        inventory.setItem(13, purgeItem(operation, false));

        if (!operation.terminal() && !operation.restorationOccurred()) {
            inventory.setItem(20, button(Material.CLOCK,
                    title("Resume / Recheck", NamedTextColor.YELLOW),
                    List.of(
                            text("Requeues remaining purge work.", NamedTextColor.GRAY),
                            text("For PARTIAL state, this may confirm partial restore", NamedTextColor.GRAY),
                            text("if your server config allows it.", NamedTextColor.GRAY),
                            click("Click to resume")
                    )));
            inventory.setItem(24, button(Material.RED_CONCRETE,
                    title("Cancel Purge", NamedTextColor.RED),
                    List.of(
                            text("Stops the operation without restoring a copy.", NamedTextColor.GRAY),
                            click("Click to review")
                    )));
        } else if ((operation.state() == PurgeState.CANCELLED || operation.state() == PurgeState.FAILED)
                && !operation.restorationOccurred()) {
            inventory.setItem(22, button(Material.CLOCK,
                    title("Resume " + pretty(operation.state().name()) + " Purge", NamedTextColor.YELLOW),
                    List.of(text("Restarts this retained operation.", NamedTextColor.GRAY), click("Click to resume"))));
        }

        inventory.setItem(31, backButton("Back to Purge History"));
        inventory.setItem(40, button(Material.SUNFLOWER,
                title("Refresh", NamedTextColor.YELLOW),
                List.of(click("Click to reload operation state"))));
        player.openInventory(inventory);
    }

    private void handlePurgeDetailClick(Player player, Holder holder, int slot) {
        TrackedDiaryRecord record = fresh(holder.record());
        UUID operationId = holder.selectedId();
        PurgeOperation operation = operationId == null ? null : plugin.diaryStore().getPurgeOperation(operationId);
        if (slot == 31) {
            openPurges(player, record, 0);
            return;
        }
        if (slot == 40) {
            openPurgeDetail(player, record, operationId);
            return;
        }
        if (operation == null) {
            return;
        }
        if (slot == 20 || (slot == 22
                && (operation.state() == PurgeState.CANCELLED || operation.state() == PurgeState.FAILED))) {
            boolean changed = plugin.diaryPurgeService().resume(operationId, player.getName());
            player.sendMessage(changed ? "§aPurge requeued/resumed." : "§cThat purge cannot be resumed from its current state.");
            openPurgeDetail(player, record, operationId);
            return;
        }
        if (slot == 24 && !operation.terminal() && !operation.restorationOccurred()) {
            openConfirmation(player, record, Action.PURGE_CANCEL, operationId);
        }
    }

    private void cancelPurge(Player player, TrackedDiaryRecord record, UUID operationId) {
        if (operationId == null) {
            openPurges(player, record, 0);
            return;
        }
        boolean changed = plugin.diaryPurgeService().cancel(operationId, player.getName());
        player.sendMessage(changed ? "§aPurge cancelled." : "§cThat purge can no longer be cancelled.");
        openPurgeDetail(player, fresh(record), operationId);
    }

    private void openLocations(Player player, TrackedDiaryRecord record, int requestedPage) {
        List<DiaryLocationRecord> locations = new ArrayList<>(record.locations());
        locations.sort(Comparator
                .comparing(DiaryLocationRecord::active).reversed()
                .thenComparing(DiaryLocationRecord::lastSeenAtEpochSeconds, Comparator.reverseOrder()));
        int page = clampPage(requestedPage, locations.size());

        Inventory inventory = Bukkit.createInventory(
                new Holder(record, Screen.LOCATIONS, page, null, null),
                54,
                "Diary Locations • " + ownerName(record)
        );
        int start = page * LIST_PAGE_SIZE;
        int end = Math.min(start + LIST_PAGE_SIZE, locations.size());
        for (int i = start; i < end; i++) {
            inventory.setItem(i - start, locationItem(locations.get(i)));
        }
        if (locations.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER,
                    title("No Tracked Locations", NamedTextColor.GRAY),
                    List.of(text("No location observations are currently retained.", NamedTextColor.GRAY))));
        }
        addListNavigation(inventory, page, locations.size(), "Back to Diary");
        player.openInventory(inventory);
    }

    private void handleLocationClick(Player player, Holder holder, int slot) {
        TrackedDiaryRecord record = fresh(holder.record());
        int total = record.locations().size();
        if (slot == 45 && holder.page() > 0) {
            openLocations(player, record, holder.page() - 1);
        } else if (slot == 49) {
            openDiaryMenu(player, record);
        } else if (slot == 53 && (holder.page() + 1) * LIST_PAGE_SIZE < total) {
            openLocations(player, record, holder.page() + 1);
        }
    }

    private ItemStack deliveryItem(DeliveryEntry entry) {
        DeliveryLifecycle lifecycle = entry.delivery().lifecycle();
        Material material = switch (lifecycle) {
            case QUEUED -> Material.CHEST_MINECART;
            case CLAIMED -> Material.HOPPER_MINECART;
            case RELEASE_PENDING -> Material.CLOCK;
            case DELIVERED -> Material.LIME_DYE;
        };
        return button(material,
                title(pretty(lifecycle.name()) + " • " + shortId(entry.delivery().token()), stateColor(lifecycle)),
                deliveryLore(entry));
    }

    private List<Component> deliveryLore(DeliveryEntry entry) {
        List<Component> lore = new ArrayList<>();
        OfflinePlayer recipient = Bukkit.getOfflinePlayer(entry.playerId());
        String recipientName = recipient.getName() == null ? "unknown" : recipient.getName();
        lore.add(kv("Recipient", recipientName));
        lore.add(kv("Recipient UUID", shortId(entry.playerId())));
        lore.add(kv("Reason", pretty(entry.delivery().reason().name())));
        lore.add(kv("State", pretty(entry.delivery().lifecycle().name())));
        lore.add(kv("Created", age(entry.delivery().createdAt())));
        if (entry.delivery().claimedAt() > 0L) {
            lore.add(kv("Claimed", age(entry.delivery().claimedAt())));
        }
        if (entry.delivery().lastPersistenceError() != null) {
            lore.add(blank());
            lore.add(text("Last persistence error:", NamedTextColor.RED));
            lore.add(text(trim(entry.delivery().lastPersistenceError(), 46), NamedTextColor.GRAY));
        }
        lore.add(blank());
        lore.add(click("Click for delivery controls"));
        return lore;
    }

    private ItemStack purgeItem(PurgeOperation operation, boolean activeCard) {
        Material material = switch (operation.state()) {
            case COMPLETED, RESTORED -> Material.LIME_DYE;
            case CANCELLED -> Material.GRAY_DYE;
            case FAILED, PARTIAL -> Material.REDSTONE;
            default -> Material.CLOCK;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(kv("Operation", shortId(operation.operationId())));
        lore.add(kv("State", pretty(operation.state().name())));
        lore.add(kv("Destination", pretty(operation.destination().name())));
        lore.add(kv("Started", age(operation.startedAt())));
        lore.add(kv("Pending players", operation.pendingPlayers().size()));
        lore.add(kv("Pending chunks", operation.pendingChunks()));
        lore.add(kv("Copies removed", operation.totalRemoved()));
        lore.add(kv("Queued copies removed", operation.pendingDeliveriesRemoved()));
        if (operation.restorationOccurred()) {
            lore.add(text("Replacement delivery has occurred.", NamedTextColor.GREEN));
        }
        if (!operation.errors().isEmpty()) {
            lore.add(blank());
            lore.add(text("Recent errors:", NamedTextColor.RED));
            operation.errors().stream().limit(3)
                    .forEach(error -> lore.add(text("• " + trim(error, 43), NamedTextColor.GRAY)));
        }
        lore.add(blank());
        lore.add(click(activeCard ? "Click to manage active purge" : "Click for purge controls"));
        return button(material,
                title((activeCard ? "Active Purge • " : "Purge • ") + pretty(operation.state().name()), stateColor(operation.state())),
                lore);
    }

    private ItemStack locationItem(DiaryLocationRecord location) {
        Material material = location.active() ? Material.COMPASS : Material.PAPER;
        List<Component> lore = new ArrayList<>();
        lore.add(kv("Type", pretty(location.type().name())));
        lore.add(kv("Status", location.active() ? "Active" : "Historical"));
        if (location.holderName() != null) {
            lore.add(kv("Holder", location.holderName()));
        }
        if (location.worldName() != null) {
            lore.add(kv("World", location.worldName()));
        }
        if (location.x() != null && location.y() != null && location.z() != null) {
            lore.add(kv("Coordinates", location.x() + ", " + location.y() + ", " + location.z()));
        }
        if (location.inventoryScope() != null) {
            lore.add(kv("Inventory", location.inventoryScope()));
        }
        if (location.slot() != null) {
            lore.add(kv("Slot", location.slot()));
        }
        lore.add(kv("Last seen", age(location.lastSeenAtEpochSeconds())));
        return button(material,
                title(trim(location.description(), 44), location.active() ? NamedTextColor.AQUA : NamedTextColor.GRAY),
                lore);
    }

    private List<DeliveryEntry> deliveriesFor(TrackedDiaryRecord record) {
        return plugin.diaryStore().getDeliveryEntries().stream()
                .filter(entry -> record.diaryId().equals(plugin.diaryService().getDiaryId(entry.delivery().item())))
                .sorted(Comparator
                        .comparing((DeliveryEntry entry) -> entry.delivery().lifecycle() == DeliveryLifecycle.DELIVERED)
                        .thenComparing((DeliveryEntry entry) -> entry.delivery().createdAt(), Comparator.reverseOrder()))
                .toList();
    }

    private long openDeliveryCount(TrackedDiaryRecord record) {
        return deliveriesFor(record).stream()
                .filter(entry -> entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED)
                .count();
    }

    private List<PurgeOperation> purgeOperations(TrackedDiaryRecord record) {
        return plugin.diaryStore().getPurgeOperationsForDiary(record.diaryId()).stream()
                .sorted(Comparator.comparing(PurgeOperation::startedAt).reversed())
                .toList();
    }

    private void addListNavigation(Inventory inventory, int page, int totalItems, String backText) {
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, filler());
        }
        if (page > 0) {
            inventory.setItem(45, button(Material.ARROW,
                    title("Previous Page", NamedTextColor.YELLOW),
                    List.of(click("Click to go back"))));
        }
        int pages = Math.max(1, (int) Math.ceil(totalItems / (double) LIST_PAGE_SIZE));
        inventory.setItem(47, button(Material.PAPER,
                title("Page " + (page + 1) + " / " + pages, NamedTextColor.WHITE),
                List.of(kv("Entries", totalItems))));
        inventory.setItem(49, backButton(backText));
        if ((page + 1) * LIST_PAGE_SIZE < totalItems) {
            inventory.setItem(53, button(Material.ARROW,
                    title("Next Page", NamedTextColor.YELLOW),
                    List.of(click("Click to continue"))));
        }
    }

    private int clampPage(int requestedPage, int totalItems) {
        int lastPage = Math.max(0, (totalItems - 1) / LIST_PAGE_SIZE);
        return Math.max(0, Math.min(requestedPage, lastPage));
    }

    private TrackedDiaryRecord fresh(TrackedDiaryRecord record) {
        if (record == null) {
            return null;
        }
        TrackedDiaryRecord refreshed = plugin.diaryRestoreService().getTrackedDiary(record.diaryId());
        return refreshed == null ? record : refreshed;
    }

    private ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(name));
        meta.lore(lore.stream().map(this::noItalic).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack filler() {
        return button(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }

    private void addFrame(Inventory inventory) {
        int size = inventory.getSize();
        int rows = size / 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == rows - 1 || column == 0 || column == 8) {
                inventory.setItem(slot, filler());
            }
        }
    }

    private ItemStack backButton(String label) {
        return button(Material.ARROW,
                title(label, NamedTextColor.YELLOW),
                List.of(click("Click to return")));
    }

    private ItemStack closeButton() {
        return button(Material.BARRIER,
                title("Close", NamedTextColor.RED),
                List.of(click("Click to close")));
    }

    private Component title(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component kv(String key, Object value) {
        return Component.text(key + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component click(String value) {
        return Component.text("▶ " + value, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component blank() {
        return Component.empty().decoration(TextDecoration.ITALIC, false);
    }

    private Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private String ownerName(TrackedDiaryRecord record) {
        if (record.ownerName() != null && !record.ownerName().isBlank()) {
            return record.ownerName();
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(record.ownerUuid());
        return player.getName() == null ? shortId(record.ownerUuid()) : player.getName();
    }

    private String shortId(UUID id) {
        return id == null ? "none" : shortId(id.toString());
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "none";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String pretty(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return "Unknown";
        }
        String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private String age(long timestamp) {
        if (timestamp <= 0L) {
            return "never";
        }
        long seconds = Math.max(0L, Instant.now().getEpochSecond() - timestamp);
        if (seconds < 60L) {
            return seconds + "s ago";
        }
        if (seconds < 3600L) {
            return seconds / 60L + "m ago";
        }
        if (seconds < 86400L) {
            return seconds / 3600L + "h ago";
        }
        return seconds / 86400L + "d ago";
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)) + "…";
    }

    private NamedTextColor stateColor(DeliveryLifecycle lifecycle) {
        return switch (lifecycle) {
            case QUEUED -> NamedTextColor.YELLOW;
            case CLAIMED, RELEASE_PENDING -> NamedTextColor.GOLD;
            case DELIVERED -> NamedTextColor.GREEN;
        };
    }

    private NamedTextColor stateColor(PurgeState state) {
        return switch (state) {
            case COMPLETED, RESTORED -> NamedTextColor.GREEN;
            case CANCELLED -> NamedTextColor.GRAY;
            case FAILED, PARTIAL -> NamedTextColor.RED;
            default -> NamedTextColor.YELLOW;
        };
    }

    private record Holder(
            TrackedDiaryRecord record,
            Screen screen,
            int page,
            UUID selectedId,
            Action action
    ) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
