package com.p2wn.diary.data;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class DiaryLocationRecord {

    private final DiaryLocationType type;
    private final String description;
    private final UUID holderUuid;
    private final String holderName;
    private final String worldName;
    private final Integer x;
    private final Integer y;
    private final Integer z;
    private final String containerType;
    private final UUID entityUuid;
    private final List<String> nestedPath;
    private final long updatedAtEpochSeconds;

    public DiaryLocationRecord(
            DiaryLocationType type,
            String description,
            UUID holderUuid,
            String holderName,
            String worldName,
            Integer x,
            Integer y,
            Integer z,
            String containerType,
            UUID entityUuid,
            List<String> nestedPath,
            long updatedAtEpochSeconds
    ) {
        this.type = type;
        this.description = description;
        this.holderUuid = holderUuid;
        this.holderName = holderName;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.containerType = containerType;
        this.entityUuid = entityUuid;
        this.nestedPath = List.copyOf(nestedPath);
        this.updatedAtEpochSeconds = updatedAtEpochSeconds;
    }

    public DiaryLocationType type() {
        return type;
    }

    public String description() {
        return description;
    }

    public UUID holderUuid() {
        return holderUuid;
    }

    public String holderName() {
        return holderName;
    }

    public String worldName() {
        return worldName;
    }

    public Integer x() {
        return x;
    }

    public Integer y() {
        return y;
    }

    public Integer z() {
        return z;
    }

    public String containerType() {
        return containerType;
    }

    public UUID entityUuid() {
        return entityUuid;
    }

    public List<String> nestedPath() {
        return nestedPath;
    }

    public long updatedAtEpochSeconds() {
        return updatedAtEpochSeconds;
    }

    public void writeTo(ConfigurationSection section) {
        section.set("type", type.name());
        section.set("description", description);
        section.set("holderUuid", holderUuid == null ? null : holderUuid.toString());
        section.set("holderName", holderName);
        section.set("world", worldName);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("containerType", containerType);
        section.set("entityUuid", entityUuid == null ? null : entityUuid.toString());
        section.set("nestedPath", nestedPath);
        section.set("updatedAt", updatedAtEpochSeconds);
    }

    public static DiaryLocationRecord readFrom(ConfigurationSection section) {
        DiaryLocationType type = DiaryLocationType.valueOf(section.getString("type", DiaryLocationType.UNKNOWN.name()).toUpperCase(Locale.ROOT));
        return new DiaryLocationRecord(
                type,
                section.getString("description", "unknown"),
                parseUuid(section.getString("holderUuid")),
                section.getString("holderName"),
                section.getString("world"),
                section.contains("x") ? section.getInt("x") : null,
                section.contains("y") ? section.getInt("y") : null,
                section.contains("z") ? section.getInt("z") : null,
                section.getString("containerType"),
                parseUuid(section.getString("entityUuid")),
                new ArrayList<>(section.getStringList("nestedPath")),
                section.getLong("updatedAt", 0L)
        );
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
