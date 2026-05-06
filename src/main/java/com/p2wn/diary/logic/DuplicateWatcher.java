package com.p2wn.diary.logic;

import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.events.DiaryDuplicateWarningEvent;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DuplicateWatcher {

    private record Occurrence(String diaryId, String holderName, String whereTag, String coords) {}
    private record ChunkScanTarget(UUID worldId, int x, int z, int nextEntityIndex) {}

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final DiaryItem diaryItem;

    private final Map<String, Long> lastWarnAt = new HashMap<>();
    private final Map<UUID, List<Occurrence>> playerSnapshots = new HashMap<>();
    private final Map<UUID, Occurrence> groundItemSnapshots = new HashMap<>();
    private final Deque<UUID> queuedPlayerScans = new ArrayDeque<>();
    private final Deque<ChunkScanTarget> queuedChunkScans = new ArrayDeque<>();
    private final Map<String, ChunkScanTarget> queuedChunkKeys = new HashMap<>();
    private BukkitTask scanTask;
    private BukkitTask periodicTask;
    private PerformanceMonitor performanceMonitor;

    public DuplicateWatcher(Plugin plugin, ConfigManager configManager, DiaryItem diaryItem) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.diaryItem = diaryItem;
    }

    public void setPerformanceMonitor(PerformanceMonitor performanceMonitor) {
        this.performanceMonitor = performanceMonitor;
    }

    public void reloadSettings() {
        stopPeriodicTask();
        if (!configManager.cfg().getBoolean("duplicate-scan.enabled", true)) {
            stopScanTask();
            queuedPlayerScans.clear();
            queuedChunkScans.clear();
            queuedChunkKeys.clear();
            updateQueueSizeCounter();
            return;
        }
        long interval = Math.max(1L, configManager.cfg().getLong("duplicate-scan.interval-minutes", 10L)) * 60L * 20L;
        periodicTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::queueGlobalScan, interval, interval);
        ensureScanTask();
    }

    public void shutdown() {
        stopScanTask();
        stopPeriodicTask();
        queuedPlayerScans.clear();
        queuedChunkScans.clear();
        queuedChunkKeys.clear();
        updateQueueSizeCounter();
    }

    public void refreshPlayerSnapshot(Player player) {
        playerSnapshots.put(player.getUniqueId(), scanPlayerOccurrences(player));
    }

    private List<Occurrence> scanPlayerOccurrences(Player player) {
        List<Occurrence> occurrences = new ArrayList<>();
        String holderName = player.getName();
        String coords = coordsOf(player.getLocation());
        scanInventoryContents(player.getInventory().getContents(), holderName, "player", coords, occurrences);
        scanInventoryContents(player.getEnderChest().getContents(), holderName, "ender_chest", coords, occurrences);
        return occurrences;
    }

    public void removePlayerSnapshot(UUID playerId) {
        playerSnapshots.remove(playerId);
    }

    public void refreshGroundItemSnapshot(Item item) {
        if (item == null || item.isDead() || !diaryItem.isDiary(item.getItemStack())) {
            return;
        }
        String diaryId = diaryItem.getDiaryId(item.getItemStack());
        if (diaryId == null) {
            return;
        }
        groundItemSnapshots.put(item.getUniqueId(), new Occurrence(diaryId, "ground", "item", coordsOf(item.getLocation())));
    }

    public void removeGroundItemSnapshot(UUID itemId) {
        groundItemSnapshots.remove(itemId);
    }

    public void onPlayerJoinInventory(Player player) {
        if (!configManager.cfg().getBoolean("duplicates.warn-on-join", true)) {
            return;
        }
        warnForIds(playerSnapshots.getOrDefault(player.getUniqueId(), Collections.emptyList()), "player-inventory");
    }

    public void onInventoryOpen(HumanEntity opener, Inventory inventory) {
        if (!configManager.cfg().getBoolean("duplicates.warn-on-container-open", true)) {
            return;
        }
        warnForIds(scanContainerInventory(opener, inventory), "container");
    }

    public void onChunkLoad(Chunk chunk) {
        queueChunkScan(chunk);
    }

    public void onChunkUnload(Chunk chunk) {
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            groundItemSnapshots.remove(entity.getUniqueId());
        }
    }

    public void sweepStartup() {
        playerSnapshots.clear();
        groundItemSnapshots.clear();
        queuedPlayerScans.clear();
        queuedChunkScans.clear();
        queuedChunkKeys.clear();
        queueGlobalScan();
    }

    public void queueGlobalScan() {
        if (!configManager.cfg().getBoolean("duplicate-scan.enabled", true)) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            queuePlayerScan(player.getUniqueId());
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queueChunkScan(chunk);
            }
        }
        ensureScanTask();
        updateQueueSizeCounter();
    }

    public void queuePlayerScan(UUID playerId) {
        if (playerId == null || queuedPlayerScans.contains(playerId)) {
            if (performanceMonitor != null) {
                performanceMonitor.diaryScanCoalesced();
            }
            return;
        }
        queuedPlayerScans.addLast(playerId);
        if (performanceMonitor != null) {
            performanceMonitor.diaryScanQueued();
        }
        ensureScanTask();
    }

    private List<Occurrence> scanContainerInventory(HumanEntity opener, Inventory inventory) {
        if (inventory == null) {
            return Collections.emptyList();
        }

        String holderName = opener != null ? opener.getName() : "unknown";
        String whereTag = "container";
        String coords = opener != null ? coordsOf(opener.getLocation()) : "?";

        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState state) {
            Block block = state.getBlock();
            whereTag = state.getType().name().toLowerCase(Locale.ROOT);
            coords = coordsOf(block.getLocation());
        } else if (holder instanceof Block block) {
            coords = coordsOf(block.getLocation());
        }

        List<Occurrence> occurrences = new ArrayList<>();
        scanInventoryContents(inventory.getContents(), holderName, whereTag, coords, occurrences);
        return occurrences;
    }

    private void scanInventoryContents(ItemStack[] contents, String holderName, String whereTag, String coords, List<Occurrence> out) {
        if (contents == null) {
            return;
        }
        for (ItemStack stack : contents) {
            scanItemStack(stack, holderName, whereTag, coords, out);
        }
    }

    private void scanItemStack(ItemStack stack, String holderName, String whereTag, String coords, List<Occurrence> out) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }

        if (diaryItem.isDiary(stack)) {
            String diaryId = diaryItem.getDiaryId(stack);
            if (diaryId != null) {
                out.add(new Occurrence(diaryId, holderName, whereTag, coords));
            }
            return;
        }

        if (stack.getType() == Material.BUNDLE && stack.hasItemMeta() && stack.getItemMeta() instanceof BundleMeta bundleMeta) {
            for (ItemStack nested : bundleMeta.getItems()) {
                scanItemStack(nested, holderName, "bundle->" + whereTag, coords, out);
            }
            return;
        }

        if (stack.hasItemMeta() && stack.getItemMeta() instanceof BlockStateMeta blockStateMeta && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            for (ItemStack nested : shulkerBox.getInventory().getContents()) {
                scanItemStack(nested, holderName, "shulker->" + whereTag, coords, out);
            }
        }
    }

    private List<Occurrence> scanChunkItems(Chunk chunk) {
        return scanChunkItems(chunk, 0, Integer.MAX_VALUE).occurrences();
    }

    private ChunkScanResult scanChunkItems(Chunk chunk, int startIndex, int maxEntities) {
        List<Occurrence> occurrences = new ArrayList<>();
        org.bukkit.entity.Entity[] entities = chunk.getEntities();
        int processed = 0;
        int index = Math.max(0, startIndex);
        for (; index < entities.length && processed < maxEntities; index++) {
            org.bukkit.entity.Entity entity = entities[index];
            processed++;
            if (entity instanceof Item item && diaryItem.isDiary(item.getItemStack())) {
                String diaryId = diaryItem.getDiaryId(item.getItemStack());
                if (diaryId == null) {
                    continue;
                }
                Occurrence occurrence = new Occurrence(diaryId, "ground", "item", coordsOf(item.getLocation()));
                if (isRepairEnabled()) {
                    groundItemSnapshots.put(item.getUniqueId(), occurrence);
                }
                occurrences.add(occurrence);
            }
        }
        return new ChunkScanResult(occurrences, index, index < entities.length);
    }

    private record ChunkScanResult(List<Occurrence> occurrences, int nextEntityIndex, boolean incomplete) {}

    private void queueChunkScan(Chunk chunk) {
        if (chunk == null || !configManager.cfg().getBoolean("duplicate-scan.enabled", true)) {
            return;
        }
        String key = chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (queuedChunkKeys.containsKey(key)) {
            if (performanceMonitor != null) {
                performanceMonitor.diaryScanCoalesced();
            }
            return;
        }
        ChunkScanTarget target = new ChunkScanTarget(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ(), 0);
        queuedChunkKeys.put(key, target);
        queuedChunkScans.addLast(target);
        if (performanceMonitor != null) {
            performanceMonitor.diaryScanQueued();
        }
        ensureScanTask();
        updateQueueSizeCounter();
    }

    private void ensureScanTask() {
        if (scanTask != null || !configManager.cfg().getBoolean("duplicate-scan.enabled", true)) {
            return;
        }
        scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanTick, 1L, 1L);
    }

    private void scanTick() {
        int maxPlayers = Math.max(1, configManager.cfg().getInt("duplicate-scan.max-players-per-tick", 2));
        int maxChunks = Math.max(1, configManager.cfg().getInt("duplicate-scan.max-chunks-per-tick", 2));
        int maxEntities = Math.max(1, configManager.cfg().getInt("duplicate-scan.max-entities-per-tick", 80));

        for (int i = 0; i < maxPlayers && !queuedPlayerScans.isEmpty(); i++) {
            UUID playerId = queuedPlayerScans.removeFirst();
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                List<Occurrence> occurrences;
                if (isRepairEnabled()) {
                    refreshPlayerSnapshot(player);
                    occurrences = playerSnapshots.getOrDefault(playerId, List.of());
                } else {
                    occurrences = scanPlayerOccurrences(player);
                }
                if (isRepairEnabled()) {
                    if (performanceMonitor != null) {
                        performanceMonitor.duplicateScanRepair();
                    }
                }
                if (configManager.cfg().getBoolean("duplicates.warn-on-startup", true)) {
                    warnForIds(occurrences, "staggered-player-scan");
                }
            }
        }

        for (int i = 0; i < maxChunks && !queuedChunkScans.isEmpty(); i++) {
            ChunkScanTarget target = queuedChunkScans.removeFirst();
            String key = target.worldId() + ":" + target.x() + ":" + target.z();
            queuedChunkKeys.remove(key);
            org.bukkit.World world = Bukkit.getWorld(target.worldId());
            if (world == null || !world.isChunkLoaded(target.x(), target.z())) {
                continue;
            }
            Chunk chunk = world.getChunkAt(target.x(), target.z());
            ChunkScanResult result = scanChunkItems(chunk, target.nextEntityIndex(), maxEntities);
            if (configManager.cfg().getBoolean("duplicates.warn-on-chunk-load", true)) {
                warnForIds(result.occurrences(), "staggered-chunk-scan " + target.x() + "," + target.z());
            }
            if (isRepairEnabled() && !result.occurrences().isEmpty()) {
                if (performanceMonitor != null) {
                    performanceMonitor.duplicateScanRepair();
                }
            }
            if (result.incomplete()) {
                ChunkScanTarget resumed = new ChunkScanTarget(target.worldId(), target.x(), target.z(), result.nextEntityIndex());
                queuedChunkKeys.put(key, resumed);
                queuedChunkScans.addLast(resumed);
            }
        }

        updateQueueSizeCounter();
        if (queuedPlayerScans.isEmpty() && queuedChunkScans.isEmpty()) {
            stopScanTask();
        }
    }

    private void stopScanTask() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    private void stopPeriodicTask() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    private void updateQueueSizeCounter() {
        if (performanceMonitor != null) {
            performanceMonitor.duplicateScanQueueSize(queuedPlayerScans.size() + queuedChunkScans.size());
        }
    }

    private boolean isRepairEnabled() {
        return configManager.cfg().getBoolean("duplicate-scan.repair-mode", true)
                && !configManager.cfg().getBoolean("duplicate-scan.report-only", false);
    }

    private void warnForIds(List<Occurrence> triggerOccurrences, String scopeTag) {
        if (triggerOccurrences == null || triggerOccurrences.isEmpty()) {
            return;
        }

        Map<String, List<Occurrence>> global = buildGlobalOccurrenceMap();
        Map<String, Occurrence> firstByDiaryId = new LinkedHashMap<>();
        for (Occurrence occurrence : triggerOccurrences) {
            firstByDiaryId.putIfAbsent(occurrence.diaryId(), occurrence);
        }

        for (Occurrence occurrence : firstByDiaryId.values()) {
            List<Occurrence> matches = global.getOrDefault(occurrence.diaryId(), List.of());
            if (matches.size() <= 1 || !shouldWarn(occurrence.diaryId())) {
                continue;
            }

            String message = buildWarningMessage(occurrence.diaryId(), matches, scopeTag);
            Bukkit.getPluginManager().callEvent(new DiaryDuplicateWarningEvent(occurrence.diaryId(), matches.size(), scopeTag, message));
            plugin.getLogger().warning(message);

            if (configManager.cfg().getBoolean("duplicates.staff-notify", true)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("diary.notify")) {
                        player.sendMessage(configManager.color("&e" + message));
                    }
                }
            }
        }
    }

    private Map<String, List<Occurrence>> buildGlobalOccurrenceMap() {
        Map<String, List<Occurrence>> grouped = new LinkedHashMap<>();
        for (List<Occurrence> occurrences : playerSnapshots.values()) {
            addOccurrences(grouped, occurrences);
        }
        addOccurrences(grouped, groundItemSnapshots.values());
        return grouped;
    }

    private void addOccurrences(Map<String, List<Occurrence>> grouped, Iterable<Occurrence> occurrences) {
        for (Occurrence occurrence : occurrences) {
            grouped.computeIfAbsent(occurrence.diaryId(), ignored -> new ArrayList<>()).add(occurrence);
        }
    }

    private boolean shouldWarn(String diaryId) {
        long now = Instant.now().getEpochSecond();
        long debounce = Math.max(1L, configManager.cfg().getLong("duplicates.debounce-seconds", 60L));
        long lastWarn = lastWarnAt.getOrDefault(diaryId, 0L);
        if (now - lastWarn < debounce) {
            return false;
        }
        lastWarnAt.put(diaryId, now);
        return true;
    }

    private String buildWarningMessage(String diaryId, List<Occurrence> occurrences, String scopeTag) {
        String shortId = diaryId.substring(0, Math.min(8, diaryId.length()));
        int maxListed = Math.max(1, configManager.cfg().getInt("duplicates.max-listed-occurrences", 5));

        StringBuilder builder = new StringBuilder();
        builder.append("[Diary] Duplicate detected (id ")
                .append(shortId)
                .append(") in ")
                .append(scopeTag)
                .append(": ");

        for (int i = 0; i < occurrences.size() && i < maxListed; i++) {
            Occurrence occurrence = occurrences.get(i);
            builder.append(occurrence.holderName())
                    .append(" @ ")
                    .append(occurrence.coords())
                    .append(" [")
                    .append(occurrence.whereTag())
                    .append("]");
            if (i < Math.min(occurrences.size(), maxListed) - 1) {
                builder.append(", ");
            }
        }

        if (occurrences.size() > maxListed) {
            builder.append(", +").append(occurrences.size() - maxListed).append(" more");
        }
        return builder.toString();
    }

    private String coordsOf(Location location) {
        if (location == null || location.getWorld() == null) {
            return "?";
        }
        return location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
