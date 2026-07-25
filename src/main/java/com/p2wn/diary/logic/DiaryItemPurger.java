package com.p2wn.diary.logic;

import com.p2wn.diary.item.DiaryItem;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public final class DiaryItemPurger {

    public record Result(ItemStack item, int removed) {}

    private final Predicate<ItemStack> diaryPredicate;
    private final Function<ItemStack, String> diaryIdReader;
    private final int maxDepth;

    public DiaryItemPurger(DiaryItem diaryItem, int maxDepth) {
        this(diaryItem::isDiary, diaryItem::getDiaryId, maxDepth);
    }

    public DiaryItemPurger(Predicate<ItemStack> diaryPredicate,
                           Function<ItemStack, String> diaryIdReader, int maxDepth) {
        this.diaryPredicate = diaryPredicate;
        this.diaryIdReader = diaryIdReader;
        this.maxDepth = Math.max(1, maxDepth);
    }

    public int purgeInventory(Inventory inventory, String diaryId) {
        if (inventory == null || diaryId == null) {
            return 0;
        }
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            Result result = purge(contents[slot], diaryId, 0);
            if (result.removed() > 0) {
                inventory.setItem(slot, result.item());
                removed += result.removed();
            }
        }
        return removed;
    }

    public Result purge(ItemStack stack, String diaryId, int depth) {
        if (stack == null) {
            return new Result(stack, 0);
        }
        if (diaryPredicate.test(stack)) {
            return diaryId.equals(diaryIdReader.apply(stack))
                    ? new Result(null, Math.max(1, stack.getAmount()))
                    : new Result(stack, 0);
        }
        if (stack.getType() == null || stack.getType() == Material.AIR) {
            return new Result(stack, 0);
        }
        if (depth >= maxDepth || !stack.hasItemMeta()) {
            return new Result(stack, 0);
        }
        if (stack.getType() == Material.BUNDLE && stack.getItemMeta() instanceof BundleMeta meta) {
            List<ItemStack> items = new ArrayList<>();
            int removed = 0;
            for (ItemStack nested : meta.getItems()) {
                Result result = purge(nested, diaryId, depth + 1);
                removed += result.removed();
                if (result.item() != null) {
                    items.add(result.item());
                }
            }
            if (removed == 0) {
                return new Result(stack, 0);
            }
            ItemStack clone = stack.clone();
            BundleMeta cloneMeta = (BundleMeta) clone.getItemMeta();
            cloneMeta.setItems(items);
            clone.setItemMeta(cloneMeta);
            return new Result(clone, removed);
        }
        if (stack.getItemMeta() instanceof BlockStateMeta meta
                && meta.getBlockState() instanceof ShulkerBox shulker) {
            int removed = purgeInventoryDepth(shulker.getInventory(), diaryId, depth + 1);
            if (removed == 0) {
                return new Result(stack, 0);
            }
            ItemStack clone = stack.clone();
            BlockStateMeta cloneMeta = (BlockStateMeta) clone.getItemMeta();
            cloneMeta.setBlockState(shulker);
            clone.setItemMeta(cloneMeta);
            return new Result(clone, removed);
        }
        return new Result(stack, 0);
    }

    private int purgeInventoryDepth(Inventory inventory, String diaryId, int depth) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            Result result = purge(contents[slot], diaryId, depth);
            if (result.removed() > 0) {
                inventory.setItem(slot, result.item());
                removed += result.removed();
            }
        }
        return removed;
    }
}
