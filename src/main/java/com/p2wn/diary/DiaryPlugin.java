package com.p2wn.diary;

import com.p2wn.diary.commands.DiaryCommand;
import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DiaryAnalyticsStore;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.integrations.plan.DiaryPlanHook;
import com.p2wn.diary.item.DiaryItem;
import com.p2wn.diary.item.WelcomeBookItem;
import com.p2wn.diary.listeners.AnvilGuardListener;
import com.p2wn.diary.listeners.BundleGuardListener;
import com.p2wn.diary.listeners.ContainerGuardListener;
import com.p2wn.diary.listeners.DiaryAnalyticsListener;
import com.p2wn.diary.listeners.DiaryTrackingListener;
import com.p2wn.diary.listeners.DropTrackListener;
import com.p2wn.diary.listeners.EditListener;
import com.p2wn.diary.listeners.EnderChestGuardListener;
import com.p2wn.diary.listeners.GrindstoneGuardListener;
import com.p2wn.diary.listeners.InventoryOpenListener;
import com.p2wn.diary.listeners.ItemProtectionListener;
import com.p2wn.diary.listeners.JoinListener;
import com.p2wn.diary.listeners.MetaGuardListener;
import com.p2wn.diary.listeners.RestoreGuiListener;
import com.p2wn.diary.listeners.ShulkerGuardListener;
import com.p2wn.diary.logic.DeliveryService;
import com.p2wn.diary.logic.DiaryRestoreService;
import com.p2wn.diary.logic.DiaryService;
import com.p2wn.diary.logic.DiaryTrackerService;
import com.p2wn.diary.logic.DuplicateWatcher;
import com.p2wn.diary.logic.PerformanceMonitor;
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
    private DiaryAnalyticsStore diaryAnalyticsStore;
    private DiaryKeys diaryKeys;
    private DiaryItem diaryItem;
    private WelcomeBookItem welcomeBookItem;
    private RestrictionService restrictionService;
    private DuplicateWatcher duplicateWatcher;
    private DeliveryService deliveryService;
    private VoidWatcher voidWatcher;
    private DiaryService diaryService;
    private DiaryTrackerService diaryTrackerService;
    private DiaryRestoreService diaryRestoreService;
    private RestoreGuiListener restoreGuiListener;
    private DiaryPlanHook planHook;
    private PerformanceMonitor performanceMonitor;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();
        logMigrationReport("startup");
        performanceMonitor = new PerformanceMonitor(this);
        performanceMonitor.reload();

        diaryKeys = new DiaryKeys(this);
        diaryStore = new DiaryStore(this);
        diaryStore.setPerformanceMonitor(performanceMonitor);
        diaryStore.load();
        diaryStore.reloadAutosave();
        diaryAnalyticsStore = new DiaryAnalyticsStore(this);
        diaryAnalyticsStore.setPerformanceMonitor(performanceMonitor);
        diaryAnalyticsStore.load();
        diaryAnalyticsStore.reloadAutosave();

        handleWorldReset();

        diaryItem = new DiaryItem(this, configManager, diaryStore, diaryKeys);
        welcomeBookItem = new WelcomeBookItem(this);
        diaryTrackerService = new DiaryTrackerService(diaryStore, diaryItem);
        diaryTrackerService.setPerformanceMonitor(performanceMonitor);
        duplicateWatcher = new DuplicateWatcher(this, configManager, diaryItem);
        duplicateWatcher.setPerformanceMonitor(performanceMonitor);
        deliveryService = new DeliveryService(this, diaryStore);
        deliveryService.setPerformanceMonitor(performanceMonitor);
        diaryService = new DiaryService(this, configManager, diaryStore, diaryItem, welcomeBookItem, deliveryService);
        restrictionService = new RestrictionService(configManager, diaryItem);
        voidWatcher = new VoidWatcher(this, configManager, diaryItem, deliveryService, duplicateWatcher);
        voidWatcher.setPerformanceMonitor(performanceMonitor);
        diaryRestoreService = new DiaryRestoreService(configManager, diaryStore, diaryItem, diaryService, deliveryService, diaryTrackerService);
        restoreGuiListener = new RestoreGuiListener(this);

        diaryService.setDuplicateWatcher(duplicateWatcher);
        diaryService.setTrackerService(diaryTrackerService);
        diaryService.setRestoreService(diaryRestoreService);
        diaryService.setAnalyticsStore(diaryAnalyticsStore);
        deliveryService.setDiaryService(diaryService);
        deliveryService.setTrackerService(diaryTrackerService);
        deliveryService.setAnalyticsStore(diaryAnalyticsStore);

        registerCommand();
        registerListeners();
        registerPlanIntegration();

        duplicateWatcher.sweepStartup();
        duplicateWatcher.reloadSettings();
        deliveryService.reloadSettings();

        getLogger().info("DiaryKeeper enabled.");
    }

    @Override
    public void onDisable() {
        if (planHook != null) {
            planHook.unregister();
            planHook = null;
        }
        if (voidWatcher != null) {
            voidWatcher.shutdown();
        }
        if (deliveryService != null) {
            deliveryService.shutdown();
        }
        if (duplicateWatcher != null) {
            duplicateWatcher.shutdown();
        }
        if (diaryStore != null) {
            diaryStore.shutdown();
        }
        if (diaryAnalyticsStore != null) {
            diaryAnalyticsStore.shutdown();
        }
        if (performanceMonitor != null) {
            performanceMonitor.shutdown();
        }
    }

    public void reloadPluginState() {
        diaryStore.flushNowBlocking("reload");
        diaryAnalyticsStore.flushNowBlocking("reload");
        configManager.reload();
        logMigrationReport("reload");
        performanceMonitor.reload();
        diaryStore.reloadAutosave();
        diaryAnalyticsStore.reloadAutosave();
        diaryAnalyticsStore.reloadRetention();
        diaryItem.clearNexoCache();
        welcomeBookItem.reload();
        deliveryService.reloadSettings();
        voidWatcher.reloadSettings();
        duplicateWatcher.reloadSettings();
        duplicateWatcher.sweepStartup();
        reloadPlanIntegration();
        getLogger().info("DiaryKeeper reload summary: configs loaded, migration actions="
                + configManager.lastMigrationReport().actions().size()
                + ", migration warnings=" + configManager.lastMigrationReport().warnings().size()
                + ", tasks restarted, duplicate scan queued.");
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public DiaryStore diaryStore() {
        return diaryStore;
    }

    public DiaryAnalyticsStore diaryAnalyticsStore() {
        return diaryAnalyticsStore;
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

    public DiaryTrackerService diaryTrackerService() {
        return diaryTrackerService;
    }

    public DiaryRestoreService diaryRestoreService() {
        return diaryRestoreService;
    }

    public RestoreGuiListener restoreGuiListener() {
        return restoreGuiListener;
    }

    public WelcomeBookItem welcomeBookItem() {
        return welcomeBookItem;
    }

    public PerformanceMonitor performanceMonitor() {
        return performanceMonitor;
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
            diaryStore.flushNowBlocking("world reset");
        }
    }

    private void logMigrationReport(String phase) {
        ConfigManager.MigrationReport report = configManager.lastMigrationReport();
        for (String action : report.actions()) {
            getLogger().info("Config migration (" + phase + "): " + action);
        }
        for (String warning : report.warnings()) {
            getLogger().warning("Config migration (" + phase + "): " + warning);
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
        pluginManager.registerEvents(new DiaryAnalyticsListener(this), this);
        pluginManager.registerEvents(new EditListener(this), this);
        pluginManager.registerEvents(new InventoryOpenListener(this), this);
        pluginManager.registerEvents(new ItemProtectionListener(this), this);
        pluginManager.registerEvents(new DropTrackListener(this), this);
        pluginManager.registerEvents(new DiaryTrackingListener(this), this);
        pluginManager.registerEvents(new AnvilGuardListener(this), this);
        pluginManager.registerEvents(new GrindstoneGuardListener(this), this);
        pluginManager.registerEvents(new MetaGuardListener(this), this);
        pluginManager.registerEvents(new EnderChestGuardListener(this), this);
        pluginManager.registerEvents(new BundleGuardListener(this), this);
        pluginManager.registerEvents(new ShulkerGuardListener(this), this);
        pluginManager.registerEvents(new ContainerGuardListener(this), this);
        pluginManager.registerEvents(restoreGuiListener, this);
    }

    private void registerPlanIntegration() {
        if (!configManager.cfg().getBoolean("integrations.plan.enabled", true)) {
            getLogger().fine("Plan integration is disabled in config.");
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("Plan")) {
            getLogger().fine("Plan is not installed or enabled; skipping DiaryKeeper Plan integration.");
            return;
        }
        try {
            planHook = new DiaryPlanHook(this);
            planHook.hookIntoPlan();
        } catch (NoClassDefFoundError | ExceptionInInitializerError ex) {
            getLogger().fine("Plan API unavailable; skipping DiaryKeeper Plan integration.");
        } catch (RuntimeException ex) {
            getLogger().warning("Failed to register DiaryKeeper Plan integration: " + ex.getMessage());
        }
    }

    private void reloadPlanIntegration() {
        if (planHook != null) {
            planHook.unregister();
            planHook = null;
        }
        registerPlanIntegration();
    }
}
