package com.p2wn.diary.data;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class PlayerIdentity {
    private final UUID uuid;
    private String currentName;
    private final Set<String> aliases = new LinkedHashSet<>();
    private long lastSeen;
    private String xuid;
    private String platform;

    public PlayerIdentity(UUID uuid, String currentName, long lastSeen) {
        this.uuid = uuid;
        this.currentName = currentName;
        this.lastSeen = lastSeen;
        if (currentName != null) aliases.add(currentName);
    }
    public UUID uuid() { return uuid; }
    public String currentName() { return currentName; }
    public Set<String> aliases() { return Set.copyOf(aliases); }
    public long lastSeen() { return lastSeen; }
    public String xuid() { return xuid; }
    public String platform() { return platform; }
    public boolean addAlias(String alias) { return alias != null && !alias.isBlank() && aliases.add(alias); }
    public boolean observe(String name, long when) {
        boolean changed = false;
        if (name != null && !name.isBlank()) {
            changed |= aliases.add(name);
            if (when >= lastSeen && !java.util.Objects.equals(currentName, name)) {
                currentName = name;
                changed = true;
            }
        }
        if (when > lastSeen) { lastSeen = when; changed = true; }
        return changed;
    }
    public boolean observeFloodgate(String name, String observedXuid, String observedPlatform, long when) {
        boolean changed = observe(name, when);
        if (!java.util.Objects.equals(xuid, observedXuid)) { xuid = observedXuid; changed = true; }
        if (!java.util.Objects.equals(platform, observedPlatform)) { platform = observedPlatform; changed = true; }
        return changed;
    }
    public void loadFloodgate(String loadedXuid, String loadedPlatform) {
        xuid = loadedXuid;
        platform = loadedPlatform;
    }
}
