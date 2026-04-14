package com.p2wn.diary.item;

import com.p2wn.diary.DiaryKeys;
import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DiaryStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DiaryItem {

    private final ConfigManager configManager;
    private final DiaryStore diaryStore;
    private final DiaryKeys keys;

    public DiaryItem(ConfigManager configManager, DiaryStore diaryStore, DiaryKeys keys) {
        this.configManager = configManager;
        this.diaryStore = diaryStore;
        this.keys = keys;
    }

    public boolean isDiary(ItemStack stack) {
        if (stack == null || stack.getType() != Material.WRITABLE_BOOK) {
            return false;
        }
        if (!(stack.getItemMeta() instanceof BookMeta meta)) {
            return false;
        }
        Boolean marker = meta.getPersistentDataContainer().get(keys.isDiary(), PersistentDataType.BOOLEAN);
        return Boolean.TRUE.equals(marker);
    }

    public UUID getOwner(ItemStack stack) {
        if (!isDiary(stack)) {
            return null;
        }
        String raw = stack.getItemMeta().getPersistentDataContainer().get(keys.ownerUuid(), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public String getDiaryId(ItemStack stack) {
        if (!isDiary(stack)) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(keys.diaryId(), PersistentDataType.STRING);
    }

    public UUID getLastDropper(ItemStack stack) {
        if (!isDiary(stack)) {
            return null;
        }
        String raw = stack.getItemMeta().getPersistentDataContainer().get(keys.lastDropper(), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public void setLastDropper(ItemStack stack, UUID playerId) {
        if (!isDiary(stack) || playerId == null) {
            return;
        }
        if (!(stack.getItemMeta() instanceof BookMeta meta)) {
            return;
        }
        meta.getPersistentDataContainer().set(keys.lastDropper(), PersistentDataType.STRING, playerId.toString());
        stack.setItemMeta(meta);
    }

    public ItemStack createDiary(UUID ownerId, String ownerName) {
        String diaryId = diaryStore.getOrCreateDiaryId(ownerId);
        ItemStack stack = new ItemStack(Material.WRITABLE_BOOK);
        if (stack.getItemMeta() instanceof BookMeta meta) {
            applyCanonicalAppearance(meta, ownerId, ownerName, diaryId);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public void refreshOwnerCosmetics(UUID ownerId, String ownerName, ItemStack stack) {
        if (!isDiary(stack)) {
            return;
        }
        String diaryId = getDiaryId(stack);
        if (diaryId == null || !(stack.getItemMeta() instanceof BookMeta meta)) {
            return;
        }
        applyCanonicalAppearance(meta, ownerId, ownerName, diaryId);
        stack.setItemMeta(meta);
    }

    public void canonicalize(ItemStack stack) {
        if (!isDiary(stack)) {
            return;
        }

        UUID ownerId = getOwner(stack);
        String diaryId = getDiaryId(stack);
        if (ownerId == null || diaryId == null || !(stack.getItemMeta() instanceof BookMeta meta)) {
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownerId);
        String ownerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : ownerId.toString();
        applyCanonicalAppearance(meta, ownerId, ownerName, diaryId);
        stack.setItemMeta(meta);
    }

    private void applyCanonicalAppearance(BookMeta meta, UUID ownerId, String ownerName, String diaryId) {
        String displayName = configManager.cfg().getString("appearance.name-format", "&d{owner}'s Diary")
                .replace("{owner}", ownerName);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

        List<String> lore = new ArrayList<>();
        for (String line : configManager.cfg().getStringList("appearance.lore")) {
            lore.add(ChatColor.translateAlternateColorCodes('&',
                    line.replace("{owner}", ownerName)
                            .replace("{id-short}", diaryId.substring(0, Math.min(8, diaryId.length())))));
        }
        meta.setLore(lore);

        if (configManager.cfg().getBoolean("appearance.enchanted-glint", true)) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            meta.removeEnchant(Enchantment.UNBREAKING);
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.isDiary(), PersistentDataType.BOOLEAN, true);
        pdc.set(keys.ownerUuid(), PersistentDataType.STRING, ownerId.toString());
        pdc.set(keys.diaryId(), PersistentDataType.STRING, diaryId);
    }
}
