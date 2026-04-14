package com.p2wn.diary.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired when a player receives their diary for the first time (first-join mint).
 */
public class DiaryReceivedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack diary;

    public DiaryReceivedEvent(Player player, ItemStack diary) {
        this.player = player;
        this.diary = diary;
    }

    public Player getPlayer() { return player; }
    public ItemStack getDiary() { return diary; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
