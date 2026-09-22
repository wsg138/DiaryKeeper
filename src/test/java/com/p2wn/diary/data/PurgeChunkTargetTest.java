package com.p2wn.diary.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class PurgeChunkTargetTest {
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void keyPrefersWorldUuidAndFallsBackToWorldName() {
        PurgeChunkTarget withUuid = new PurgeChunkTarget(WORLD, "world", 12, -4, null, null, null);
        PurgeChunkTarget withoutUuid = new PurgeChunkTarget(null, "legacy-world", 12, -4, null, null, null);

        assertEquals(WORLD + ":12:-4", withUuid.key());
        assertEquals("legacy-world:12:-4", withoutUuid.key());
    }

    @Test
    void failTracksAttemptsAndErrorWithoutCompletingTarget() {
        PurgeChunkTarget target = target();
        AtomicInteger dirty = new AtomicInteger();
        target.attachDirtyCallback(dirty::incrementAndGet);

        target.fail("chunk unavailable");
        target.fail("still unavailable");

        assertFalse(target.completed());
        assertEquals(2, target.attempts());
        assertEquals("still unavailable", target.error());
        assertEquals(2, dirty.get());
    }

    @Test
    void completeMarksDoneClearsErrorAndNotifiesPersistence() {
        PurgeChunkTarget target = target();
        target.loadState(false, 3, "old error");
        AtomicInteger dirty = new AtomicInteger();
        target.attachDirtyCallback(dirty::incrementAndGet);

        target.complete();

        assertTrue(target.completed());
        assertEquals(3, target.attempts());
        assertNull(target.error());
        assertEquals(1, dirty.get());
    }

    @Test
    void finishWithErrorIsTerminalAndPreservesReason() {
        PurgeChunkTarget target = target();

        target.finishWithError("gave up safely");

        assertTrue(target.completed());
        assertEquals("gave up safely", target.error());
    }

    @Test
    void resetForRetryClearsTerminalAndAttemptState() {
        PurgeChunkTarget target = target();
        target.loadState(true, 7, "terminal failure");

        target.resetForRetry();

        assertFalse(target.completed());
        assertEquals(0, target.attempts());
        assertNull(target.error());
    }

    @Test
    void loadingStateAndNullCallbackRemainSafeAndObservable() {
        PurgeChunkTarget target = target();
        AtomicInteger dirty = new AtomicInteger();
        target.attachDirtyCallback(dirty::incrementAndGet);

        target.setLoading(true);
        assertTrue(target.loading());
        target.setLoading(false);
        assertFalse(target.loading());
        assertEquals(2, dirty.get());

        target.attachDirtyCallback(null);
        target.fail("safe with no callback");
        assertEquals(1, target.attempts());
    }

    private static PurgeChunkTarget target() {
        return new PurgeChunkTarget(WORLD, "world", 3, 9, 50, 64, 150);
    }
}
