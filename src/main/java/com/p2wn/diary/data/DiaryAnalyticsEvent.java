package com.p2wn.diary.data;

import java.util.UUID;

public record DiaryAnalyticsEvent(
        long occurredAt,
        DiaryAnalyticsEventType type,
        UUID playerUuid,
        String playerName,
        String diaryId,
        String detail
) {
}
