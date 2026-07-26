package com.p2wn.diary.commands;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.data.IdentityResolution;
import com.p2wn.diary.logic.DiaryService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiaryCommandIdentityTest {
    @Test
    void cachedAuthenticatedJavaPlayerIsAcceptedButUnknownNamesNeverGenerateUuid() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        DiaryService service = mock(DiaryService.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("diary.admin")).thenReturn(true);
        when(plugin.diaryStore()).thenReturn(store);
        when(plugin.diaryService()).thenReturn(service);
        when(store.resolveStoredPlayer(anyString())).thenReturn(IdentityResolution.notFound());
        OfflinePlayer cached = mock(OfflinePlayer.class);
        UUID cachedUuid = UUID.randomUUID();
        when(cached.getName()).thenReturn("CachedJava");
        when(cached.getUniqueId()).thenReturn(cachedUuid);
        when(cached.hasPlayedBefore()).thenReturn(true);
        DiaryService.IssueResult issueResult = mock(DiaryService.IssueResult.class);
        when(service.issueDiary(cached, "CachedJava")).thenReturn(issueResult);
        when(service.formatAdminSummary(issueResult)).thenReturn("issued");
        List<String> messages = new ArrayList<>();
        doAnswer(call -> { messages.add(call.getArgument(0)); return null; })
                .when(sender).sendMessage(anyString());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact(anyString())).thenReturn(null);
            bukkit.when(Bukkit::getOfflinePlayers).thenReturn(new OfflinePlayer[]{cached});
            DiaryCommand command = new DiaryCommand(plugin);
            command.onCommand(sender, mock(Command.class), "diary",
                    new String[]{"issue", "CachedJava"});
            verify(service).issueDiary(cached, "CachedJava");

            messages.clear();
            command.onCommand(sender, mock(Command.class), "diary",
                    new String[]{"issue", "NeverJoined"});
            verify(service, never()).issueDiary(any(), eq("NeverJoined"));
            assertEquals(1, messages.size());
            assertFalse(messages.getFirst().toLowerCase().contains("diary id"));
            assertTrue(messages.getFirst().contains("exact UUID"));
            bukkit.verify(() -> Bukkit.getOfflinePlayer("NeverJoined"), never());
        }
    }

    @Test
    void ambiguousStoredIdentityNeverFallsThroughToBukkit() {
        DiaryPlugin plugin = mock(DiaryPlugin.class);
        DiaryStore store = mock(DiaryStore.class);
        DiaryService service = mock(DiaryService.class);
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("diary.admin")).thenReturn(true);
        when(plugin.diaryStore()).thenReturn(store);
        when(plugin.diaryService()).thenReturn(service);
        when(store.resolveStoredPlayer("Shared")).thenReturn(IdentityResolution.ambiguous());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            new DiaryCommand(plugin).onCommand(sender, mock(Command.class), "diary",
                    new String[]{"issue", "Shared"});
            verify(service, never()).issueDiary(any(), anyString());
            bukkit.verifyNoInteractions();
        }
    }
}
