package com.p2wn.diary.logic;

import com.p2wn.diary.DiaryPlugin;
import com.p2wn.diary.events.DiaryDuplicateWarningEvent;
import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.*;

/**
 * Warn-only duplicate detector.
 * Now reports current holder name + coords instead of owner UUID.
 * Debounced per diaryId.
 */
public class DuplicateWatcher {

    private final DiaryPlugin plugin;
    private final Map<String, Long> lastWarnAt = new HashMap<>(); // diaryId -> epoch seconds

    public DuplicateWatcher(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean shouldWarn(String id) {
        long now = Instant.now().getEpochSecond();
        long last = lastWarnAt.getOrDefault(id, 0L);
        long debounce = plugin.configManager().cfg().getLong("duplicates.debounce-seconds", 60L);
        if (now - last >= debounce) {
            lastWarnAt.put(id, now);
            return true;
        }
        return false;
    }

    /** One occurrence of a diary while scanning. */
    private record Occurrence(String diaryId, String holderName, String whereTag, String coords) {}

    /* ---------- public entry points (unchanged signatures) ---------- */

    public void onPlayerJoinInventory(Player p) {
        if (!plugin.configManager().cfg().getBoolean("duplicates.warn-on-join", true)) return;

        List<Occurrence> found = new ArrayList<>();
        scanPlayerInventory(p, found);
        warnIfDuplicates(found, "player-inventory");
    }

    public void onInventoryOpen(HumanEntity who, Inventory inv) {
        if (!plugin.configManager().cfg().getBoolean("duplicates.warn-on-container-open", true)) return;

        List<Occurrence> found = new ArrayList<>();
        scanContainerInventory(who, inv, found);
        warnIfDuplicates(found, "container");
    }

    public void onChunkLoad(Chunk chunk) {
        if (!plugin.configManager().cfg().getBoolean("duplicates.warn-on-chunk-load", true)) return;

        List<Occurrence> found = new ArrayList<>();
        scanChunkItems(chunk, found);
        warnIfDuplicates(found, "chunk " + chunk.getX() + "," + chunk.getZ());
    }

    public void sweepStartup() {
        // Light sweep: online players and already-loaded chunks
        for (Player p : Bukkit.getOnlinePlayers()) onPlayerJoinInventory(p);
        Bukkit.getWorlds().forEach(w -> {
            for (Chunk c : w.getLoadedChunks()) onChunkLoad(c);
        });
    }

    /* ---------- scanning helpers ---------- */

    private void scanPlayerInventory(Player p, List<Occurrence> out) {
        if (p == null) return;
        String who = p.getName();
        String coords = coordsOf(p.getLocation());
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || !DiaryItem.isDiary(it)) continue;
            String id = DiaryItem.getDiaryId(it);
            if (id == null) continue;
            out.add(new Occurrence(id, who, "player", coords));
        }
    }

    private void scanContainerInventory(HumanEntity opener, Inventory inv, List<Occurrence> out) {
        if (inv == null) return;

        String who = opener != null ? opener.getName() : "unknown";
        String coords = "?";
        String where = "container";

        InventoryHolder holder = inv.getHolder();
        if (holder instanceof BlockState state) {
            Block b = state.getBlock();
            coords = coordsOf(b.getLocation());
            where = state.getType().name().toLowerCase(Locale.ROOT);
        } else if (holder instanceof Block blockHolder) {
            coords = coordsOf(blockHolder.getLocation());
        } else if (opener != null) {
            coords = coordsOf(opener.getLocation());
        }

        for (ItemStack it : inv.getContents()) {
            if (it == null || !DiaryItem.isDiary(it)) continue;
            String id = DiaryItem.getDiaryId(it);
            if (id == null) continue;
            out.add(new Occurrence(id, who, where, coords));
        }
    }

    private void scanChunkItems(Chunk chunk, List<Occurrence> out) {
        for (var e : chunk.getEntities()) {
            if (e instanceof Item item) {
                ItemStack st = item.getItemStack();
                if (st == null || !DiaryItem.isDiary(st)) continue;
                String id = DiaryItem.getDiaryId(st);
                if (id == null) continue;
                out.add(new Occurrence(id, "ground", "item", coordsOf(item.getLocation())));
            }
        }
    }

    /* ---------- warning assembly ---------- */

    private void warnIfDuplicates(List<Occurrence> found, String scopeTag) {
        if (found.isEmpty()) return;

        // Group by diaryId
        Map<String, List<Occurrence>> byId = new HashMap<>();
        for (Occurrence oc : found) byId.computeIfAbsent(oc.diaryId, k -> new ArrayList<>()).add(oc);

        byId.forEach((id, list) -> {
            if (list.size() <= 1) return;         // not a duplicate in this scope
            if (!shouldWarn(id)) return;          // debounce per id

            // Build a concise staff/console message with holders + coords
            String idShort = id.substring(0, Math.min(8, id.length()));
            StringBuilder sb = new StringBuilder();
            sb.append("[Diary] Duplicate detected (id ").append(idShort).append(") in ").append(scopeTag).append(": ");

            int maxList = 5;
            int i = 0;
            for (; i < list.size() && i < maxList; i++) {
                Occurrence oc = list.get(i);
                sb.append(oc.holderName).append(" @ ").append(oc.coords)
                        .append(" [").append(oc.whereTag).append("]");
                if (i < Math.min(list.size(), maxList) - 1) sb.append(", ");
            }
            if (list.size() > maxList) {
                sb.append(", +").append(list.size() - maxList).append(" more");
            }

            String msg = sb.toString();

            Bukkit.getPluginManager().callEvent(new DiaryDuplicateWarningEvent(id, list.size(), scopeTag, msg));

            // Console log
            plugin.getLogger().warning(msg);

            // Staff notify
            if (plugin.configManager().cfg().getBoolean("duplicates.staff-notify", true)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("diary.notify")) {
                        p.sendMessage("Â§e" + msg);
                    }
                }
            }
        });
    }

    private String coordsOf(Location loc) {
        if (loc == null) return "?";
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        String w = (loc.getWorld() != null) ? loc.getWorld().getName() : "?";
        return w + ":" + x + "," + y + "," + z;
    }
}
