package com.lincoln.diary.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

/**
 * Fired when a player edits their diary (writes content without signing).
 */
public class DiaryFilledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack diary;
    private final BookMeta newMeta;

    public DiaryFilledEvent(Player player, ItemStack diary, BookMeta newMeta) {
        this.player = player;
        this.diary = diary;
        this.newMeta = newMeta;
    }

    public Player getPlayer() { return player; }
    public ItemStack getDiary() { return diary; }
    public BookMeta getNewMeta() { return newMeta; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
