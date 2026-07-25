package com.p2wn.diary.data;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class DiaryLocationRecord {

    private final DiaryLocationType type;
    private final String description;
    private final UUID holderUuid;
    private final String holderName;
    private final UUID worldUuid;
    private final String worldName;
    private final Integer x;
    private final Integer y;
    private final Integer z;
    private final String containerType;
    private final UUID entityUuid;
    private final List<String> nestedPath;
    private final String inventoryScope;
    private final Integer slot;
    private final long firstSeenAtEpochSeconds;
    private final long lastSeenAtEpochSeconds;
    private final boolean active;

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
        this(type, description, holderUuid, holderName, null, worldName, x, y, z,
                containerType, entityUuid, nestedPath, null, null,
                updatedAtEpochSeconds, updatedAtEpochSeconds, true);
    }

    public DiaryLocationRecord(
            DiaryLocationType type,
            String description,
            UUID holderUuid,
            String holderName,
            UUID worldUuid,
            String worldName,
            Integer x,
            Integer y,
            Integer z,
            String containerType,
            UUID entityUuid,
            List<String> nestedPath,
            String inventoryScope,
            Integer slot,
            long firstSeenAtEpochSeconds,
            long lastSeenAtEpochSeconds,
            boolean active
    ) {
        this.type = type;
        this.description = description;
        this.holderUuid = holderUuid;
        this.holderName = holderName;
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.containerType = containerType;
        this.entityUuid = entityUuid;
        this.nestedPath = List.copyOf(nestedPath);
        this.inventoryScope = inventoryScope;
        this.slot = slot;
        this.firstSeenAtEpochSeconds = firstSeenAtEpochSeconds;
        this.lastSeenAtEpochSeconds = lastSeenAtEpochSeconds;
        this.active = active;
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

    public UUID worldUuid() {
        return worldUuid;
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
        return lastSeenAtEpochSeconds;
    }

    public String inventoryScope() { return inventoryScope; }
    public Integer slot() { return slot; }
    public long firstSeenAtEpochSeconds() { return firstSeenAtEpochSeconds; }
    public long lastSeenAtEpochSeconds() { return lastSeenAtEpochSeconds; }
    public boolean active() { return active; }

    public DiaryLocationRecord observedAgain(DiaryLocationRecord observation) {
        return new DiaryLocationRecord(type, observation.description, holderUuid, observation.holderName,
                observation.worldUuid, observation.worldName, observation.x, observation.y, observation.z,
                observation.containerType, observation.entityUuid, observation.nestedPath,
                observation.inventoryScope, observation.slot, firstSeenAtEpochSeconds,
                observation.lastSeenAtEpochSeconds, true);
    }

    public DiaryLocationRecord inactive(long when) {
        return new DiaryLocationRecord(type, description, holderUuid, holderName, worldUuid, worldName,
                x, y, z, containerType, entityUuid, nestedPath, inventoryScope, slot,
                firstSeenAtEpochSeconds, Math.max(lastSeenAtEpochSeconds, when), false);
    }

    public DiaryLocationRecord withWorldUuid(UUID value) {
        return new DiaryLocationRecord(type, description, holderUuid, holderName, value, worldName,
                x, y, z, containerType, entityUuid, nestedPath, inventoryScope, slot,
                firstSeenAtEpochSeconds, lastSeenAtEpochSeconds, active);
    }

    public String identityKey() {
        String worldKey = worldUuid == null
                ? (worldName == null ? null : worldName.toLowerCase(Locale.ROOT)) : worldUuid.toString();
        return type + "|" + holderUuid + "|" + worldKey + "|"
                + x + "|" + y + "|" + z + "|" + entityUuid + "|" + inventoryScope + "|"
                + slot + "|" + String.join("/", nestedPath);
    }

    public void writeTo(ConfigurationSection section) {
        section.set("type", type.name());
        section.set("description", description);
        section.set("holderUuid", holderUuid == null ? null : holderUuid.toString());
        section.set("holderName", holderName);
        section.set("worldUuid", worldUuid == null ? null : worldUuid.toString());
        section.set("world", worldName);
        section.set("x", x);
        section.set("y", y);
        section.set("z", z);
        section.set("containerType", containerType);
        section.set("entityUuid", entityUuid == null ? null : entityUuid.toString());
        section.set("nestedPath", nestedPath);
        section.set("inventoryScope", inventoryScope);
        section.set("slot", slot);
        section.set("firstSeenAt", firstSeenAtEpochSeconds);
        section.set("lastSeenAt", lastSeenAtEpochSeconds);
        section.set("active", active);
        section.set("updatedAt", lastSeenAtEpochSeconds);
    }

    public static DiaryLocationRecord readFrom(ConfigurationSection section) {
        DiaryLocationType type = parseType(section.getString("type"));
        return new DiaryLocationRecord(
                type,
                section.getString("description", "unknown"),
                parseUuid(section.getString("holderUuid")),
                section.getString("holderName"),
                parseUuid(section.getString("worldUuid")),
                section.getString("world"),
                section.contains("x") ? section.getInt("x") : null,
                section.contains("y") ? section.getInt("y") : null,
                section.contains("z") ? section.getInt("z") : null,
                section.getString("containerType"),
                parseUuid(section.getString("entityUuid")),
                new ArrayList<>(section.getStringList("nestedPath")),
                section.getString("inventoryScope"),
                section.contains("slot") ? section.getInt("slot") : null,
                section.getLong("firstSeenAt", section.getLong("updatedAt", 0L)),
                section.getLong("lastSeenAt", section.getLong("updatedAt", 0L)),
                section.getBoolean("active", true)
        );
    }

    private static DiaryLocationType parseType(String raw) {
        try {
            return raw == null ? DiaryLocationType.UNKNOWN : DiaryLocationType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DiaryLocationType.UNKNOWN;
        }
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
