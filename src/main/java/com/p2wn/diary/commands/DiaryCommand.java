package com.p2wn.diary.commands;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.logic.DiaryService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

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
            sender.sendMessage(plugin.configManager().msg("admin.usage-header"));
            sender.sendMessage(plugin.configManager().msg("admin.usage-reload"));
            sender.sendMessage(plugin.configManager().msg("admin.usage-issue"));
            sender.sendMessage(plugin.configManager().msg("admin.usage-status"));
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPluginState();
                sender.sendMessage(plugin.configManager().msg("reload-complete"));
                yield true;
            }
            case "issue" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.configManager().msg("admin.issue-usage"));
                    yield true;
                }

                OfflinePlayer target = resolveOfflinePlayer(args[1]);
                DiaryService.IssueResult result = plugin.diaryService().issueDiary(target, args[1]);
                sender.sendMessage(plugin.diaryService().formatAdminSummary(result));
                yield true;
            }
            case "status" -> {
                if (args.length < 2) {
                    sender.sendMessage(plugin.configManager().msg("admin.status-usage"));
                    yield true;
                }
                OfflinePlayer target = resolveOfflinePlayer(args[1]);
                DiaryService.DiaryStatus status = plugin.diaryService().getStatus(target);
                for (String line : plugin.diaryService().formatStatus(target, status).split("\n")) {
                    sender.sendMessage(line);
                }
                yield true;
            }
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
            return partial(List.of("reload", "issue", "status"), args[0]);
        }
        if (args.length == 2 && ("issue".equalsIgnoreCase(args[0]) || "status".equalsIgnoreCase(args[0]))) {
            List<String> onlineNames = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> onlineNames.add(player.getName()));
            return partial(onlineNames, args[1]);
        }
        return List.of();
    }

    private OfflinePlayer resolveOfflinePlayer(String input) {
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ex) {
            return Bukkit.getOfflinePlayer(input);
        }
    }

    private List<String> partial(List<String> candidates, String input) {
        String needle = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(needle))
                .toList();
    }
}
