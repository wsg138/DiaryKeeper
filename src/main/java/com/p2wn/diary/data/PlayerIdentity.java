package com.p2wn.diary.data;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class PlayerIdentity {
    private final UUID uuid;
    private String currentName;
    private final Set<String> aliases = new LinkedHashSet<>();
    private long lastSeen;

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
    public void observe(String name, long when) { if (name != null && !name.isBlank()) { currentName = name; aliases.add(name); } lastSeen = when; }
}
