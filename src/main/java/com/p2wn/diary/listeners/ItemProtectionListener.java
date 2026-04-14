package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryDestructionAttemptEvent;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;

public class ItemProtectionListener implements Listener {

    private final DiaryPlugin plugin;

    public ItemProtectionListener(DiaryPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (!plugin.configManager().cfg().getBoolean("indestructible.prevent-combust", true)) return;
        if (e.getEntity() instanceof Item it && DiaryItem.isDiary(it.getItemStack())) {
            Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(it, "COMBUST"));
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item it)) return;
        if (!DiaryItem.isDiary(it.getItemStack())) return;

        var cfg = plugin.configManager().cfg();

        switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA -> {
                if (cfg.getBoolean("indestructible.prevent-combust", true)) {
                    Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(it, e.getCause().name()));
                    e.setCancelled(true);
                }
            }
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> {
                if (cfg.getBoolean("indestructible.prevent-explosion", true)) {
                    Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(it, e.getCause().name()));
                    e.setCancelled(true);
                }
            }
            default -> {
                if (cfg.getBoolean("indestructible.prevent-contact-damage", true)) {
                    Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(it, e.getCause().name()));
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent e) {
        if (!plugin.configManager().cfg().getBoolean("indestructible.prevent-despawn", true)) return;
        if (DiaryItem.isDiary(e.getEntity().getItemStack())) {
            Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(e.getEntity(), "DESPAWN"));
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        plugin.duplicateWatcher().onChunkLoad(e.getChunk());
    }
}
