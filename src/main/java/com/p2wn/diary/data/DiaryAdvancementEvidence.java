package com.p2wn.diary.data;

public record DiaryAdvancementEvidence(
        boolean received,
        int edits,
        int destructionAttempts,
        int voidReturns,
        int containerAttempts,
        int groundPickups
) {
    public static final DiaryAdvancementEvidence EMPTY =
            new DiaryAdvancementEvidence(false, 0, 0, 0, 0, 0);

    public DiaryAdvancementEvidence {
        if (edits < 0 || destructionAttempts < 0 || voidReturns < 0
                || containerAttempts < 0 || groundPickups < 0) {
            throw new IllegalArgumentException("Diary advancement counters cannot be negative");
        }
    }

    public DiaryAdvancementEvidence withReceived() {
        return received ? this : new DiaryAdvancementEvidence(
                true, edits, destructionAttempts, voidReturns, containerAttempts, groundPickups);
    }

    public DiaryAdvancementEvidence recordEdit() {
        return new DiaryAdvancementEvidence(received, Math.addExact(edits, 1),
                destructionAttempts, voidReturns, containerAttempts, groundPickups);
    }

    public DiaryAdvancementEvidence recordDestructionAttempt() {
        return new DiaryAdvancementEvidence(received, edits,
                Math.addExact(destructionAttempts, 1),
                voidReturns, containerAttempts, groundPickups);
    }

    public DiaryAdvancementEvidence recordVoidReturn() {
        return new DiaryAdvancementEvidence(received, edits, destructionAttempts,
                Math.addExact(voidReturns, 1), containerAttempts, groundPickups);
    }

    public DiaryAdvancementEvidence recordContainerAttempt() {
        return new DiaryAdvancementEvidence(received, edits, destructionAttempts,
                voidReturns, Math.addExact(containerAttempts, 1), groundPickups);
    }

    public DiaryAdvancementEvidence recordGroundPickup() {
        return new DiaryAdvancementEvidence(received, edits, destructionAttempts,
                voidReturns, containerAttempts, Math.addExact(groundPickups, 1));
    }

    public DiaryAdvancementEvidence max(DiaryAdvancementEvidence other) {
        if (other == null) return this;
        return new DiaryAdvancementEvidence(
                received || other.received,
                Math.max(edits, other.edits),
                Math.max(destructionAttempts, other.destructionAttempts),
                Math.max(voidReturns, other.voidReturns),
                Math.max(containerAttempts, other.containerAttempts),
                Math.max(groundPickups, other.groundPickups));
    }
}
