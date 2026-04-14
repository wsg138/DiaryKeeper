package com.p2wn.diary.commands;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.TrackedDiaryRecord;
import com.p2wn.diary.logic.DiaryService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
            return partial(List.of("reload", "issue", "status", "find", "restore"), args[0]);
        }
        if (args.length == 2 && List.of("issue", "status", "find", "restore").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> onlineNames = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> onlineNames.add(player.getName()));
            return partial(onlineNames, args[1]);
        }
        return List.of();
    }

    private boolean handleIssue(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.configManager().msg("admin.issue-usage"));
            return true;
        }

        OfflinePlayer target = resolveOfflinePlayer(args[1]);
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

        if (record.lastKnownLocation() != null) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.configManager().msg("restore.player-only-confirm"));
                return true;
            }
            plugin.restoreGuiListener().openRestoreGui(player, record);
            sender.sendMessage(plugin.configManager().msg("restore.gui-opened"));
            return true;
        }

        plugin.diaryRestoreService().restoreDiaryToOwner(record, true);
        sender.sendMessage(plugin.configManager().msg("restore.direct-success"));
        return true;
    }

    private OfflinePlayer resolveOfflinePlayer(String input) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ex) {
            return Bukkit.getOfflinePlayer(input);
        }
    }

    private String resolveDiaryId(String input) {
        String diaryId = plugin.diaryStore().findDiaryIdByExactOrPrefix(input);
        if (diaryId != null) {
            return diaryId;
        }

        OfflinePlayer target = resolveOfflinePlayer(input);
        return target.getUniqueId() == null ? null : plugin.diaryStore().findDiaryIdByOwner(target.getUniqueId());
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(plugin.configManager().msg("admin.usage-header"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-reload"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-issue"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-status"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-find"));
        sender.sendMessage(plugin.configManager().msg("admin.usage-restore"));
    }

    private List<String> partial(List<String> candidates, String input) {
        String needle = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(needle))
                .toList();
    }
}
