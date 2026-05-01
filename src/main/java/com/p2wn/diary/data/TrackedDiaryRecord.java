package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public record TrackedDiaryRecord(
        String diaryId,
        UUID ownerUuid,
        String ownerName,
        ItemStack snapshot,
        DiaryLocationRecord lastKnownLocation
) {

    public TrackedDiaryRecord copy() {
        return new TrackedDiaryRecord(
                diaryId,
                ownerUuid,
                ownerName,
                snapshot == null ? null : snapshot.clone(),
                lastKnownLocation
        );
    }
}
