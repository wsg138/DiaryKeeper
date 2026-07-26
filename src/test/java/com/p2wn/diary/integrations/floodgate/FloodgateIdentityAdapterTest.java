package com.p2wn.diary.integrations.floodgate;

import com.p2wn.diary.data.DiaryStore;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class FloodgateIdentityAdapterTest {
    @Test
    void availableAdapterStoresExactMetadataAgainstActualUuid() throws Exception {
        Plugin plugin = mock(Plugin.class);
        DiaryStore store = mock(DiaryStore.class);
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(".Fallback");
        FakeApi api = new FakeApi(uuid);
        Constructor<FloodgateIdentityAdapter> constructor = FloodgateIdentityAdapter.class
                .getDeclaredConstructor(Plugin.class, DiaryStore.class, Object.class,
                        java.lang.reflect.Method.class, java.lang.reflect.Method.class);
        constructor.setAccessible(true);
        FloodgateIdentityAdapter adapter = constructor.newInstance(plugin, store, api,
                FakeApi.class.getMethod("isFloodgatePlayer", UUID.class),
                FakeApi.class.getMethod("getPlayer", UUID.class));

        assertTrue(adapter.observe(player));
        verify(store).observeFloodgateIdentity(uuid, "ExactBedrock", "987654321", "ANDROID");
    }

    public static final class FakeApi {
        private final UUID accepted;
        FakeApi(UUID accepted) { this.accepted = accepted; }
        public boolean isFloodgatePlayer(UUID uuid) { return accepted.equals(uuid); }
        public FakeFloodgatePlayer getPlayer(UUID uuid) {
            return accepted.equals(uuid) ? new FakeFloodgatePlayer() : null;
        }
    }

    public static final class FakeFloodgatePlayer {
        public String getUsername() { return "ExactBedrock"; }
        public String getXuid() { return "987654321"; }
        public String getDeviceOs() { return "ANDROID"; }
    }
}
