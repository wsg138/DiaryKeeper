package com.p2wn.diary.listeners;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DiaryAnalyticsEventType;
import com.p2wn.diary.events.DiaryContainerAttemptEvent;
import com.p2wn.diary.events.DiaryDestructionAttemptEvent;
import com.p2wn.diary.events.DiaryDuplicateWarningEvent;
import com.p2wn.diary.events.DiaryFilledEvent;
import com.p2wn.diary.events.DiaryObtainedEvent;
import com.p2wn.diary.events.DiaryReceivedEvent;
import com.p2wn.diary.events.DiarySignedEvent;
import com.p2wn.diary.events.DiaryVoidReturnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class DiaryAnalyticsListener implements Listener {

    private final DiaryPlugin plugin;

    public DiaryAnalyticsListener(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDiaryReceived(DiaryReceivedEvent event) {
        recordPlayerEvent(DiaryAnalyticsEventType.INITIAL_ISSUE, event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getDiary(), "first join");
    }

    @EventHandler
    public void onDiaryObtained(DiaryObtainedEvent event) {
        recordPlayerEvent(DiaryAnalyticsEventType.DIARY_OBTAINED, event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getDiary(), "picked up");
    }

    @EventHandler
    public void onDiaryVoidReturn(DiaryVoidReturnEvent event) {
        recordPlayerEvent(DiaryAnalyticsEventType.VOID_RETURN, event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getDiary(), "returned to inventory");
    }

    @EventHandler
    public void onDiaryFilled(DiaryFilledEvent event) {
        recordPlayerEvent(DiaryAnalyticsEventType.DIARY_EDITED, event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getDiary(), "edited");
    }

    @EventHandler
    public void onDiarySigned(DiarySignedEvent event) {
        recordPlayerEvent(DiaryAnalyticsEventType.DIARY_EDITED, event.getPlayer().getUniqueId(), event.getPlayer().getName(), event.getDiary(), "sign blocked");
    }

    @EventHandler
    public void onDuplicateWarning(DiaryDuplicateWarningEvent event) {
        plugin.diaryAnalyticsStore().record(
                DiaryAnalyticsEventType.DUPLICATE_WARNING,
                null,
                null,
                event.getDiaryId(),
                event.getDuplicateCount() + " copies in " + event.getScopeTag()
        );
    }

    @EventHandler
    public void onContainerAttempt(DiaryContainerAttemptEvent event) {
        recordPlayerEvent(
                DiaryAnalyticsEventType.BLOCKED_CONTAINER,
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName(),
                event.getDiary(),
                event.getContainerType()
        );
    }

    @EventHandler
    public void onDestructionAttempt(DiaryDestructionAttemptEvent event) {
        UUID ownerUuid = plugin.diaryItem().getOwner(event.getItem().getItemStack());
        plugin.diaryAnalyticsStore().record(
                DiaryAnalyticsEventType.PROTECTED_DESTRUCTION,
                ownerUuid,
                null,
                plugin.diaryItem().getDiaryId(event.getItem().getItemStack()),
                event.getCause()
        );
    }

    private void recordPlayerEvent(DiaryAnalyticsEventType type, UUID playerUuid, String playerName, ItemStack diary, String detail) {
        plugin.diaryAnalyticsStore().record(
                type,
                playerUuid,
                playerName,
                plugin.diaryItem().getDiaryId(diary),
                detail
        );
    }
}
