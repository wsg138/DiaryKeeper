package com.lincoln.diary.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired when a player picks up a diary from the ground.
 */
public class DiaryObtainedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack diary;

    public DiaryObtainedEvent(Player player, ItemStack diary) {
        this.player = player;
        this.diary = diary;
    }

    public Player getPlayer() { return player; }
    public ItemStack getDiary() { return diary; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
