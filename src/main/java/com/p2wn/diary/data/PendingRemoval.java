package com.p2wn.diary.data;

import java.util.UUID;

public record PendingRemoval(String diaryId, DiaryLocationType locationType, UUID holderUuid) {
}
