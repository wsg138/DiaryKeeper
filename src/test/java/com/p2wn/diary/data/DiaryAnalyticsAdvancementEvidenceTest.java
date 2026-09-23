package com.p2wn.diary.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiaryAnalyticsAdvancementEvidenceTest {
    private static final String PLAYER_NAME = "Player";
    private static final String DIARY_ID = "diary";
    @TempDir Path temp;

    @Test
    void retainedAnalyticsSeedsOnlyProvableDiaryMilestones() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());

        DiaryAnalyticsStore store = new DiaryAnalyticsStore(plugin);
        UUID player = UUID.randomUUID();
        store.record(DiaryAnalyticsEventType.INITIAL_ISSUE, player, PLAYER_NAME, DIARY_ID, "first join");
        store.record(DiaryAnalyticsEventType.DIARY_EDITED, player, PLAYER_NAME, DIARY_ID, "edited");
        store.record(DiaryAnalyticsEventType.DIARY_EDITED, player, PLAYER_NAME, DIARY_ID, "sign blocked");
        store.record(DiaryAnalyticsEventType.PROTECTED_DESTRUCTION, player, PLAYER_NAME, DIARY_ID, "LAVA");
        store.record(DiaryAnalyticsEventType.VOID_RETURN, player, PLAYER_NAME, DIARY_ID, "returned to inventory");
        store.record(DiaryAnalyticsEventType.BLOCKED_CONTAINER, player, PLAYER_NAME, DIARY_ID, "ENDER_CHEST");
        store.record(DiaryAnalyticsEventType.DIARY_OBTAINED, player, PLAYER_NAME, DIARY_ID, "picked up");

        assertEquals(
                new DiaryAdvancementEvidence(true, 1, 1, 1, 1, 1),
                store.advancementEvidenceSummary().get(player));
    }
    @Test
    void resetCutoffExcludesOlderAndSameSecondEventsButKeepsNewerEvidence() throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        UUID oldPlayer = UUID.randomUUID();
        UUID newPlayer = UUID.randomUUID();
        long cutoff = Instant.now().getEpochSecond() - 2;
        YamlConfiguration file = new YamlConfiguration();
        writeEvent(file, 0, cutoff - 1, DiaryAnalyticsEventType.INITIAL_ISSUE, oldPlayer, "issue");
        writeEvent(file, 1, cutoff, DiaryAnalyticsEventType.DIARY_EDITED, oldPlayer, "edited");
        writeEvent(file, 2, cutoff + 1, DiaryAnalyticsEventType.DIARY_EDITED, newPlayer, "edited");
        file.save(temp.resolve("analytics.yml").toFile());

        DiaryAnalyticsStore store = new DiaryAnalyticsStore(plugin);
        store.load();
        var summary = store.advancementEvidenceSummary(cutoff);
        assertFalse(summary.containsKey(oldPlayer));
        assertEquals(1, summary.get(newPlayer).edits());
        assertEquals(3, store.recentEvents(10).size(),
                "Filtering advancement migration must not erase the audit log");
    }

    private static void writeEvent(YamlConfiguration file, int index, long occurredAt,
                                   DiaryAnalyticsEventType type, UUID player, String detail) {
        String path = "events." + index + ".";
        file.set(path + "occurredAt", occurredAt);
        file.set(path + "type", type.name());
        file.set(path + "playerUuid", player.toString());
        file.set(path + "playerName", PLAYER_NAME);
        file.set(path + "diaryId", DIARY_ID);
        file.set(path + "detail", detail);
    }

    @Test
    void startupAppliesResetBoundaryBeforeReconciliation() throws Exception {
        String source=java.nio.file.Files.readString(Path.of("src/main/java/com/p2wn/diary/DiaryPlugin.java"));
        int reset=source.indexOf("        handleWorldReset();");
        int reconcile=source.indexOf("activeDiaryStore.reconcileAdvancementEvidence(");
        assertTrue(reset>=0 && reset<reconcile);
        assertTrue(source.substring(reconcile,reconcile+250).contains("activeDiaryStore.getAdvancementEvidenceResetAfter()"));
    }

}
