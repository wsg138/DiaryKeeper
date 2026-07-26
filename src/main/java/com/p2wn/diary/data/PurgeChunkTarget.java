package com.p2wn.diary.data;

import java.util.UUID;

@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
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
    private boolean loading;
    private transient Runnable dirtyCallback = () -> { };

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
    public boolean loading() { return loading; }
    public void attachDirtyCallback(Runnable callback) { dirtyCallback = callback == null ? () -> { } : callback; }

    public void complete() {
        completed = true;
        error = null;
        dirtyCallback.run();
    }

    public void finishWithError(String message) {
        completed = true;
        error = message;
        dirtyCallback.run();
    }

    public void fail(String message) {
        attempts++;
        error = message;
        dirtyCallback.run();
    }

    void loadState(boolean completed, int attempts, String error) {
        this.completed = completed;
        this.attempts = attempts;
        this.error = error;
    }

    public void resetForRetry() {
        completed = false;
        attempts = 0;
        error = null;
        dirtyCallback.run();
    }

    public void setLoading(boolean value) {
        loading = value;
        dirtyCallback.run();
    }

    public String key() {
        return (worldUuid == null ? worldName : worldUuid.toString()) + ":" + chunkX + ":" + chunkZ;
    }
}
