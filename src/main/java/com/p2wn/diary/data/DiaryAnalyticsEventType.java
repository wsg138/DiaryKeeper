package com.p2wn.diary.data;

public enum DiaryAnalyticsEventType {
    INITIAL_ISSUE("Initial issue"),
    ADMIN_ISSUE("Admin issue"),
    QUEUED_DELIVERY("Queued delivery"),
    DELIVERED_FROM_QUEUE("Delivered from queue"),
    VOID_RETURN("Void return"),
    DIARY_EDITED("Diary edited"),
    DIARY_OBTAINED("Diary obtained"),
    DUPLICATE_WARNING("Duplicate warning"),
    BLOCKED_CONTAINER("Blocked container"),
    PROTECTED_DESTRUCTION("Protected destruction"),
    PURGE_STARTED("Purge started"),
    PURGE_COPY_REMOVED("Purge copy removed"),
    PURGE_PENDING_PLAYER("Purge pending player"),
    PURGE_PENDING_CHUNK("Purge pending chunk"),
    PURGE_PARTIAL("Purge partial"),
    PURGE_FAILED("Purge failed"),
    PURGE_COMPLETED("Purge completed"),
    RESTORE_TO_OWNER("Restore to owner"),
    RESTORE_TO_ADMIN("Restore to admin"),
    RESTORE_DUPLICATE("Restore duplicate"),
    POST_PURGE_COPY_FOUND("Post-purge copy found");

    private final String displayLabel;

    DiaryAnalyticsEventType(String label) {
        this.displayLabel = label;
    }

    public String label() {
        return displayLabel;
    }
}
