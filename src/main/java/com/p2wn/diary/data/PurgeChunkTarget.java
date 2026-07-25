package com.p2wn.diary.data;

import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PurgeChunkTarget {

    private final UUID worldUuid;
    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private final Integer blockX;
    private final Integer blockY;
    private final Integer blockZ;
    private boolean completed;
    private int attempts;
    private String error;
    private int nextBlockEntityIndex;
    private int nextEntityIndex;
    private final Set<UUID> processedEntityUuids = new LinkedHashSet<>();

    public PurgeChunkTarget(UUID worldUuid, String worldName, int chunkX, int chunkZ,
                            Integer blockX, Integer blockY, Integer blockZ) {
        this.worldUuid = worldUuid;
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
    }

    public UUID worldUuid() { return worldUuid; }
    public String worldName() { return worldName; }
    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public Integer blockX() { return blockX; }
    public Integer blockY() { return blockY; }
    public Integer blockZ() { return blockZ; }
    public boolean completed() { return completed; }
    public int attempts() { return attempts; }
    public String error() { return error; }
    public int nextBlockEntityIndex() { return nextBlockEntityIndex; }
    public int nextEntityIndex() { return nextEntityIndex; }
    public Set<UUID> processedEntityUuids() { return processedEntityUuids; }

    public void complete() {
        completed = true;
        error = null;
    }

    public void finishWithError(String message) {
        completed = true;
        error = message;
    }

    public void fail(String message) {
        attempts++;
        error = message;
    }

    public void loadState(boolean completed, int attempts, String error,
                          int nextBlockEntityIndex, int nextEntityIndex) {
        this.completed = completed;
        this.attempts = attempts;
        this.error = error;
        this.nextBlockEntityIndex = nextBlockEntityIndex;
        this.nextEntityIndex = nextEntityIndex;
    }

    public void advance(int blockIndex, int entityIndex) {
        this.nextBlockEntityIndex = blockIndex;
        this.nextEntityIndex = entityIndex;
    }

    public void resetForRetry() {
        completed = false;
        attempts = 0;
        error = null;
    }

    public String key() {
        return (worldUuid == null ? worldName : worldUuid.toString()) + ":" + chunkX + ":" + chunkZ;
    }
}
