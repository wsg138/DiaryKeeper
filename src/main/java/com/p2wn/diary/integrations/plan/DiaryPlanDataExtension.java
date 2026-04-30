package com.p2wn.diary.integrations.plan;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.BooleanProvider;
import com.djrapitops.plan.extension.annotation.Conditional;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.icon.Icon;
import com.djrapitops.plan.extension.table.Table;
import com.djrapitops.plan.extension.table.TableColumnFormat;
import com.p2wn.diary.data.DiaryAnalyticsEvent;
import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DiaryLocationRecord;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.TrackedDiaryRecord;

import java.time.Instant;
import java.util.UUID;

@PluginInfo(
        name = "DiaryKeeper",
        iconName = "book",
        iconFamily = Family.SOLID,
        color = Color.LIGHT_BLUE
)
public final class DiaryPlanDataExtension implements DataExtension {

    private static final String HAS_DIARY = "hasDiary";
    private static final String HAS_DIARY_ACTIVITY = "hasDiaryActivity";

    private final DiaryStore diaryStore;
    private final DiaryAnalyticsStore analyticsStore;
    private final int recentTableSize;

    public DiaryPlanDataExtension(DiaryStore diaryStore, DiaryAnalyticsStore analyticsStore, int recentTableSize) {
        this.diaryStore = diaryStore;
        this.analyticsStore = analyticsStore;
        this.recentTableSize = Math.max(1, Math.min(25, recentTableSize));
    }

    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[] {
                CallEvents.PLAYER_JOIN,
                CallEvents.PLAYER_LEAVE,
                CallEvents.SERVER_EXTENSION_REGISTER,
                CallEvents.SERVER_PERIODICAL
        };
    }

    @BooleanProvider(
            text = "Diary issued",
            description = "Whether this player has been issued a DiaryKeeper diary.",
            priority = 100,
            conditionName = HAS_DIARY,
            hidden = false,
            iconName = "book",
            iconFamily = Family.SOLID,
            iconColor = Color.LIGHT_BLUE,
            showInPlayerTable = true
    )
    public boolean hasDiary(UUID playerUuid) {
        return diaryStore.hasIssued(playerUuid) && diaryStore.getDiaryId(playerUuid) != null;
    }

    @Conditional(HAS_DIARY)
    @StringProvider(
            text = "Diary ID",
            description = "The player's assigned diary identifier.",
            priority = 90,
            playerName = false,
            iconName = "fingerprint",
            iconFamily = Family.SOLID,
            iconColor = Color.NONE,
            showInPlayerTable = false
    )
    public String diaryId(UUID playerUuid) {
        String diaryId = diaryStore.getDiaryId(playerUuid);
        return diaryId == null ? "unknown" : shortId(diaryId);
    }

    @Conditional(HAS_DIARY)
    @NumberProvider(
            text = "Diary issued at",
            description = "When this player's diary was first issued.",
            priority = 80,
            iconName = "calendar-check",
            iconFamily = Family.SOLID,
            iconColor = Color.GREEN,
            format = FormatType.DATE_SECOND,
            showInPlayerTable = false
    )
    public long issuedAt(UUID playerUuid) {
        return toMillis(diaryStore.getIssuedAt(playerUuid));
    }

    @NumberProvider(
            text = "Queued diary deliveries",
            description = "Diary items waiting to be delivered to this player.",
            priority = 70,
            iconName = "truck",
            iconFamily = Family.SOLID,
            iconColor = Color.YELLOW,
            format = FormatType.NONE,
            showInPlayerTable = true
    )
    public long queuedDeliveries(UUID playerUuid) {
        return diaryStore.getPendingDeliveryCount(playerUuid);
    }

    @BooleanProvider(
            text = "Has diary activity",
            description = "Whether DiaryKeeper has recent retained activity for this player.",
            priority = 1,
            conditionName = HAS_DIARY_ACTIVITY,
            hidden = true,
            iconName = "clock",
            iconFamily = Family.SOLID,
            iconColor = Color.NONE,
            showInPlayerTable = false
    )
    public boolean hasDiaryActivity(UUID playerUuid) {
        return analyticsStore.lastActivityAt(playerUuid) > 0L;
    }

    @Conditional(HAS_DIARY)
    @StringProvider(
            text = "Last known diary location",
            description = "Most recent location recorded by DiaryKeeper tracking.",
            priority = 60,
            playerName = false,
            iconName = "map-marker-alt",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE,
            showInPlayerTable = false
    )
    public String lastKnownLocation(UUID playerUuid) {
        TrackedDiaryRecord record = diaryStore.findTrackedDiaryByOwner(playerUuid);
        DiaryLocationRecord location = record == null ? null : record.lastKnownLocation();
        return location == null ? "unknown" : trim(location.description());
    }

    @Conditional(HAS_DIARY_ACTIVITY)
    @NumberProvider(
            text = "Last diary activity",
            description = "Most recent DiaryKeeper activity recorded for this player.",
            priority = 50,
            iconName = "clock",
            iconFamily = Family.SOLID,
            iconColor = Color.NONE,
            format = FormatType.DATE_SECOND,
            showInPlayerTable = false
    )
    public long lastDiaryActivity(UUID playerUuid) {
        return toMillis(analyticsStore.lastActivityAt(playerUuid));
    }

    @NumberProvider(
            text = "Issued diaries",
            description = "Players who have a DiaryKeeper diary issued.",
            priority = 100,
            iconName = "book",
            iconFamily = Family.SOLID,
            iconColor = Color.LIGHT_BLUE,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long issuedDiaries() {
        return diaryStore.getIssuedPlayerCount();
    }

    @NumberProvider(
            text = "Tracked diary items",
            description = "Diary items with a known tracked snapshot or location.",
            priority = 90,
            iconName = "location-dot",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long trackedDiaryItems() {
        return diaryStore.getTrackedDiaryCount();
    }

    @NumberProvider(
            text = "Queued diary deliveries",
            description = "Diary items currently waiting in the delivery queue.",
            priority = 80,
            iconName = "truck",
            iconFamily = Family.SOLID,
            iconColor = Color.YELLOW,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long queuedDiaryDeliveries() {
        return diaryStore.getTotalPendingDeliveryCount();
    }

    @NumberProvider(
            text = "Players waiting for delivery",
            description = "Players with one or more queued diary deliveries.",
            priority = 70,
            iconName = "users",
            iconFamily = Family.SOLID,
            iconColor = Color.YELLOW,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long playersWaitingForDelivery() {
        return diaryStore.getPlayersWithPendingDeliveries().size();
    }

    @NumberProvider(
            text = "Duplicate warnings in 24h",
            description = "Duplicate diary warnings recorded in the last 24 hours.",
            priority = 60,
            iconName = "triangle-exclamation",
            iconFamily = Family.SOLID,
            iconColor = Color.RED,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long duplicateWarnings24h() {
        return countSince(DiaryAnalyticsEventType.DUPLICATE_WARNING, 1);
    }

    @NumberProvider(
            text = "Void returns in 24h",
            description = "Diaries returned from the void in the last 24 hours.",
            priority = 50,
            iconName = "rotate-left",
            iconFamily = Family.SOLID,
            iconColor = Color.GREEN,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long voidReturns24h() {
        return countSince(DiaryAnalyticsEventType.VOID_RETURN, 1);
    }

    @NumberProvider(
            text = "Blocked container attempts in 24h",
            description = "Restricted container placement attempts blocked in the last 24 hours.",
            priority = 40,
            iconName = "box-archive",
            iconFamily = Family.SOLID,
            iconColor = Color.ORANGE,
            format = FormatType.NONE,
            showInPlayerTable = false
    )
    public long blockedContainerAttempts24h() {
        return countSince(DiaryAnalyticsEventType.BLOCKED_CONTAINER, 1);
    }

    @TableProvider(tableColor = Color.LIGHT_BLUE)
    public Table recentDiaryActivity() {
        Table.Factory table = Table.builder()
                .columnOne("When", icon("clock"))
                .columnOneFormat(TableColumnFormat.DATE_SECOND)
                .columnTwo("Event", icon("list"))
                .columnThree("Player", icon("user"))
                .columnFour("Diary", icon("fingerprint"))
                .columnFive("Detail", icon("circle-info"));
        for (DiaryAnalyticsEvent event : analyticsStore.recentEvents(recentTableSize)) {
            table.addRow(toMillis(event.occurredAt()), event.type().label(), value(event.playerName()), shortId(event.diaryId()), trim(event.detail()));
        }
        return table.build();
    }

    @TableProvider(tableColor = Color.BLUE)
    public Table recentTrackedLocations() {
        Table.Factory table = Table.builder()
                .columnOne("Updated", icon("clock"))
                .columnOneFormat(TableColumnFormat.DATE_SECOND)
                .columnTwo("Owner", icon("user"))
                .columnThree("Diary", icon("fingerprint"))
                .columnFour("Type", icon("tag"))
                .columnFive("Location", icon("map-marker-alt"));
        for (TrackedDiaryRecord record : diaryStore.getRecentTrackedDiaries(recentTableSize)) {
            DiaryLocationRecord location = record.lastKnownLocation();
            table.addRow(
                    toMillis(location == null ? 0L : location.updatedAtEpochSeconds()),
                    shortId(record.ownerUuid() == null ? null : record.ownerUuid().toString()),
                    shortId(record.diaryId()),
                    location == null ? "unknown" : location.type().name(),
                    location == null ? "unknown" : trim(location.description())
            );
        }
        return table.build();
    }

    @TableProvider(tableColor = Color.LIGHT_BLUE)
    public Table recentPlayerDiaryActivity(UUID playerUuid) {
        Table.Factory table = Table.builder()
                .columnOne("When", icon("clock"))
                .columnOneFormat(TableColumnFormat.DATE_SECOND)
                .columnTwo("Event", icon("list"))
                .columnThree("Diary", icon("fingerprint"))
                .columnFour("Detail", icon("circle-info"));
        for (DiaryAnalyticsEvent event : analyticsStore.recentEventsForPlayer(playerUuid, recentTableSize)) {
            table.addRow(toMillis(event.occurredAt()), event.type().label(), shortId(event.diaryId()), trim(event.detail()));
        }
        return table.build();
    }

    private long countSince(DiaryAnalyticsEventType type, int days) {
        long since = Instant.now().getEpochSecond() - days * 86_400L;
        return analyticsStore.countSince(type, since);
    }

    private long toMillis(long epochSeconds) {
        return epochSeconds <= 0L ? 0L : epochSeconds * 1000L;
    }

    private String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "none";
        }
        return id.substring(0, Math.min(8, id.length()));
    }

    private String value(String input) {
        return input == null || input.isBlank() ? "none" : trim(input);
    }

    private String trim(String input) {
        if (input == null || input.isBlank()) {
            return "none";
        }
        return input.length() <= 50 ? input : input.substring(0, 47) + "...";
    }

    private Icon icon(String name) {
        return new Icon(Family.SOLID, name, Color.NONE);
    }
}
