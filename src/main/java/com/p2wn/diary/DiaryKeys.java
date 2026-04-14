package com.p2wn.diary;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

public final class DiaryKeys {

    private final NamespacedKey isDiary;
    private final NamespacedKey ownerUuid;
    private final NamespacedKey diaryId;
    private final NamespacedKey lastDropper;

    public DiaryKeys(Plugin plugin) {
        this.isDiary = new NamespacedKey(plugin, "is_diary");
        this.ownerUuid = new NamespacedKey(plugin, "owner_uuid");
        this.diaryId = new NamespacedKey(plugin, "diary_id");
        this.lastDropper = new NamespacedKey(plugin, "last_dropper");
    }

    public NamespacedKey isDiary() {
        return isDiary;
    }

    public NamespacedKey ownerUuid() {
        return ownerUuid;
    }

    public NamespacedKey diaryId() {
        return diaryId;
    }

    public NamespacedKey lastDropper() {
        return lastDropper;
    }
}
