package com.p2wn.diary.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class PlayerIdentityTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void constructorSeedsCurrentNameAliasAndLastSeen() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        assertEquals(PLAYER, identity.uuid());
        assertEquals("Alice", identity.currentName());
        assertEquals(100L, identity.lastSeen());
        assertEquals(java.util.Set.of("Alice"), identity.aliases());
        assertNull(identity.xuid());
        assertNull(identity.platform());
    }

    @Test
    void aliasesRejectBlankValuesDuplicatesAndExternalMutation() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        assertFalse(identity.addAlias(null));
        assertFalse(identity.addAlias(""));
        assertFalse(identity.addAlias("   "));
        assertFalse(identity.addAlias("Alice"));
        assertTrue(identity.addAlias("OldAlice"));
        assertEquals(java.util.Set.of("Alice", "OldAlice"), identity.aliases());
        assertThrows(UnsupportedOperationException.class, () -> identity.aliases().add("Injected"));
    }

    @Test
    void olderObservationCanRecordAliasWithoutReplacingCurrentIdentity() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        assertTrue(identity.observe("OldAlice", 50L));

        assertEquals("Alice", identity.currentName());
        assertEquals(100L, identity.lastSeen());
        assertEquals(java.util.Set.of("Alice", "OldAlice"), identity.aliases());
    }

    @Test
    void newerObservationUpdatesCurrentNameAndTimestamp() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        assertTrue(identity.observe("AliceTwo", 150L));
        assertEquals("AliceTwo", identity.currentName());
        assertEquals(150L, identity.lastSeen());
        assertEquals(java.util.Set.of("Alice", "AliceTwo"), identity.aliases());

        assertFalse(identity.observe("AliceTwo", 150L));
    }

    @Test
    void blankNameStillAdvancesLastSeenButNeverBecomesAnAlias() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        assertTrue(identity.observe("  ", 125L));

        assertEquals("Alice", identity.currentName());
        assertEquals(125L, identity.lastSeen());
        assertEquals(java.util.Set.of("Alice"), identity.aliases());
    }

    @Test
    void floodgateObservationTracksIdentityAndIsIdempotentForSameSnapshot() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        assertTrue(identity.observeFloodgate("BedrockAlice", "123456789", "bedrock", 200L));
        assertEquals("BedrockAlice", identity.currentName());
        assertEquals("123456789", identity.xuid());
        assertEquals("bedrock", identity.platform());
        assertEquals(200L, identity.lastSeen());

        assertFalse(identity.observeFloodgate("BedrockAlice", "123456789", "bedrock", 200L));
    }

    @Test
    void loadingFloodgateMetadataDoesNotRewriteNameOrLastSeen() {
        PlayerIdentity identity = new PlayerIdentity(PLAYER, "Alice", 100L);

        identity.loadFloodgate("loaded-xuid", "loaded-platform");

        assertEquals("Alice", identity.currentName());
        assertEquals(100L, identity.lastSeen());
        assertEquals("loaded-xuid", identity.xuid());
        assertEquals("loaded-platform", identity.platform());
    }
}
