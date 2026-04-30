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
    PROTECTED_DESTRUCTION("Protected destruction");

    private final String label;

    DiaryAnalyticsEventType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
