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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DuplicateWatcher {

    private record Occurrence(String diaryId, String holderName, String whereTag, String coords) {}

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final DiaryItem diaryItem;

    private final Map<String, Long> lastWarnAt = new HashMap<>();
    private final Map<UUID, List<Occurrence>> playerSnapshots = new HashMap<>();
    private final Map<UUID, Occurrence> groundItemSnapshots = new HashMap<>();

    public DuplicateWatcher(Plugin plugin, ConfigManager configManager, DiaryItem diaryItem) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.diaryItem = diaryItem;
    }

    public void refreshPlayerSnapshot(Player player) {
        List<Occurrence> occurrences = new ArrayList<>();
        String holderName = player.getName();
        String coords = coordsOf(player.getLocation());
        scanInventoryContents(player.getInventory().getContents(), holderName, "player", coords, occurrences);
        scanInventoryContents(player.getEnderChest().getContents(), holderName, "ender_chest", coords, occurrences);
        playerSnapshots.put(player.getUniqueId(), occurrences);
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
        List<Occurrence> occurrences = scanChunkItems(chunk);
        if (configManager.cfg().getBoolean("duplicates.warn-on-chunk-load", true)) {
            warnForIds(occurrences, "chunk " + chunk.getX() + "," + chunk.getZ());
        }
    }

    public void onChunkUnload(Chunk chunk) {
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            groundItemSnapshots.remove(entity.getUniqueId());
        }
    }

    public void sweepStartup() {
        playerSnapshots.clear();
        groundItemSnapshots.clear();

        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerSnapshot(player);
        }
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkItems(chunk);
            }
        }

        if (configManager.cfg().getBoolean("duplicates.warn-on-startup", true)) {
            List<Occurrence> occurrences = new ArrayList<>();
            playerSnapshots.values().forEach(occurrences::addAll);
            occurrences.addAll(groundItemSnapshots.values());
            warnForIds(occurrences, "startup");
        }
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
        List<Occurrence> occurrences = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item && diaryItem.isDiary(item.getItemStack())) {
                String diaryId = diaryItem.getDiaryId(item.getItemStack());
                if (diaryId == null) {
                    continue;
                }
                Occurrence occurrence = new Occurrence(diaryId, "ground", "item", coordsOf(item.getLocation()));
                groundItemSnapshots.put(item.getUniqueId(), occurrence);
                occurrences.add(occurrence);
            }
        }
        return occurrences;
    }

    private void warnForIds(List<Occurrence> triggerOccurrences, String scopeTag) {
        if (triggerOccurrences == null || triggerOccurrences.isEmpty()) {
            return;
        }

        Map<String, List<Occurrence>> global = buildGlobalOccurrenceMap(triggerOccurrences);
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

    private Map<String, List<Occurrence>> buildGlobalOccurrenceMap(List<Occurrence> triggerOccurrences) {
        Map<String, List<Occurrence>> grouped = new LinkedHashMap<>();
        for (List<Occurrence> occurrences : playerSnapshots.values()) {
            addOccurrences(grouped, occurrences);
        }
        addOccurrences(grouped, groundItemSnapshots.values());
        addOccurrences(grouped, triggerOccurrences);
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
