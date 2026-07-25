package com.p2wn.diary.logic;

import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.TrackedDiaryRecord;

/**
 * Read facade retained for command compatibility. Restore and removal work is
 * owned exclusively by {@link DiaryPurgeService}.
 */
public final class DiaryRestoreService {

    private final DiaryStore diaryStore;

    public DiaryRestoreService(DiaryStore diaryStore) {
        this.diaryStore = diaryStore;
    }

    public TrackedDiaryRecord getTrackedDiary(String diaryId) {
        return diaryStore.getTrackedDiary(diaryId);
    }
}
