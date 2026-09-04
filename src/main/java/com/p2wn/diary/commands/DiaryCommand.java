package com.p2wn.diary.commands;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.PurgeOperation;
import com.p2wn.diary.data.DeliveryEntry;
import com.p2wn.diary.data.DeliveryLifecycle;
import com.p2wn.diary.data.IdentityResolution;
import com.p2wn.diary.logic.DiaryService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public final class DiaryCommand implements CommandExecutor, TabCompleter {

    private final DiaryPlugin plugin;

    public DiaryCommand(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("diary.admin")) {
            sender.sendMessage(plugin.configManager().msg("admin.no-permission"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPluginState();
                sender.sendMessage(plugin.configManager().msg("reload-complete"));
                yield true;
            }
            case "issue" -> handleIssue(sender, args);
            case "status" -> handleStatus(sender, args);
            case "find" -> handleFind(sender, args);
            case "restore" -> handleRestore(sender, args);
            case "purge" -> handlePurge(sender, args);
            case "deliveries" -> handleDeliveries(sender, args);
            case "importwelcome" -> handleImportWelcome(sender);
            case "scan" -> handleScan(sender, args);
            case "repair" -> handleRepair(sender);
            default -> {
                sender.sendMessage(plugin.configManager().msg("admin.unknown-subcommand"));
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("diary.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return partial(List.of("reload", "issue", "status", "find", "restore", "purge", "deliveries", "scan", "repair", "importwelcome"), args[0]);
        }
        if (args.length == 2 && "scan".equalsIgnoreCase(args[0])) {
            return partial(List.of("duplicates", "locations"), args[1]);
        }
        if (args.length == 2 && "purge".equalsIgnoreCase(args[0])) {
            return partial(List.of("status", "cancel", "resume", "list"), args[1]);
        }
        if (args.length == 3 && "restore".equalsIgnoreCase(args[0])) {
            return partial(List.of("owner", "admin", "duplicate"), args[2]);
        }
        if (args.length == 2 && List.of("issue", "status", "find", "restore").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> onlineNames = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> onlineNames.add(player.getName()));
            return partial(onlineNames, args[1]);
        }
        return List.of();
    }

    private boolean handleDeliveries(CommandSender sender, String[] args) {
        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            String filter = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "open";
            int page = args.length >= 4 ? Math.max(1, parsePage(args[3])) : 1;
            plugin.diaryStore().getDeliveryEntries().stream()
                    .filter(entry -> matchesDeliveryFilter(entry, filter))
                    .sorted((left, right) -> deliverySortKey(left).compareTo(deliverySortKey(right)))
                    .skip((long) (page - 1) * 10).limit(10)
                    .forEach(entry -> sender.sendMessage(formatDelivery(entry)));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("Usage: /diary deliveries <list|status|resolve>");
            return true;
        }
        UUID deliveryId;
        try { deliveryId = UUID.fromString(args[2]); } catch (IllegalArgumentException ex) {
            sender.sendMessage("Invalid delivery ID."); return true;
        }
        DeliveryEntry entry = plugin.diaryStore().getDeliveryEntry(deliveryId);
        if (entry == null) { sender.sendMessage("Delivery not found."); return true; }
        if ("status".equalsIgnoreCase(args[1])) {
            sender.sendMessage(formatDelivery(entry));
            return true;
        }
        if (args.length < 4) { sender.sendMessage("Usage: /diary deliveries resolve <id> <retry|delivered|cancel>"); return true; }
        String deliveryAction = args[3].toLowerCase(Locale.ROOT);
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
        return true;
    }

    private String formatDelivery(DeliveryEntry entry) {
        long now = Instant.now().getEpochSecond();
        OfflinePlayer player = Bukkit.getOfflinePlayer(entry.playerId());
        String name = player.getName() == null ? "unknown" : player.getName();
        String diaryId = plugin.diaryService().getDiaryId(entry.delivery().item());
        String error = entry.delivery().lastPersistenceError() == null ? ""
                : " error=\"" + entry.delivery().lastPersistenceError() + "\"";
        return "§e" + entry.delivery().token() + " §f" + name + " " + entry.playerId()
                + " §7diary=" + diaryId + " reason=" + entry.delivery().reason()
                + " state=" + entry.delivery().lifecycle()
                + " created=" + age(now, entry.delivery().createdAt())
                + " claimed=" + age(now, entry.delivery().claimedAt()) + error;
    }

    private String age(long now, long timestamp) {
        if (timestamp <= 0L) return "never";
        long seconds = Math.max(0L, now - timestamp);
        if (seconds >= 86400L) return seconds / 86400L + "d";
        if (seconds >= 3600L) return seconds / 3600L + "h";
        if (seconds >= 60L) return seconds / 60L + "m";
        return seconds + "s";
    }

    private int parsePage(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ex) { return 1; }
    }

    private boolean matchesDeliveryFilter(DeliveryEntry entry, String filter) {
        return switch (filter) {
            case "queued" -> entry.delivery().lifecycle() == DeliveryLifecycle.QUEUED;
            case "claimed" -> entry.delivery().lifecycle() == DeliveryLifecycle.CLAIMED
                    || entry.delivery().lifecycle() == DeliveryLifecycle.RELEASE_PENDING;
            case "delivered" -> entry.delivery().lifecycle() == DeliveryLifecycle.DELIVERED;
            case "all" -> true;
            default -> entry.delivery().lifecycle() != DeliveryLifecycle.DELIVERED;
        };
    }

    private String deliverySortKey(DeliveryEntry entry) {
        boolean claimed = entry.delivery().lifecycle() == DeliveryLifecycle.CLAIMED
                || entry.delivery().lifecycle() == DeliveryLifecycle.RELEASE_PENDING;
        long when = claimed ? entry.delivery().claimedAt() : entry.delivery().createdAt();
        return (claimed ? "0" : "1")
                + String.format(Locale.ROOT, "%020d", when) + entry.delivery().token();
    }

    private boolean handleIssue(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.configManager().msg("admin.issue-usage"));
            return true;
        }

        OfflinePlayer target = resolveIssuePlayer(args[1]);
        if (target == null) {
            sender.sendMessage("Unknown or ambiguous player; use an exact UUID or known player name.");
            return true;
        }
        DiaryService.IssueResult result = plugin.diaryService().issueDiary(target, args[1]);
        sender.sendMessage(plugin.diaryService().formatAdminSummary(result));
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.configManager().msg("admin.status-usage"));
            return true;
        }
        OfflinePlayer target = resolveOfflinePlayer(args[1]);
        if (target == null) {
            sender.sendMessage("Unknown or ambiguous offline player; use UUID or diary ID.");
            return true;
        }
        DiaryService.DiaryStatus status = plugin.diaryService().getStatus(target);
        for (String line : plugin.diaryService().formatStatus(target, status).split("\n")) {
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean handleFind(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.configManager().msg("find.usage"));
            return true;
        }

        String diaryId = resolveDiaryId(args[1]);
        if (diaryId == null) {
            sender.sendMessage(plugin.configManager().msg("find.not-found"));
            return true;
        }

        TrackedDiaryRecord record = plugin.diaryRestoreService().getTrackedDiary(diaryId);
        if (record == null) {
            sender.sendMessage(plugin.configManager().msg("find.not-found"));
            return true;
        }

        OfflinePlayer owner = Bukkit.getOfflinePlayer(record.ownerUuid());
        String ownerName = owner.getName() != null ? owner.getName() : record.ownerUuid().toString();
        sender.sendMessage(plugin.configManager().msg("find.result-owner", java.util.Map.of("player", ownerName)));
        sender.sendMessage(plugin.configManager().msg("find.result-id", java.util.Map.of("id", diaryId)));
        sender.sendMessage(plugin.configManager().msg("find.result-location", java.util.Map.of(
                "location",
                record.lastKnownLocation() == null ? "unknown" : record.lastKnownLocation().description()
        )));
        return true;
    }

    private boolean handleRestore(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.configManager().msg("restore.usage"));
            return true;
        }

        String diaryId = resolveDiaryId(args[1]);
        if (diaryId == null) {
            sender.sendMessage(plugin.configManager().msg("restore.not-found"));
            return true;
        }

        TrackedDiaryRecord record = plugin.diaryRestoreService().getTrackedDiary(diaryId);
        if (record == null || record.snapshot() == null) {
            sender.sendMessage(plugin.configManager().msg("restore.not-found"));
            return true;
        }

        if (args.length == 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.configManager().msg("restore.console-explicit"));
                return true;
            }
            plugin.restoreGuiListener().openRestoreGui(player, record);
            sender.sendMessage(plugin.configManager().msg("restore.gui-opened"));
            return true;
        }

        String mode = args[2].toLowerCase(Locale.ROOT);
        if ("duplicate".equals(mode)) {
            boolean queued = plugin.diaryPurgeService().restoreDuplicate(
                    record, sender instanceof Player player ? player : null);
            sender.sendMessage(plugin.configManager().msg(
                    queued ? "restore.duplicate-started" : "restore.duplicate-blocked"));
            return true;
        }
        if ("admin".equals(mode) && !(sender instanceof Player)) {
            sender.sendMessage(plugin.configManager().msg("restore.admin-player-only"));
            return true;
        }
        if (!"owner".equals(mode) && !"admin".equals(mode)) {
            sender.sendMessage(plugin.configManager().msg("restore.usage"));
            return true;
        }
        PurgeDestination destination = "owner".equals(mode) ? PurgeDestination.OWNER : PurgeDestination.ADMIN;
        PurgeOperation operation = plugin.diaryPurgeService().begin(record, destination,
                sender instanceof Player player ? player : null);
        sendOperationStarted(sender, operation, destination, sender instanceof Player player ? player.getUniqueId() : null);
        return true;
    }

    private boolean handlePurge(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.configManager().msg("purge.usage"));
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<PurgeOperation> operations = plugin.diaryStore().getPurgeOperations();
                if (operations.isEmpty()) {
                    sender.sendMessage(plugin.configManager().msg("purge.none"));
                }
                operations.stream()
                        .sorted((left, right) -> Long.compare(right.startedAt(), left.startedAt()))
                        .limit(20)
                        .forEach(operation -> sender.sendMessage(shortOperation(operation)));
                yield true;
            }
            case "status" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.configManager().msg("purge.status-usage"));
                    yield true;
                }
                PurgeOperation operation = plugin.diaryPurgeService().find(args[2]);
                if (operation == null) {
                    sender.sendMessage(plugin.configManager().msg("purge.not-found"));
                } else {
                    sendOperationStatus(sender, operation);
                }
                yield true;
            }
            case "cancel", "resume" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.configManager().msg("purge.control-usage"));
                    yield true;
                }
                UUID operationId;
                try {
                    operationId = UUID.fromString(args[2]);
                } catch (IllegalArgumentException ex) {
                    sender.sendMessage(plugin.configManager().msg("purge.not-found"));
                    yield true;
                }
                boolean changed = "cancel".equalsIgnoreCase(args[1])
                        ? plugin.diaryPurgeService().cancel(operationId, sender.getName())
                        : plugin.diaryPurgeService().resume(operationId, sender.getName());
                sender.sendMessage(plugin.configManager().msg(changed ? "purge.control-success" : "purge.control-failed"));
                yield true;
            }
            default -> {
                String diaryId = resolveDiaryId(args[1]);
                TrackedDiaryRecord record = diaryId == null ? null : plugin.diaryRestoreService().getTrackedDiary(diaryId);
                if (record == null || record.snapshot() == null) {
                    sender.sendMessage(plugin.configManager().msg("restore.not-found"));
                    yield true;
                }
                PurgeOperation operation = plugin.diaryPurgeService().begin(record, PurgeDestination.NONE,
                        sender instanceof Player player ? player : null);
                sendOperationStarted(sender, operation, PurgeDestination.NONE,
                        sender instanceof Player player ? player.getUniqueId() : null);
                yield true;
            }
        };
    }

    private void sendOperationStarted(CommandSender sender, PurgeOperation operation, PurgeDestination requestedDestination,
                                      UUID requestedAdmin) {
        if (operation == null) {
            sender.sendMessage(plugin.configManager().msg("purge.start-failed"));
            return;
        }
        if (operation.destination() != requestedDestination || !java.util.Objects.equals(operation.adminUuid(), requestedAdmin)) {
            sender.sendMessage("§eNo new purge started. Existing operation §f" + operation.operationId()
                    + " §edestination=§f" + operation.destination() + " §estate=§f" + operation.state());
            return;
        }
        sender.sendMessage(plugin.configManager().msg("purge.started", java.util.Map.of(
                "operation", operation.operationId().toString(),
                "state", operation.state().name(),
                "players", Integer.toString(operation.pendingPlayers().size()),
                "chunks", Integer.toString(operation.pendingChunks())
        )));
    }

    private void sendOperationStatus(CommandSender sender, PurgeOperation operation) {
        OfflinePlayer owner = operation.ownerUuid() == null ? null : Bukkit.getOfflinePlayer(operation.ownerUuid());
        sender.sendMessage("§eOperation: §f" + operation.operationId());
        sender.sendMessage("§eDiary ID: §f" + operation.diaryId());
        sender.sendMessage("§eOwner: §f" + (owner == null ? "unknown" :
                owner.getName() == null ? owner.getUniqueId() : owner.getName()));
        sender.sendMessage("§eDestination: §f" + operation.destination());
        sender.sendMessage("§eState: §f" + operation.state());
        sender.sendMessage("§eCopies removed: §f" + operation.totalRemoved() + " " + operation.removedByLocation());
        sender.sendMessage("§eOnline inventories scanned: §f" + operation.onlinePlayersScanned());
        sender.sendMessage("§eOffline players pending: §f" + operation.pendingPlayers().size());
        sender.sendMessage("§eLoaded/known chunks scanned: §f" + operation.loadedChunksScanned());
        sender.sendMessage("§eKnown chunks pending: §f" + operation.pendingChunks());
        sender.sendMessage("§ePending deliveries removed: §f" + operation.pendingDeliveriesRemoved());
        sender.sendMessage("§eErrors: §f" + operation.errors().size()
                + (operation.errors().isEmpty() ? "" : " " + operation.errors()));
        sender.sendMessage("§eReplacement restored: §f" + operation.restorationOccurred());
        sender.sendMessage("§eReplacement holder: §f" +
                (operation.replacementHolder() == null ? "none" : operation.replacementHolder()));
    }

    private String shortOperation(PurgeOperation operation) {
        return "§e" + operation.operationId() + " §f" + operation.diaryId()
                + " §7" + operation.state() + " removed=" + operation.totalRemoved();
    }

    private OfflinePlayer resolveOfflinePlayer(String input) {
        String diaryId = plugin.diaryStore().findDiaryIdByExactOrPrefix(input);
        if (diaryId != null) {
            TrackedDiaryRecord tracked = plugin.diaryStore().getTrackedDiary(diaryId);
            if (tracked != null && tracked.ownerUuid() != null) {
                return Bukkit.getOfflinePlayer(tracked.ownerUuid());
            }
        }
        if (plugin.diaryStore().isAmbiguousDiaryIdPrefix(input)) return null;
        IdentityResolution stored = plugin.diaryStore().resolveStoredPlayer(input);
        if (stored.status() == IdentityResolution.Status.FOUND) {
            return Bukkit.getOfflinePlayer(stored.uuid());
        }
        if (stored.status() == IdentityResolution.Status.AMBIGUOUS) {
            return null;
        }
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ex) {
            return Bukkit.getPlayerExact(input);
        }
    }

    private OfflinePlayer resolveIssuePlayer(String input) {
        IdentityResolution stored = plugin.diaryStore().resolveStoredPlayer(input);
        if (stored.status() == IdentityResolution.Status.FOUND) {
            return Bukkit.getOfflinePlayer(stored.uuid());
        }
        if (stored.status() == IdentityResolution.Status.AMBIGUOUS) return null;
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return online;
        for (OfflinePlayer cached : Bukkit.getOfflinePlayers()) {
            if (cached.getName() != null && cached.getName().equalsIgnoreCase(input)
                    && cached.hasPlayedBefore()) {
                return cached;
            }
        }
        return null;
    }

    private String resolveDiaryId(String input) {
        String diaryId = plugin.diaryStore().findDiaryIdByExactOrPrefix(input);
        if (diaryId != null) {
            return diaryId;
        }
        if (plugin.diaryStore().isAmbiguousDiaryIdPrefix(input)) {
            return null;
        }

        OfflinePlayer target = resolveOfflinePlayer(input);
        return target == null ? null : plugin.diaryStore().findDiaryIdByOwner(target.getUniqueId());
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(plugin.configManager().msg("admin.usage-header"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-reload"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-issue"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-status"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-find"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-restore"));
        sender.sendMessage("/diary purge <player|diaryId>");
        sender.sendMessage("/diary purge <status|cancel|resume|list> [operation]");
        sender.sendMessage("/diary scan <duplicates|locations>");
        sender.sendMessage("/diary repair");
        sender.sendMessage("/diary importwelcome");
    }

    private List<String> partial(List<String> candidates, String input) {
        String needle = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(needle))
                .toList();
    }

    private boolean handleImportWelcome(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getItemMeta() == null) {
            sender.sendMessage("Hold the book you want to import.");
            return true;
        }
        if (plugin.welcomeBookItem().importTemplate(stack)) {
            sender.sendMessage("Imported welcome book template from your held book.");
            return true;
        }
        sender.sendMessage("Failed to import the held book.");
        return true;
    }

    private boolean handleScan(CommandSender sender, String[] args) {
        if (args.length < 2 || (!"duplicates".equalsIgnoreCase(args[1]) && !"locations".equalsIgnoreCase(args[1]))) {
            sender.sendMessage("Usage: /diary scan <duplicates|locations>");
            return true;
        }
        plugin.duplicateWatcher().queueGlobalScan();
        sender.sendMessage("Queued a staggered diary " + args[1].toLowerCase(Locale.ROOT) + " scan.");
        return true;
    }

    private boolean handleRepair(CommandSender sender) {
        plugin.duplicateWatcher().queueRepairScan();
        sender.sendMessage("Queued a staggered duplicate scan; confirmed duplicates will start purge-and-owner-restore operations.");
        return true;
    }
}
