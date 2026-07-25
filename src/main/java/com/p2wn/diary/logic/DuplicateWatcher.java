package com.p2wn.diary.logic;

import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.data.PurgeDestination;
import com.p2wn.diary.data.TrackedDiaryRecord;
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

@SuppressWarnings({"PMD.UseConcurrentHashMap", "PMD.AvoidInstantiatingObjectsInLoops", "PMD.NullAssignment"})
public final class DuplicateWatcher {

    private record Occurrence(String diaryId, String holderName, String whereTag, String coords) {}
    private record ChunkScanTarget(UUID worldId, int x, int z, int nextEntityIndex) {}
    private static final String DUPLICATE_SCAN_ENABLED = "duplicate-scan.enabled";
    private static final String DUPLICATE_SCAN_REPAIR_MODE = "duplicate-scan.repair-mode";
    private static final String DUPLICATE_SCAN_REPORT_ONLY = "duplicate-scan.report-only";
    private static final String DUPLICATES_STAFF_NOTIFY = "duplicates.staff-notify";
    private static final String DIARY_NOTIFY_PERMISSION = "diary.notify";
    private static final String UNKNOWN_COORDS = "?";
    private static final int WARNING_MESSAGE_CAPACITY = 128;

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final DiaryItem diaryItem;

    private final Map<String, Long> lastWarnAt = new HashMap<>();
    private final Map<UUID, List<Occurrence>> playerSnapshots = new HashMap<>();
    private final Map<UUID, List<Occurrence>> groundItemSnapshots = new HashMap<>();
    private final Deque<UUID> queuedPlayerScans = new ArrayDeque<>();
    private final Deque<ChunkScanTarget> queuedChunkScans = new ArrayDeque<>();
    private final Map<String, ChunkScanTarget> queuedChunkKeys = new HashMap<>();
    private BukkitTask scanTask;
    private BukkitTask periodicTask;
    private PerformanceMonitor performanceMonitor;
    private boolean purgeDuplicatesOnCurrentScan;

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
        if (!configManager.cfg().getBoolean(DUPLICATE_SCAN_ENABLED, true)) {
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
        if (item == null || item.isDead()) {
            return;
        }
        List<Occurrence> occurrences = new ArrayList<>();
        scanItemStack(item.getItemStack(), "ground", "item", coordsOf(item.getLocation()), occurrences);
        if (occurrences.isEmpty()) {
            groundItemSnapshots.remove(item.getUniqueId());
        } else {
            groundItemSnapshots.put(item.getUniqueId(), occurrences);
        }
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
        if (!configManager.cfg().getBoolean(DUPLICATE_SCAN_ENABLED, true)) {
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

    public void queueRepairScan() {
        purgeDuplicatesOnCurrentScan = true;
        queueGlobalScan();
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

        if (scanDiaryStack(stack, holderName, whereTag, coords, out)) {
            return;
        }
        scanNestedStackContents(stack, holderName, whereTag, coords, out);
    }

    private boolean scanDiaryStack(ItemStack stack, String holderName, String whereTag, String coords, List<Occurrence> out) {
        if (diaryItem.isDiary(stack)) {
            String diaryId = diaryItem.getDiaryId(stack);
            if (diaryId != null) {
                out.add(new Occurrence(diaryId, holderName, whereTag, coords));
            }
            return true;
        }
        return false;
    }

    private void scanNestedStackContents(ItemStack stack, String holderName, String whereTag, String coords, List<Occurrence> out) {
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
            if (entity instanceof Item item) {
                List<Occurrence> itemOccurrences = new ArrayList<>();
                scanItemStack(item.getItemStack(), "ground", "item", coordsOf(item.getLocation()), itemOccurrences);
                if (isRepairEnabled()) {
                    if (itemOccurrences.isEmpty()) {
                        groundItemSnapshots.remove(item.getUniqueId());
                    } else {
                        groundItemSnapshots.put(item.getUniqueId(), itemOccurrences);
                    }
                }
                occurrences.addAll(itemOccurrences);
            }
        }
        return new ChunkScanResult(occurrences, index, index < entities.length);
    }

    private record ChunkScanResult(List<Occurrence> occurrences, int nextEntityIndex, boolean incomplete) {}

    private void queueChunkScan(Chunk chunk) {
        if (chunk == null || !configManager.cfg().getBoolean(DUPLICATE_SCAN_ENABLED, true)) {
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
        if (scanTask != null || !configManager.cfg().getBoolean(DUPLICATE_SCAN_ENABLED, true)) {
            return;
        }
        scanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanTick, 1L, 1L);
    }

    private void scanTick() {
        int maxPlayers = Math.max(1, configManager.cfg().getInt("duplicate-scan.max-players-per-tick", 2));
        int maxChunks = Math.max(1, configManager.cfg().getInt("duplicate-scan.max-chunks-per-tick", 2));
        int maxEntities = Math.max(1, configManager.cfg().getInt("duplicate-scan.max-entities-per-tick", 80));

        scanQueuedPlayers(maxPlayers);
        scanQueuedChunks(maxChunks, maxEntities);

        updateQueueSizeCounter();
        if (queuedPlayerScans.isEmpty() && queuedChunkScans.isEmpty()) {
            purgeDuplicatesOnCurrentScan = false;
            stopScanTask();
        }
    }

    private void scanQueuedPlayers(int maxPlayers) {
        for (int i = 0; i < maxPlayers && !queuedPlayerScans.isEmpty(); i++) {
            scanQueuedPlayer(queuedPlayerScans.removeFirst());
        }
    }

    private void scanQueuedPlayer(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        List<Occurrence> occurrences = collectPlayerOccurrences(player, playerId);
        if (configManager.cfg().getBoolean("duplicates.warn-on-startup", true)) {
            warnForIds(occurrences, "staggered-player-scan");
        }
    }

    private List<Occurrence> collectPlayerOccurrences(Player player, UUID playerId) {
        if (!isRepairEnabled()) {
            return scanPlayerOccurrences(player);
        }
        refreshPlayerSnapshot(player);
        markDuplicateScanRepair();
        return playerSnapshots.getOrDefault(playerId, List.of());
    }

    private void scanQueuedChunks(int maxChunks, int maxEntities) {
        for (int i = 0; i < maxChunks && !queuedChunkScans.isEmpty(); i++) {
            scanQueuedChunk(queuedChunkScans.removeFirst(), maxEntities);
        }
    }

    private void scanQueuedChunk(ChunkScanTarget target, int maxEntities) {
        String key = target.worldId() + ":" + target.x() + ":" + target.z();
        queuedChunkKeys.remove(key);
        org.bukkit.World world = Bukkit.getWorld(target.worldId());
        if (world == null || !world.isChunkLoaded(target.x(), target.z())) {
            return;
        }
        ChunkScanResult result = scanChunkItems(world.getChunkAt(target.x(), target.z()), target.nextEntityIndex(), maxEntities);
        if (configManager.cfg().getBoolean("duplicates.warn-on-chunk-load", true)) {
            warnForIds(result.occurrences(), "staggered-chunk-scan " + target.x() + "," + target.z());
        }
        if (isRepairEnabled() && !result.occurrences().isEmpty()) {
            markDuplicateScanRepair();
        }
        if (result.incomplete()) {
            ChunkScanTarget resumed = new ChunkScanTarget(target.worldId(), target.x(), target.z(), result.nextEntityIndex());
            queuedChunkKeys.put(key, resumed);
            queuedChunkScans.addLast(resumed);
        }
    }

    private void markDuplicateScanRepair() {
        if (performanceMonitor != null) {
            performanceMonitor.duplicateScanRepair();
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
        return configManager.cfg().getBoolean(DUPLICATE_SCAN_REPAIR_MODE, true)
                && !configManager.cfg().getBoolean(DUPLICATE_SCAN_REPORT_ONLY, false);
    }

    private void warnForIds(List<Occurrence> triggerOccurrences, String scopeTag) {
        if (triggerOccurrences == null || triggerOccurrences.isEmpty()) {
            return;
        }

        Map<String, List<Occurrence>> global = buildGlobalOccurrenceMap();
        Map<Occurrence, Integer> triggerCounts = new HashMap<>();
        for (Occurrence trigger : triggerOccurrences) {
            List<Occurrence> matches = global.computeIfAbsent(trigger.diaryId(), ignored -> new ArrayList<>());
            int triggerCount = triggerCounts.merge(trigger, 1, Integer::sum);
            long existingCount = matches.stream().filter(trigger::equals).count();
            if (existingCount < triggerCount) {
                matches.add(trigger);
            }
        }
        Map<String, Occurrence> firstByDiaryId = new LinkedHashMap<>();
        for (Occurrence occurrence : triggerOccurrences) {
            firstByDiaryId.putIfAbsent(occurrence.diaryId(), occurrence);
        }

        for (Occurrence occurrence : firstByDiaryId.values()) {
            List<Occurrence> matches = global.getOrDefault(occurrence.diaryId(), List.of());
            if (plugin instanceof DiaryPlugin diaryPlugin && !matches.isEmpty()) {
                diaryPlugin.diaryPurgeService().onObservedCopy(
                        occurrence.diaryId(), scopeTag + " " + occurrence.coords(), matches.size());
            }
            if (matches.size() <= 1) {
                continue;
            }

            if (purgeDuplicatesOnCurrentScan && plugin instanceof DiaryPlugin diaryPlugin) {
                TrackedDiaryRecord record = diaryPlugin.diaryStore().getTrackedDiary(occurrence.diaryId());
                if (record != null && record.snapshot() != null
                        && diaryPlugin.diaryStore().getPurgeOperationsForDiary(occurrence.diaryId()).stream()
                        .noneMatch(operation -> !operation.terminal())) {
                    diaryPlugin.diaryPurgeService().begin(record, PurgeDestination.OWNER, null);
                }
            }
            if (!shouldWarn(occurrence.diaryId())) {
                continue;
            }

            String message = buildWarningMessage(occurrence.diaryId(), matches, scopeTag);
            Bukkit.getPluginManager().callEvent(new DiaryDuplicateWarningEvent(occurrence.diaryId(), matches.size(), scopeTag, message));
            plugin.getLogger().warning(message);

            if (configManager.cfg().getBoolean(DUPLICATES_STAFF_NOTIFY, true)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission(DIARY_NOTIFY_PERMISSION)) {
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
        for (List<Occurrence> occurrences : groundItemSnapshots.values()) {
            addOccurrences(grouped, occurrences);
        }
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

        StringBuilder builder = new StringBuilder(WARNING_MESSAGE_CAPACITY);
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
                    .append(']');
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
            return UNKNOWN_COORDS;
        }
        return location.getWorld().getName() + ":" + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }
}
