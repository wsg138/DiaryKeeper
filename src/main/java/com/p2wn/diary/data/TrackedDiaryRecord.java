package com.p2wn.diary.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.List;

public record TrackedDiaryRecord(
        String diaryId,
        UUID ownerUuid,
        String ownerName,
        ItemStack snapshot,
        DiaryLocationRecord lastKnownLocation,
        List<DiaryLocationRecord> locations,
        long snapshotUpdatedAt
) {
    public TrackedDiaryRecord {
        locations = locations == null ? List.of() : List.copyOf(locations);
    }

    public TrackedDiaryRecord(String diaryId, UUID ownerUuid, String ownerName,
                              ItemStack snapshot, DiaryLocationRecord lastKnownLocation) {
        this(diaryId, ownerUuid, ownerName, snapshot, lastKnownLocation,
                lastKnownLocation == null ? List.of() : List.of(lastKnownLocation), 0L);
    }

    public TrackedDiaryRecord copy() {
        return new TrackedDiaryRecord(
                diaryId,
                ownerUuid,
                ownerName,
                snapshot == null ? null : snapshot.clone(),
                lastKnownLocation,
                locations,
                snapshotUpdatedAt
        );
    }

    public long activeLocationCount() {
        return locations.stream().filter(DiaryLocationRecord::active).count();
    }
}
