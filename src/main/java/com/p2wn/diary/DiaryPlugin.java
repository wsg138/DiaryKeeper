package com.p2wn.diary;

import com.p2wn.diary.commands.DiaryCommand;
import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.item.DiaryItem;
import com.p2wn.diary.listeners.AnvilGuardListener;
import com.p2wn.diary.listeners.BundleGuardListener;
import com.p2wn.diary.listeners.ContainerGuardListener;
import com.p2wn.diary.listeners.DropTrackListener;
import com.p2wn.diary.listeners.EditListener;
import com.p2wn.diary.listeners.EnderChestGuardListener;
import com.p2wn.diary.listeners.GrindstoneGuardListener;
import com.p2wn.diary.listeners.InventoryOpenListener;
import com.p2wn.diary.listeners.ItemProtectionListener;
import com.p2wn.diary.listeners.JoinListener;
import com.p2wn.diary.listeners.MetaGuardListener;
import com.p2wn.diary.listeners.ShulkerGuardListener;
import com.p2wn.diary.logic.DeliveryService;
import com.p2wn.diary.logic.DiaryService;
import com.p2wn.diary.logic.DuplicateWatcher;
import com.p2wn.diary.logic.RestrictionService;
import com.p2wn.diary.logic.VoidWatcher;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class DiaryPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DiaryStore diaryStore;
    private DiaryKeys diaryKeys;
    private DiaryItem diaryItem;
    private RestrictionService restrictionService;
    private DuplicateWatcher duplicateWatcher;
    private DeliveryService deliveryService;
    private VoidWatcher voidWatcher;
    private DiaryService diaryService;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        diaryKeys = new DiaryKeys(this);
        diaryStore = new DiaryStore(this);
        diaryStore.load();
        diaryStore.reloadAutosave();

        handleWorldReset();

        diaryItem = new DiaryItem(configManager, diaryStore, diaryKeys);
        duplicateWatcher = new DuplicateWatcher(this, configManager, diaryItem);
        deliveryService = new DeliveryService(this, diaryStore);
        diaryService = new DiaryService(this, configManager, diaryStore, diaryItem, deliveryService);
        restrictionService = new RestrictionService(configManager, diaryItem);
        voidWatcher = new VoidWatcher(this, configManager, diaryItem, deliveryService, duplicateWatcher);

        diaryService.setDuplicateWatcher(duplicateWatcher);
        deliveryService.setDiaryService(diaryService);

        registerCommand();
        registerListeners();

        duplicateWatcher.sweepStartup();
        deliveryService.reloadSettings();

        getLogger().info("DiaryKeeper enabled.");
    }

    @Override
    public void onDisable() {
        if (voidWatcher != null) {
            voidWatcher.shutdown();
        }
        if (deliveryService != null) {
            deliveryService.shutdown();
        }
        if (diaryStore != null) {
            diaryStore.shutdown();
        }
    }

    public void reloadPluginState() {
        diaryStore.flushNow();
        configManager.reload();
        diaryStore.reloadAutosave();
        deliveryService.reloadSettings();
        voidWatcher.reloadSettings();
        duplicateWatcher.sweepStartup();
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public DiaryStore diaryStore() {
        return diaryStore;
    }

    public DiaryKeys diaryKeys() {
        return diaryKeys;
    }

    public DiaryItem diaryItem() {
        return diaryItem;
    }

    public RestrictionService restrictionService() {
        return restrictionService;
    }

    public DuplicateWatcher duplicateWatcher() {
        return duplicateWatcher;
    }

    public DeliveryService deliveryService() {
        return deliveryService;
    }

    public VoidWatcher voidWatcher() {
        return voidWatcher;
    }

    public DiaryService diaryService() {
        return diaryService;
    }

    private void handleWorldReset() {
        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (mainWorld == null) {
            return;
        }

        String currentWorldId = mainWorld.getUID().toString();
        String previousWorldId = diaryStore.getLastWorldUid();
        if (previousWorldId == null || !Objects.equals(previousWorldId, currentWorldId)) {
            getLogger().warning("Main world UUID changed or was not recorded; resetting diary issuance state and queued deliveries.");
            diaryStore.resetAllPlayers();
            diaryStore.setLastWorldUid(currentWorldId);
            diaryStore.flushNow();
        }
    }

    private void registerCommand() {
        PluginCommand command = getCommand("diary");
        if (command == null) {
            throw new IllegalStateException("Command 'diary' is missing from plugin.yml");
        }
        DiaryCommand executor = new DiaryCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerListeners() {
        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new JoinListener(this), this);
        pluginManager.registerEvents(new EditListener(this), this);
        pluginManager.registerEvents(new InventoryOpenListener(this), this);
        pluginManager.registerEvents(new ItemProtectionListener(this), this);
        pluginManager.registerEvents(new DropTrackListener(this), this);
        pluginManager.registerEvents(new AnvilGuardListener(this), this);
        pluginManager.registerEvents(new GrindstoneGuardListener(this), this);
        pluginManager.registerEvents(new MetaGuardListener(this), this);
        pluginManager.registerEvents(new EnderChestGuardListener(this), this);
        pluginManager.registerEvents(new BundleGuardListener(this), this);
        pluginManager.registerEvents(new ShulkerGuardListener(this), this);
        pluginManager.registerEvents(new ContainerGuardListener(this), this);
    }
}
