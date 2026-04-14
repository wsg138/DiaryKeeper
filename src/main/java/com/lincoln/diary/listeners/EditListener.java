package com.lincoln.diary.listeners;

import com.lincoln.diary.DiaryPlugin;
import com.lincoln.diary.events.DiaryFilledEvent;
import com.lincoln.diary.events.DiarySignedEvent;
import com.lincoln.diary.item.DiaryItem;
import com.lincoln.diary.util.DiaryTextValidator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;

import java.util.UUID;

public class EditListener implements Listener {

    private final DiaryPlugin plugin;

    public EditListener(DiaryPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEdit(PlayerEditBookEvent e) {
        if (e.getSlot() < 0) return;
        var item = e.getPlayer().getInventory().getItem(e.getSlot());
        if (!DiaryItem.isDiary(item)) return;

        var cfg = plugin.configManager().cfg();

        if (!cfg.getBoolean("allow-signing", false) && e.isSigning()) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.configManager().msg("signing-disabled"));
            return;
        }

        if (cfg.getBoolean("owner-only-edit", true)) {
            UUID owner = DiaryItem.getOwner(item);
            Player p = e.getPlayer();
            if (owner == null || !owner.equals(p.getUniqueId())) {
                e.setCancelled(true);
                p.sendMessage(plugin.configManager().msg("non-owner-edit"));
                return;
            }
        }

        var newMeta = e.getNewBookMeta();
        if (!DiaryTextValidator.isAsciiOnly(newMeta.getTitle())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.configManager().msg("ascii-only"));
            return;
        }

        for (String page : newMeta.getPages()) {
            if (!DiaryTextValidator.isAsciiOnly(page)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(plugin.configManager().msg("ascii-only"));
                return;
            }
        }

        if (e.isSigning()) {
            Bukkit.getPluginManager().callEvent(new DiarySignedEvent(e.getPlayer(), item, newMeta));
        } else {
            Bukkit.getPluginManager().callEvent(new DiaryFilledEvent(e.getPlayer(), item, newMeta));
        }
    }
}
