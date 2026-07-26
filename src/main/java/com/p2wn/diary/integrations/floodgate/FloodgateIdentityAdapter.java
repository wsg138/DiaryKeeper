package com.p2wn.diary.integrations.floodgate;

import com.p2wn.diary.data.DiaryStore;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/** Reflection boundary that keeps Floodgate completely optional at class-load time. */
public final class FloodgateIdentityAdapter {
    private final Plugin plugin;
    private final DiaryStore store;
    private final Object api;
    private final Method isFloodgatePlayer;
    private final Method getPlayer;

    FloodgateIdentityAdapter(Plugin plugin, DiaryStore store, Object api,
                             Method isFloodgatePlayer, Method getPlayer) {
        this.plugin = plugin;
        this.store = store;
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
        this.getPlayer = getPlayer;
    }

    public static Optional<FloodgateIdentityAdapter> create(Plugin plugin, DiaryStore store) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("floodgate")) return Optional.empty();
        try {
            Class<?> apiType = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiType.getMethod("getInstance").invoke(null);
            return Optional.of(new FloodgateIdentityAdapter(plugin, store, api,
                    apiType.getMethod("isFloodgatePlayer", UUID.class),
                    apiType.getMethod("getPlayer", UUID.class)));
        } catch (ReflectiveOperationException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Floodgate is enabled but its identity API could not be initialized.", failure);
            return Optional.empty();
        }
    }

    public boolean observe(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            if (!Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, uuid))) return false;
            Object floodgatePlayer = getPlayer.invoke(api, uuid);
            if (floodgatePlayer == null) return false;
            String username = stringValue(floodgatePlayer, "getUsername", player.getName());
            String xuid = stringValue(floodgatePlayer, "getXuid", null);
            String platform = stringValue(floodgatePlayer, "getDeviceOs", "UNKNOWN");
            store.observeFloodgateIdentity(uuid, username, xuid, platform);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not record Floodgate identity for " + player.getUniqueId() + ".", failure);
            return false;
        }
    }

    private String stringValue(Object target, String method, String fallback) throws ReflectiveOperationException {
        Object value = target.getClass().getMethod(method).invoke(target);
        return value == null ? fallback : value.toString();
    }
}
