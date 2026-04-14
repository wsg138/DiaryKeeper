package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryDestructionAttemptEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

public final class ItemProtectionListener implements Listener {

    private final DiaryPlugin plugin;

    public ItemProtectionListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (!plugin.configManager().cfg().getBoolean("indestructible.prevent-combust", true)) {
            return;
        }
        if (event.getEntity() instanceof Item item && plugin.diaryService().isDiary(item.getItemStack())) {
            Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(item, "COMBUST"));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item) || !plugin.diaryService().isDiary(item.getItemStack())) {
            return;
        }

        boolean cancel = switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA -> plugin.configManager().cfg().getBoolean("indestructible.prevent-combust", true);
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> plugin.configManager().cfg().getBoolean("indestructible.prevent-explosion", true);
            default -> plugin.configManager().cfg().getBoolean("indestructible.prevent-contact-damage", true);
        };

        if (cancel) {
            Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(item, event.getCause().name()));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent event) {
        if (!plugin.configManager().cfg().getBoolean("indestructible.prevent-despawn", true)) {
            return;
        }
        if (plugin.diaryService().isDiary(event.getEntity().getItemStack())) {
            Bukkit.getPluginManager().callEvent(new DiaryDestructionAttemptEvent(event.getEntity(), "DESPAWN"));
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.duplicateWatcher().onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        plugin.duplicateWatcher().onChunkUnload(event.getChunk());
    }
}
