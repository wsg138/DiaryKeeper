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
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DiaryItem {

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final DiaryStore diaryStore;
    private final DiaryKeys keys;
    private boolean warnedNexoFallback;
    private String cachedNexoItemId;
    private ItemStack cachedNexoBase;

    public DiaryItem(Plugin plugin, ConfigManager configManager, DiaryStore diaryStore, DiaryKeys keys) {
        this.plugin = plugin;
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
        ItemStack stack = createBaseDiaryStack();
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
        meta = mergeConfiguredBaseMeta(meta);
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
        meta = mergeConfiguredBaseMeta(meta);
        applyCanonicalAppearance(meta, ownerId, ownerName, diaryId);
        stack.setItemMeta(meta);
    }

    public void clearNexoCache() {
        cachedNexoItemId = null;
        cachedNexoBase = null;
        warnedNexoFallback = false;
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
            meta.removeEnchant(Enchantment.UNBREAKING);
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        } else {
            meta.removeEnchant(Enchantment.UNBREAKING);
            meta.setEnchantmentGlintOverride(Boolean.FALSE);
        }
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.isDiary(), PersistentDataType.BOOLEAN, true);
        pdc.set(keys.ownerUuid(), PersistentDataType.STRING, ownerId.toString());
        pdc.set(keys.diaryId(), PersistentDataType.STRING, diaryId);
    }

    private ItemStack createBaseDiaryStack() {
        ItemStack nexoStack = createConfiguredNexoStack();
        if (nexoStack != null) {
            return nexoStack;
        }
        return new ItemStack(Material.WRITABLE_BOOK);
    }

    private BookMeta mergeConfiguredBaseMeta(BookMeta currentMeta) {
        ItemStack nexoStack = createConfiguredNexoStack();
        if (nexoStack == null || !(nexoStack.getItemMeta() instanceof BookMeta baseMeta)) {
            return currentMeta;
        }

        List<String> pages = currentMeta.getPages();
        if (!pages.isEmpty()) {
            baseMeta.setPages(pages);
        }
        if (currentMeta.hasTitle()) {
            baseMeta.setTitle(currentMeta.getTitle());
        }
        if (currentMeta.hasAuthor()) {
            baseMeta.setAuthor(currentMeta.getAuthor());
        }
        if (currentMeta.hasGeneration()) {
            baseMeta.setGeneration(currentMeta.getGeneration());
        }
        return baseMeta;
    }

    private ItemStack createConfiguredNexoStack() {
        String nexoItemId = configManager.cfg().getString("appearance.nexo-item-id", "diary_book");
        if (nexoItemId == null || nexoItemId.isBlank() || !Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            return null;
        }
        if (nexoItemId.equals(cachedNexoItemId) && cachedNexoBase != null) {
            return cachedNexoBase.clone();
        }

        ItemStack nexoStack = createNexoItem(nexoItemId);
        if (nexoStack == null) {
            return null;
        }
        if (!(nexoStack.getItemMeta() instanceof BookMeta)) {
            warnNexoFallback("Nexo item '" + nexoItemId + "' is not backed by BookMeta. Set its material to WRITABLE_BOOK.");
            return null;
        }
        nexoStack.setAmount(1);
        cachedNexoItemId = nexoItemId;
        cachedNexoBase = nexoStack.clone();
        return nexoStack.clone();
    }

    @SuppressWarnings("PMD.UseProperClassLoader") // The Nexo API is visible through Nexo's plugin class loader, not Bukkit's context loader.
    private ItemStack createNexoItem(String itemId) {
        Plugin nexo = Bukkit.getPluginManager().getPlugin("Nexo");
        if (nexo == null) {
            return null;
        }

        try {
            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.api.NexoItems", true, nexo.getClass().getClassLoader());
            Method optionalItemFromId = nexoItemsClass.getMethod("optionalItemFromId", String.class);
            Object optionalBuilder = optionalItemFromId.invoke(null, itemId);
            if (!(optionalBuilder instanceof Optional<?> optional) || optional.isEmpty()) {
                warnNexoFallback("Nexo item '" + itemId + "' was not found. Check appearance.nexo-item-id.");
                return null;
            }

            Object itemBuilder = optional.get();
            Method build = itemBuilder.getClass().getMethod("build");
            Object built = build.invoke(itemBuilder);
            return built instanceof ItemStack stack ? stack : null;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            warnNexoFallback("Nexo item '" + itemId + "' failed to build: " + cause.getMessage());
            return null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            warnNexoFallback("Nexo API lookup failed for '" + itemId + "': " + ex.getMessage());
            return null;
        }
    }

    private void warnNexoFallback(String message) {
        if (warnedNexoFallback) {
            return;
        }
        warnedNexoFallback = true;
        plugin.getLogger().warning(message + " Falling back to a vanilla writable book.");
    }
}
