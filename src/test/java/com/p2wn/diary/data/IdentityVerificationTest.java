package com.p2wn.diary.data;

import com.p2wn.diary.integrations.floodgate.FloodgateIdentityAdapter;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdentityVerificationTest {
    @TempDir Path temp;

    @Test
    void exactCurrentAliasCaseAndUuidResolutionPersistWithFloodgateMetadata() {
        DiaryStore store = store();
        UUID bedrock = UUID.randomUUID();
        store.observeIdentity(bedrock, "OldTracked");
        store.observeFloodgateIdentity(bedrock, "ExactBedrock", "123456789", "ANDROID");
        store.flushDurably().join();

        DiaryStore restarted = store();
        restarted.load();
        assertEquals(bedrock, restarted.resolveStoredPlayer(bedrock.toString()).uuid());
        assertEquals(bedrock, restarted.resolveStoredPlayer("exactbedrock").uuid());
        assertEquals(bedrock, restarted.resolveStoredPlayer("OLDTRACKED").uuid());
        PlayerIdentity identity = restarted.identityForTesting(bedrock);
        assertEquals("123456789", identity.xuid());
        assertEquals("ANDROID", identity.platform());
        assertTrue(identity.aliases().contains("OldTracked"));
    }

    @Test
    void ambiguousAliasIsRejectedWhileExactJavaCurrentNameWinsItsOwnPolicyStage() {
        DiaryStore store = store();
        UUID java = UUID.randomUUID();
        UUID bedrock = UUID.randomUUID();
        store.observeIdentity(java, "SharedOld");
        store.observeIdentity(java, "JavaCurrent");
        store.observeFloodgateIdentity(bedrock, "SharedOld", "42", "XBOX");
        store.observeIdentity(bedrock, "BedrockCurrent");

        assertEquals(IdentityResolution.Status.AMBIGUOUS,
                store.resolveStoredPlayer("sharedold").status());
        assertEquals(java, store.resolveStoredPlayer("javacurrent").uuid());
        assertEquals(bedrock, store.resolveStoredPlayer("bedrockcurrent").uuid());
    }

    @Test
    void floodgateAbsenceDoesNotPreventStartupOrStoredLookup() {
        Plugin plugin = plugin();
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer().getPluginManager()).thenReturn(manager);
        when(manager.isPluginEnabled("floodgate")).thenReturn(false);
        DiaryStore store = new DiaryStore(plugin);
        UUID owner = UUID.randomUUID();
        store.observeIdentity(owner, "OfflineBedrock");

        assertTrue(FloodgateIdentityAdapter.create(plugin, store).isEmpty());
        assertEquals(owner, store.resolveStoredPlayer("offlinebedrock").uuid());
    }

    private DiaryStore store() {
        return new DiaryStore(plugin());
    }

    private Plugin plugin() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run(); return null;
        });
        return plugin;
    }
}
