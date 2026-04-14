package com.p2wn.diary.events;

import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when something attempts to destroy a diary (fire, explosion, despawn, etc.).
 * The diary is always protected; this event is informational only.
 */
public class DiaryDestructionAttemptEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Item item;
    private final String cause;

    public DiaryDestructionAttemptEvent(Item item, String cause) {
        this.item = item;
        this.cause = cause;
    }

    public Item getItem() { return item; }
    public String getCause() { return cause; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
