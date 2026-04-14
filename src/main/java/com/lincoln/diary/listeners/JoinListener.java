package com.lincoln.diary.listeners;

import com.lincoln.diary.DiaryPlugin;
import com.lincoln.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import com.lincoln.diary.events.DiaryReceivedEvent;

import java.util.List;

public class JoinListener implements Listener {

    private final DiaryPlugin plugin;

    public JoinListener(DiaryPlugin plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        // Refresh cosmetics for any diaries they OWN (username sync)
        for (ItemStack it : p.getInventory().getContents()) {
            if (DiaryItem.isDiary(it) && p.getUniqueId().equals(DiaryItem.getOwner(it))) {
                DiaryItem.refreshOwnerCosmetics(p, it);
            }
        }

        // TRUE one-time mint: only if never issued (or after data/world reset)
        if (!plugin.diaryStore().hasIssued(p.getUniqueId()) || plugin.diaryStore().getId(p.getUniqueId()) == null) {
            var minted = DiaryItem.mintFor(p);
            Bukkit.getPluginManager().callEvent(new DiaryReceivedEvent(p, minted));
            var leftovers = p.getInventory().addItem(minted);
            if (!leftovers.isEmpty()) {
                // inventory full → reliable delivery queue (no unsafe drops)
                plugin.deliveryService().enqueue(p.getUniqueId(), minted);
            }
            plugin.diaryStore().markIssued(p.getUniqueId());
        }

        // Duplicate warnings on join (lightweight, debounced)
        plugin.duplicateWatcher().onPlayerJoinInventory(p);

        // Deliver any exact queued void returns (offline captures)
        List<ItemStack> queued = plugin.diaryStore().drainVoidReturns(p.getUniqueId());
        if (!queued.isEmpty()) {
            plugin.deliveryService().enqueueAll(p.getUniqueId(), queued);
        }
    }
}
