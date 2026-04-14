package com.p2wn.diary.item;

import com.p2wn.diary.DiaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DiaryItem {

    private DiaryItem() {}

    public static boolean isDiary(ItemStack stack) {
        if (stack == null || stack.getType() != Material.WRITABLE_BOOK) return false;
        var meta = stack.getItemMeta();
        if (meta == null) return false;
        Boolean marker = meta.getPersistentDataContainer()
                .get(DiaryPlugin.get().keyIsDiary(), PersistentDataType.BOOLEAN);
        return marker != null && marker;
    }

    public static UUID getOwner(ItemStack stack) {
        if (!isDiary(stack)) return null;
        String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(DiaryPlugin.get().keyOwnerUuid(), PersistentDataType.STRING);
        try { return raw == null ? null : UUID.fromString(raw); }
        catch (Exception e) { return null; }
    }

    public static String getDiaryId(ItemStack stack) {
        if (!isDiary(stack)) return null;
        return stack.getItemMeta().getPersistentDataContainer()
                .get(DiaryPlugin.get().keyDiaryId(), PersistentDataType.STRING);
    }

    public static ItemStack mintFor(Player owner) {
        var pl = DiaryPlugin.get();
        var cfg = pl.configManager().cfg();

        String id = pl.diaryStore().getOrCreateId(owner.getUniqueId());

        ItemStack book = new ItemStack(Material.WRITABLE_BOOK, 1);
        var meta = book.getItemMeta();
        if (!(meta instanceof BookMeta bm)) return book;

        applyCanonicalAppearance(bm, owner.getUniqueId(), owner.getName(), id);

        book.setItemMeta(bm);
        return book;
    }

    /** Refresh cosmetic owner name (e.g., username changed). */
    public static void refreshOwnerCosmetics(Player owner, ItemStack stack) {
        if (!isDiary(stack)) return;
        var id = getDiaryId(stack);
        if (id == null) return;
        var meta = stack.getItemMeta();
        if (!(meta instanceof BookMeta bm)) return;

        applyCanonicalAppearance(bm, owner.getUniqueId(), owner.getName(), id);
        stack.setItemMeta(bm);
    }

    /** Force title/lore/glint back to canonical (blocks rename/lore stripping by other plugins). */
    public static void canonicalize(ItemStack stack) {
        if (!isDiary(stack)) return;
        var meta = stack.getItemMeta();
        if (!(meta instanceof BookMeta bm)) return;

        UUID owner = getOwner(stack);
        String id = getDiaryId(stack);
        if (owner == null || id == null) return;

        String ownerName = owner.toString();
        OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
        if (op != null && op.getName() != null) ownerName = op.getName();

        applyCanonicalAppearance(bm, owner, ownerName, id);
        stack.setItemMeta(bm);
    }

    private static void applyCanonicalAppearance(BookMeta bm, UUID ownerUuid, String ownerName, String id) {
        var pl = DiaryPlugin.get();
        var cfg = pl.configManager().cfg();

        String name = cfg.getString("appearance.name-format", "&d{owner}'s Diary")
                .replace("{owner}", ownerName);
        bm.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> loreCfg = cfg.getStringList("appearance.lore");
        List<String> lore = new ArrayList<>();
        for (String line : loreCfg) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    line.replace("{owner}", ownerName)
                            .replace("{id-short}", id.substring(0, 8))));
        }
        bm.setLore(lore);

        if (cfg.getBoolean("appearance.enchanted-glint", true)) {
            bm.addEnchant(Enchantment.UNBREAKING, 1, true);
            bm.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            bm.removeEnchant(Enchantment.UNBREAKING);
        }

        var pdc = bm.getPersistentDataContainer();
        pdc.set(pl.keyIsDiary(), PersistentDataType.BOOLEAN, true);
        pdc.set(pl.keyOwnerUuid(), PersistentDataType.STRING, ownerUuid.toString());
        pdc.set(pl.keyDiaryId(), PersistentDataType.STRING, id);
    }
}
