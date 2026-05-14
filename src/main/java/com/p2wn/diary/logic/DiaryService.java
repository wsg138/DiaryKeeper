package com.p2wn.diary.logic;

import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DeliveryReason;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.events.DiaryFilledEvent;
import com.p2wn.diary.events.DiaryObtainedEvent;
import com.p2wn.diary.events.DiaryReceivedEvent;
import com.p2wn.diary.events.DiarySignedEvent;
import com.p2wn.diary.events.DiaryVoidReturnEvent;
import com.p2wn.diary.item.DiaryItem;
import com.p2wn.diary.item.WelcomeBookItem;
import com.p2wn.diary.util.DiaryTextValidator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DiaryService {

    public enum IssueResultType {
        ISSUED_DIRECT,
        QUEUED_FOR_DELIVERY,
        ALREADY_ISSUED,
        INVALID_TARGET
    }

    public record IssueResult(IssueResultType type, UUID playerId, String playerName) {}

    public record DiaryStatus(boolean issued, String diaryId, long issuedAt, int queuedDeliveries) {}

    private final ConfigManager configManager;
    private final DiaryStore diaryStore;
    private final DiaryItem diaryItem;
    private final WelcomeBookItem welcomeBookItem;
    private final DeliveryService deliveryService;
    private final Plugin plugin;
    private DiaryTrackerService trackerService;
    private DiaryRestoreService restoreService;
    private DuplicateWatcher duplicateWatcher;
    private DiaryAnalyticsStore analyticsStore;

    public DiaryService(Plugin plugin, ConfigManager configManager, DiaryStore diaryStore, DiaryItem diaryItem, WelcomeBookItem welcomeBookItem, DeliveryService deliveryService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.diaryStore = diaryStore;
        this.diaryItem = diaryItem;
        this.welcomeBookItem = welcomeBookItem;
        this.deliveryService = deliveryService;
    }

    public void setDuplicateWatcher(DuplicateWatcher duplicateWatcher) {
        this.duplicateWatcher = duplicateWatcher;
    }

    public void setTrackerService(DiaryTrackerService trackerService) {
        this.trackerService = trackerService;
    }

    public void setRestoreService(DiaryRestoreService restoreService) {
        this.restoreService = restoreService;
    }

    public void setAnalyticsStore(DiaryAnalyticsStore analyticsStore) {
        this.analyticsStore = analyticsStore;
    }

    public boolean isDiary(ItemStack stack) {
        return diaryItem.isDiary(stack);
    }

    public UUID getOwner(ItemStack stack) {
        return diaryItem.getOwner(stack);
    }

    public String getDiaryId(ItemStack stack) {
        return diaryItem.getDiaryId(stack);
    }

    public void canonicalize(ItemStack stack) {
        diaryItem.canonicalize(stack);
    }

    public void refreshOwnerCosmetics(Player player, ItemStack stack) {
        diaryItem.refreshOwnerCosmetics(player.getUniqueId(), player.getName(), stack);
    }

    public void tagLastDropper(ItemStack stack, UUID playerId) {
        diaryItem.setLastDropper(stack, playerId);
    }

    public UUID getLastDropper(ItemStack stack) {
        return diaryItem.getLastDropper(stack);
    }

    public void handlePlayerJoin(Player player) {
        if (restoreService != null) {
            restoreService.processPendingRemovals(player);
        }
        refreshOwnedDiaries(player);
        if (trackerService != null) {
            trackerService.trackPlayerInventory(player);
            trackerService.trackEnderChest(player);
        }
        alertStaffIfDiaryInEnderChest(player);

        if (configManager.cfg().getBoolean("give-on-first-join", true)
                && (!diaryStore.hasIssued(player.getUniqueId()) || diaryStore.getDiaryId(player.getUniqueId()) == null)) {
            ItemStack diary = diaryItem.createDiary(player.getUniqueId(), player.getName());
            Bukkit.getPluginManager().callEvent(new DiaryReceivedEvent(player, diary.clone()));
            deliverOrQueue(player, DeliveryReason.INITIAL_ISSUE, diary);
            deliverOrDrop(player, welcomeBookItem.createWelcomeBook());
            plugin.getLogger().info("Issued first-join diary and welcome guide to " + player.getName() + ".");
            diaryStore.markIssued(player.getUniqueId());
            diaryStore.flushNow();
        }

        deliveryService.requestDelivery(player.getUniqueId());

        if (duplicateWatcher != null) {
            duplicateWatcher.refreshPlayerSnapshot(player);
            duplicateWatcher.onPlayerJoinInventory(player);
        }
    }

    public IssueResult issueDiary(OfflinePlayer target, String requestedName) {
        if (target == null || target.getUniqueId() == null) {
            return new IssueResult(IssueResultType.INVALID_TARGET, null, requestedName);
        }

        UUID playerId = target.getUniqueId();
        String targetName = resolveTargetName(target, requestedName);
        if (diaryStore.hasIssued(playerId) && diaryStore.getDiaryId(playerId) != null) {
            return new IssueResult(IssueResultType.ALREADY_ISSUED, playerId, targetName);
        }

        ItemStack diary = diaryItem.createDiary(playerId, targetName);
        diaryStore.markIssued(playerId);

        Player online = target.getPlayer();
        IssueResultType resultType;
        if (online != null && online.isOnline()) {
            resultType = deliverOrQueue(online, DeliveryReason.ADMIN_ISSUE, diary)
                    ? IssueResultType.ISSUED_DIRECT
                    : IssueResultType.QUEUED_FOR_DELIVERY;
        } else {
            deliveryService.queue(playerId, DeliveryReason.ADMIN_ISSUE, diary);
            resultType = IssueResultType.QUEUED_FOR_DELIVERY;
        }
        if (analyticsStore != null) {
            analyticsStore.record(DiaryAnalyticsEventType.ADMIN_ISSUE, playerId, targetName, diaryItem.getDiaryId(diary), resultType.name());
        }
        diaryStore.flushNow();

        return new IssueResult(resultType, playerId, targetName);
    }

    public DiaryStatus getStatus(OfflinePlayer target) {
        UUID playerId = target.getUniqueId();
        return new DiaryStatus(
                diaryStore.hasIssued(playerId),
                diaryStore.getDiaryId(playerId),
                diaryStore.getIssuedAt(playerId),
                diaryStore.getPendingDeliveryCount(playerId)
        );
    }

    public boolean handleEdit(PlayerEditBookEvent event) {
        if (event.getSlot() < 0) {
            return false;
        }

        ItemStack item = event.getPlayer().getInventory().getItem(event.getSlot());
        if (!diaryItem.isDiary(item)) {
            return false;
        }

        if (event.isSigning()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(configManager.msg("signing-disabled"));
            return true;
        }

        if (configManager.cfg().getBoolean("owner-only-edit", true)) {
            UUID ownerId = diaryItem.getOwner(item);
            if (ownerId == null || !ownerId.equals(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(configManager.msg("non-owner-edit"));
                return true;
            }
        }

        BookMeta newMeta = event.getNewBookMeta();
        if (!DiaryTextValidator.isAllowedBookText(newMeta.getTitle())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(configManager.msg("ascii-only"));
            return true;
        }

        for (String page : newMeta.getPages()) {
            if (!DiaryTextValidator.isAllowedBookText(page)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(configManager.msg("ascii-only"));
                return true;
            }
        }

        if (event.isSigning()) {
            Bukkit.getPluginManager().callEvent(new DiarySignedEvent(event.getPlayer(), item.clone(), newMeta.clone()));
        } else {
            Bukkit.getPluginManager().callEvent(new DiaryFilledEvent(event.getPlayer(), item.clone(), newMeta.clone()));
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (trackerService != null) {
                trackerService.trackPlayerInventory(event.getPlayer());
            }
            if (duplicateWatcher != null) {
                duplicateWatcher.refreshPlayerSnapshot(event.getPlayer());
            }
        }, 1L);
        return true;
    }

    public void handleDiaryObtained(Player player, ItemStack stack) {
        Bukkit.getPluginManager().callEvent(new DiaryObtainedEvent(player, stack.clone()));
        if (trackerService != null) {
            trackerService.trackPlayerInventory(player);
        }
        if (duplicateWatcher != null) {
            duplicateWatcher.refreshPlayerSnapshot(player);
        }
    }

    public void onVoidReturnDelivered(Player player, ItemStack stack) {
        Bukkit.getPluginManager().callEvent(new DiaryVoidReturnEvent(player, stack.clone()));
        player.sendMessage(configManager.msg("void-returned"));
        if (trackerService != null) {
            trackerService.trackPlayerInventory(player);
        }
        if (duplicateWatcher != null) {
            duplicateWatcher.refreshPlayerSnapshot(player);
        }
    }

    public void refreshOwnedDiaries(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (diaryItem.isDiary(stack) && player.getUniqueId().equals(diaryItem.getOwner(stack))) {
                diaryItem.refreshOwnerCosmetics(player.getUniqueId(), player.getName(), stack);
            }
        }
        if (trackerService != null) {
            trackerService.trackPlayerInventory(player);
        }
        if (duplicateWatcher != null) {
            duplicateWatcher.refreshPlayerSnapshot(player);
        }
    }

    public void canonicalizeInventory(ItemStack[] contents) {
        for (ItemStack stack : contents) {
            diaryItem.canonicalize(stack);
        }
    }

    public String formatAdminSummary(IssueResult result) {
        return switch (result.type()) {
            case INVALID_TARGET -> configManager.msg("admin.invalid-target");
            case ALREADY_ISSUED -> configManager.msg("admin.issue-already-issued", Map.of("player", result.playerName()));
            case ISSUED_DIRECT -> configManager.msg("admin.issue-success", Map.of("player", result.playerName()));
            case QUEUED_FOR_DELIVERY -> configManager.msg("admin.issue-queued", Map.of("player", result.playerName()));
        };
    }

    public String formatStatus(OfflinePlayer target, DiaryStatus status) {
        String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        return String.join("\n",
                configManager.msg("admin.status-player", Map.of("player", targetName)),
                configManager.msg("admin.status-issued", Map.of("issued", Boolean.toString(status.issued()).toLowerCase(Locale.ROOT))),
                configManager.msg("admin.status-diary-id", Map.of("id", status.diaryId() == null ? "none" : status.diaryId())),
                configManager.msg("admin.status-queued", Map.of("count", Integer.toString(status.queuedDeliveries())))
        );
    }

    private boolean deliverOrQueue(Player player, DeliveryReason reason, ItemStack diary) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(diary.clone());
        if (!leftovers.isEmpty()) {
            deliveryService.queue(player.getUniqueId(), reason, diary);
            return false;
        }
        if (duplicateWatcher != null) {
            duplicateWatcher.refreshPlayerSnapshot(player);
        }
        return true;
    }

    private void deliverOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item.clone());
        if (leftovers.isEmpty()) {
            plugin.getLogger().info("Delivered welcome guide to " + player.getName() + "'s inventory.");
        }
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            plugin.getLogger().info("Dropped welcome guide at " + player.getName() + "'s location because their inventory was full.");
        }
        if (duplicateWatcher != null) {
            duplicateWatcher.refreshPlayerSnapshot(player);
        }
    }

    private String resolveTargetName(OfflinePlayer target, String requestedName) {
        if (target.getName() != null && !target.getName().isBlank()) {
            return target.getName();
        }
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName;
        }
        return target.getUniqueId().toString();
    }

    private void alertStaffIfDiaryInEnderChest(Player player) {
        if (!configManager.cfg().getBoolean("alerts.enderchest-diary-on-join", true)) {
            return;
        }
        if (!containsDiary(player.getEnderChest().getContents(), 0)) {
            return;
        }
        String message = configManager.msg("staff.enderchest-diary-alert", Map.of("player", player.getName()));
        plugin.getLogger().warning("[Diary] " + player.getName() + " has a diary or diary-containing item in their ender chest.");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("diary.notify")) {
                online.sendMessage(message);
            }
        }
    }

    private boolean containsDiary(ItemStack[] contents, int depth) {
        if (contents == null) {
            return false;
        }
        for (ItemStack stack : contents) {
            if (containsDiary(stack, depth)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDiary(ItemStack stack, int depth) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        if (diaryItem.isDiary(stack)) {
            return true;
        }
        if (depth >= 2 || !stack.hasItemMeta()) {
            return false;
        }
        if (stack.getType() == Material.BUNDLE && stack.getItemMeta() instanceof BundleMeta bundleMeta) {
            for (ItemStack nested : bundleMeta.getItems()) {
                if (containsDiary(nested, depth + 1)) {
                    return true;
                }
            }
        }
        if (isShulkerBoxItem(stack)
                && stack.getItemMeta() instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            return containsDiary(shulkerBox.getInventory().getContents(), depth + 1);
        }
        return false;
    }

    private boolean isShulkerBoxItem(ItemStack stack) {
        return stack.getType().name().endsWith("SHULKER_BOX");
    }
}
