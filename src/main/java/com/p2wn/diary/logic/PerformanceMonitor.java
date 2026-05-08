package com.p2wn.diary.logic;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicLong;

public final class PerformanceMonitor {

    private final Plugin plugin;
    private final AtomicLong diaryScansQueued = new AtomicLong();
    private final AtomicLong diaryScansCoalesced = new AtomicLong();
    private final AtomicLong inventoriesScanned = new AtomicLong();
    private final AtomicLong containersScanned = new AtomicLong();
    private final AtomicLong shulkersScanned = new AtomicLong();
    private final AtomicLong duplicateScanQueueSize = new AtomicLong();
    private final AtomicLong duplicateScanRepairs = new AtomicLong();
    private final AtomicLong voidTrackedItems = new AtomicLong();
    private final AtomicLong yamlSavesQueued = new AtomicLong();
    private final AtomicLong yamlSavesSkipped = new AtomicLong();
    private final AtomicLong yamlSavesRunning = new AtomicLong();
    private final AtomicLong yamlSavesFlushed = new AtomicLong();
    private final AtomicLong yamlSavesFailed = new AtomicLong();
    private final AtomicLong analyticsSavesQueued = new AtomicLong();
    private final AtomicLong analyticsSavesSkipped = new AtomicLong();
    private final AtomicLong analyticsSavesRunning = new AtomicLong();
    private final AtomicLong analyticsSavesFlushed = new AtomicLong();
    private final AtomicLong analyticsSavesFailed = new AtomicLong();
    private final AtomicLong deliveryQueueSize = new AtomicLong();
    private BukkitTask task;

    public PerformanceMonitor(Plugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stop();
        if (!plugin.getConfig().getBoolean("debug.performance.enabled", false)) {
            return;
        }
        long interval = Math.max(20L, plugin.getConfig().getLong("debug.performance.log-interval-seconds", 300L) * 20L);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::logAndReset, interval, interval);
    }

    public void shutdown() {
        stop();
    }

    public void diaryScanQueued() {
        diaryScansQueued.incrementAndGet();
    }

    public void diaryScanCoalesced() {
        diaryScansCoalesced.incrementAndGet();
    }

    public void inventoryScanned() {
        inventoriesScanned.incrementAndGet();
    }

    public void containerScanned() {
        containersScanned.incrementAndGet();
    }

    public void shulkerScanned() {
        shulkersScanned.incrementAndGet();
    }

    public void duplicateScanQueueSize(long size) {
        duplicateScanQueueSize.set(size);
    }

    public void duplicateScanRepair() {
        duplicateScanRepairs.incrementAndGet();
    }

    public void voidTrackedItems(long size) {
        voidTrackedItems.set(size);
    }

    public void yamlSaveQueued() {
        yamlSavesQueued.incrementAndGet();
    }

    public void yamlSaveSkipped() {
        yamlSavesSkipped.incrementAndGet();
    }

    public void yamlSaveRunning(long running) {
        yamlSavesRunning.set(running);
    }

    public void yamlSaveFlushed() {
        yamlSavesFlushed.incrementAndGet();
    }

    public void yamlSaveFailed() {
        yamlSavesFailed.incrementAndGet();
    }

    public void analyticsSaveQueued() {
        analyticsSavesQueued.incrementAndGet();
    }

    public void analyticsSaveSkipped() {
        analyticsSavesSkipped.incrementAndGet();
    }

    public void analyticsSaveRunning(long running) {
        analyticsSavesRunning.set(running);
    }

    public void analyticsSaveFlushed() {
        analyticsSavesFlushed.incrementAndGet();
    }

    public void analyticsSaveFailed() {
        analyticsSavesFailed.incrementAndGet();
    }

    public void deliveryQueueSize(long size) {
        deliveryQueueSize.set(size);
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void logAndReset() {
        plugin.getLogger().info("[perf] diaryScansQueued=" + diaryScansQueued.getAndSet(0)
                + " coalesced=" + diaryScansCoalesced.getAndSet(0)
                + " inventories=" + inventoriesScanned.getAndSet(0)
                + " containers=" + containersScanned.getAndSet(0)
                + " shulkers=" + shulkersScanned.getAndSet(0)
                + " duplicateQueue=" + duplicateScanQueueSize.get()
                + " duplicateRepairs=" + duplicateScanRepairs.getAndSet(0)
                + " voidTracked=" + voidTrackedItems.get()
                + " yamlQueued=" + yamlSavesQueued.getAndSet(0)
                + " yamlSkipped=" + yamlSavesSkipped.getAndSet(0)
                + " yamlRunning=" + yamlSavesRunning.get()
                + " yamlFlushed=" + yamlSavesFlushed.getAndSet(0)
                + " yamlFailed=" + yamlSavesFailed.getAndSet(0)
                + " analyticsQueued=" + analyticsSavesQueued.getAndSet(0)
                + " analyticsSkipped=" + analyticsSavesSkipped.getAndSet(0)
                + " analyticsRunning=" + analyticsSavesRunning.get()
                + " analyticsFlushed=" + analyticsSavesFlushed.getAndSet(0)
                + " analyticsFailed=" + analyticsSavesFailed.getAndSet(0)
                + " deliveryQueue=" + deliveryQueueSize.get());
    }
}
