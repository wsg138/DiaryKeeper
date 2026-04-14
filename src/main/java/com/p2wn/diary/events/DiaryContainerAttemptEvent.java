package com.p2wn.diary.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/**
 * Fired when a player attempts to place a diary into a restricted container.
 * The action is always blocked â€” this event is informational only.
 */
public class DiaryContainerAttemptEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack diary;
    private final String containerType;

    public DiaryContainerAttemptEvent(Player player, ItemStack diary, String containerType) {
        this.player = player;
        this.diary = diary;
        this.containerType = containerType;
    }

    public Player getPlayer() { return player; }
    public ItemStack getDiary() { return diary; }
    public String getContainerType() { return containerType; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
