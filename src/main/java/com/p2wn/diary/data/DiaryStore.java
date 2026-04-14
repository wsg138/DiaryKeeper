package com.p2wn.diary.data;

import com.p2wn.diary.util.ItemIO;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public class DiaryStore {
    private final Plugin plugin;
    private final File file;
    private final FileConfiguration data;

    // playerUUID -> fixed diaryId
    private final Map<UUID, String> ids = new HashMap<>();

    // Issuance timestamps (for audit/guarantee)
    private final Map<UUID, Long> issuedAt = new HashMap<>();

    // Offline void returns: dropperUUID -> list of serialized ItemStacks (base64)
    private final Map<UUID, List<String>> voidQueue = new HashMap<>();

    // Track last loaded main world UUID for reset detection
    private String lastWorldUid;

    public DiaryStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "diaries.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        load();
    }

    private void load() {
        lastWorldUid = data.getString("lastWorldUid", null);

        if (data.isConfigurationSection("players")) {
            for (String key : Objects.requireNonNull(data.getConfigurationSection("players")).getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    String id = data.getString("players." + key + ".id");
                    if (id != null) ids.put(u, id);
                    long ts = data.getLong("players." + key + ".issuedAt", 0L);
                    if (ts > 0) issuedAt.put(u, ts);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        if (data.isConfigurationSection("voidQueue")) {
            for (String k : Objects.requireNonNull(data.getConfigurationSection("voidQueue")).getKeys(false)) {
                try {
                    UUID u = UUID.fromString(k);
                    List<String> list = data.getStringList("voidQueue." + k);
                    if (!list.isEmpty()) voidQueue.put(u, new ArrayList<>(list));
                } catch (Exception ignored) {}
            }
        }
    }

    public void save() {
        data.set("lastWorldUid", lastWorldUid);

        data.set("players", null);
        for (var e : ids.entrySet()) {
            UUID u = e.getKey();
            data.set("players." + u + ".id", e.getValue());
            Long ts = issuedAt.get(u);
            if (ts != null) data.set("players." + u + ".issuedAt", ts);
        }

        data.set("voidQueue", null);
        for (var e : voidQueue.entrySet()) {
            data.set("voidQueue." + e.getKey(), e.getValue());
        }

        try { data.save(file); } catch (IOException ex) { plugin.getLogger().warning("Failed to save diaries.yml"); }
    }

    public String getLastWorldUid() { return lastWorldUid; }
    public void setLastWorldUid(String uid) { this.lastWorldUid = uid; }

    /** Clear registry so everyone is considered "new" again (used on detected world reset). */
    public void resetAllPlayers() {
        ids.clear();
        issuedAt.clear();
        voidQueue.clear();
    }

    public String getOrCreateId(UUID uuid) {
        String id = ids.computeIfAbsent(uuid, u -> UUID.randomUUID().toString());
        if (!issuedAt.containsKey(uuid)) issuedAt.put(uuid, Instant.now().getEpochSecond());
        return id;
    }

    public String getId(UUID uuid) { return ids.get(uuid); }

    /** Mark issuance if not present (for logging/guarantee). */
    public void markIssued(UUID uuid) {
        issuedAt.putIfAbsent(uuid, Instant.now().getEpochSecond());
        save();
    }

    public boolean hasIssued(UUID uuid) { return issuedAt.containsKey(uuid); }

    /** Queue exact diary stack for offline delivery to a dropper. */
    public void queueVoidReturn(UUID dropper, ItemStack stack) {
        try {
            String ser = ItemIO.toBase64(stack);
            voidQueue.computeIfAbsent(dropper, k -> new ArrayList<>()).add(ser);
            save();
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to serialize diary for void return: " + ex.getMessage());
        }
    }

    /** Drain all queued returns for a player, deserializing them. */
    public List<ItemStack> drainVoidReturns(UUID dropper) {
        List<String> list = voidQueue.remove(dropper);
        save();
        if (list == null || list.isEmpty()) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();
        for (String s : list) {
            try { items.add(ItemIO.fromBase64(s)); }
            catch (IOException ex) { plugin.getLogger().warning("Failed to deserialize queued diary: " + ex.getMessage()); }
        }
        return items;
    }
}
