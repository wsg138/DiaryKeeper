package com.p2wn.diary.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiaryAdvancementEvidenceTest {
    @Test
    void countersAndReceivedStateAreMonotonic() {
        var evidence = DiaryAdvancementEvidence.EMPTY
                .withReceived()
                .recordEdit()
                .recordEdit()
                .recordDestructionAttempt()
                .recordDestructionAttempt()
                .recordVoidReturn()
                .recordContainerAttempt()
                .recordGroundPickup();

        assertTrue(evidence.received());
        assertEquals(2, evidence.edits());
        assertEquals(2, evidence.destructionAttempts());
        assertEquals(1, evidence.voidReturns());
        assertEquals(1, evidence.containerAttempts());
        assertEquals(1, evidence.groundPickups());
    }

    @Test
    void maxMergeNeverRegressesProviderEvidence() {
        var current = new DiaryAdvancementEvidence(true, 10, 4, 1, 2, 3);
        var imported = new DiaryAdvancementEvidence(false, 25, 1, 2, 1, 5);

        assertEquals(
                new DiaryAdvancementEvidence(true, 25, 4, 2, 2, 5),
                current.max(imported));
    }

    @Test
    void negativeCountersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DiaryAdvancementEvidence(false, -1, 0, 0, 0, 0));
    }
}
