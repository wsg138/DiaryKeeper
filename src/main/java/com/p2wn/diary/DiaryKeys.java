package com.p2wn.diary;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class DiaryKeys {

    private final NamespacedKey isDiaryKey;
    private final NamespacedKey ownerUuidKey;
    private final NamespacedKey diaryIdKey;
    private final NamespacedKey lastDropperKey;

    public DiaryKeys(Plugin plugin) {
        this.isDiaryKey = new NamespacedKey(plugin, "is_diary");
        this.ownerUuidKey = new NamespacedKey(plugin, "owner_uuid");
        this.diaryIdKey = new NamespacedKey(plugin, "diary_id");
        this.lastDropperKey = new NamespacedKey(plugin, "last_dropper");
    }

    public NamespacedKey isDiary() {
        return isDiaryKey;
    }

    public NamespacedKey ownerUuid() {
        return ownerUuidKey;
    }

    public NamespacedKey diaryId() {
        return diaryIdKey;
    }

    public NamespacedKey lastDropper() {
        return lastDropperKey;
    }
}
