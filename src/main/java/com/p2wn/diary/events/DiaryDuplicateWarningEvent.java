package com.p2wn.diary.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a duplicate diary is detected during scanning.
 */
public class DiaryDuplicateWarningEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String diaryId;
    private final int duplicateCount;
    private final String scopeTag;
    private final String message;

    public DiaryDuplicateWarningEvent(String diaryId, int duplicateCount, String scopeTag, String message) {
        this.diaryId = diaryId;
        this.duplicateCount = duplicateCount;
        this.scopeTag = scopeTag;
        this.message = message;
    }

    public String getDiaryId() { return diaryId; }
    public int getDuplicateCount() { return duplicateCount; }
    public String getScopeTag() { return scopeTag; }
    public String getMessage() { return message; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
