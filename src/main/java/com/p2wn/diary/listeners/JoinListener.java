package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinListener implements Listener {

    private final DiaryPlugin plugin;

    public JoinListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.diaryService().handlePlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.duplicateWatcher().removePlayerSnapshot(event.getPlayer().getUniqueId());
    }
}
