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
import com.p2wn.diary.listeners.LumaGuildVaultGuardListener;
import com.p2wn.diary.listeners.MetaGuardListener;
import com.p2wn.diary.listeners.RestoreGuiListener;
import com.p2wn.diary.listeners.ShulkerGuardListener;
import com.p2wn.diary.logic.DeliveryService;
import com.p2wn.diary.logic.DiaryRestoreService;
import com.p2wn.diary.logic.DiaryPurgeService;
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

    private ConfigManager activeConfigManager;
    private DiaryStore activeDiaryStore;
    private DiaryAnalyticsStore activeDiaryAnalyticsStore;
    private DiaryKeys activeDiaryKeys;
    private DiaryItem activeDiaryItem;
    private WelcomeBookItem activeWelcomeBookItem;
    private RestrictionService activeRestrictionService;
    private DuplicateWatcher activeDuplicateWatcher;
    private DeliveryService activeDeliveryService;
    private VoidWatcher activeVoidWatcher;
    private DiaryService activeDiaryService;
    private DiaryTrackerService activeDiaryTrackerService;
    private DiaryRestoreService activeDiaryRestoreService;
    private DiaryPurgeService activeDiaryPurgeService;
    private RestoreGuiListener activeRestoreGuiListener;
    private DiaryPlanHook planHook;
    private PerformanceMonitor activePerformanceMonitor;

    @Override
    public void onEnable() {
        activeConfigManager = new ConfigManager(this);
        activeConfigManager.load();
        logMigrationReport("startup");
        activePerformanceMonitor = new PerformanceMonitor(this);
        activePerformanceMonitor.reload();

        activeDiaryKeys = new DiaryKeys(this);
        activeDiaryStore = new DiaryStore(this);
        activeDiaryStore.setPerformanceMonitor(activePerformanceMonitor);
        activeDiaryStore.load();
        activeDiaryStore.reloadAutosave();
        activeDiaryAnalyticsStore = new DiaryAnalyticsStore(this);
        activeDiaryAnalyticsStore.setPerformanceMonitor(activePerformanceMonitor);
        activeDiaryAnalyticsStore.load();
        activeDiaryAnalyticsStore.reloadAutosave();

        handleWorldReset();

        activeDiaryItem = new DiaryItem(this, activeConfigManager, activeDiaryStore, activeDiaryKeys);
        activeWelcomeBookItem = new WelcomeBookItem(this);
        activeDiaryTrackerService = new DiaryTrackerService(activeDiaryStore, activeDiaryItem);
        activeDiaryTrackerService.setPerformanceMonitor(activePerformanceMonitor);
        activeDuplicateWatcher = new DuplicateWatcher(this, activeConfigManager, activeDiaryItem);
        activeDuplicateWatcher.setPerformanceMonitor(activePerformanceMonitor);
        activeDeliveryService = new DeliveryService(this, activeDiaryStore);
        activeDeliveryService.setPerformanceMonitor(activePerformanceMonitor);
        activeDiaryService = new DiaryService(this, activeConfigManager, activeDiaryStore, activeDiaryItem, activeWelcomeBookItem, activeDeliveryService);
        activeRestrictionService = new RestrictionService(activeConfigManager, activeDiaryItem);
        activeVoidWatcher = new VoidWatcher(this, activeConfigManager, activeDiaryItem, activeDeliveryService, activeDuplicateWatcher);
        activeVoidWatcher.setPerformanceMonitor(activePerformanceMonitor);
        activeDiaryRestoreService = new DiaryRestoreService(activeConfigManager, activeDiaryStore, activeDiaryItem, activeDiaryService, activeDeliveryService, activeDiaryTrackerService);
        activeDiaryPurgeService = new DiaryPurgeService(this);
        activeRestoreGuiListener = new RestoreGuiListener(this);

        activeDiaryService.setDuplicateWatcher(activeDuplicateWatcher);
        activeDiaryService.setTrackerService(activeDiaryTrackerService);
        activeDiaryService.setRestoreService(activeDiaryRestoreService);
        activeDiaryService.setAnalyticsStore(activeDiaryAnalyticsStore);
        activeDeliveryService.setDiaryService(activeDiaryService);
        activeDeliveryService.setTrackerService(activeDiaryTrackerService);
        activeDeliveryService.setAnalyticsStore(activeDiaryAnalyticsStore);

        registerCommand();
        registerListeners();
        registerPlanIntegration();

        activeDuplicateWatcher.sweepStartup();
        activeDuplicateWatcher.reloadSettings();
        activeDeliveryService.reloadSettings();
        activeDiaryPurgeService.start();

        getLogger().info("DiaryKeeper enabled.");
    }

    @Override
    public void onDisable() {
        if (planHook != null) {
            planHook.unregister();
            planHook = null;
        }
        if (activeVoidWatcher != null) {
            activeVoidWatcher.shutdown();
        }
        if (activeDeliveryService != null) {
            activeDeliveryService.shutdown();
        }
        if (activeDuplicateWatcher != null) {
            activeDuplicateWatcher.shutdown();
        }
        if (activeDiaryPurgeService != null) {
            activeDiaryPurgeService.shutdown();
        }
        if (activeDiaryStore != null) {
            activeDiaryStore.shutdown();
        }
        if (activeDiaryAnalyticsStore != null) {
            activeDiaryAnalyticsStore.shutdown();
        }
        if (activePerformanceMonitor != null) {
            activePerformanceMonitor.shutdown();
        }
    }

    public void reloadPluginState() {
        activeDiaryStore.flushNowBlocking("reload");
        activeDiaryAnalyticsStore.flushNowBlocking("reload");
        activeConfigManager.reload();
        logMigrationReport("reload");
        activePerformanceMonitor.reload();
        activeDiaryStore.reloadAutosave();
        activeDiaryAnalyticsStore.reloadAutosave();
        activeDiaryAnalyticsStore.reloadRetention();
        activeDiaryItem.clearNexoCache();
        activeWelcomeBookItem.reload();
        activeDeliveryService.reloadSettings();
        activeVoidWatcher.reloadSettings();
        activeDuplicateWatcher.reloadSettings();
        activeDuplicateWatcher.sweepStartup();
        activeDiaryPurgeService.start();
        reloadPlanIntegration();
        getLogger().info("DiaryKeeper reload summary: configs loaded, migration actions="
                + activeConfigManager.lastMigrationReport().actions().size()
                + ", migration warnings=" + activeConfigManager.lastMigrationReport().warnings().size()
                + ", tasks restarted, duplicate scan queued.");
    }

    public ConfigManager configManager() {
        return activeConfigManager;
    }

    public DiaryStore diaryStore() {
        return activeDiaryStore;
    }

    public DiaryAnalyticsStore diaryAnalyticsStore() {
        return activeDiaryAnalyticsStore;
    }

    public DiaryKeys diaryKeys() {
        return activeDiaryKeys;
    }

    public DiaryItem diaryItem() {
        return activeDiaryItem;
    }

    public RestrictionService restrictionService() {
        return activeRestrictionService;
    }

    public DuplicateWatcher duplicateWatcher() {
        return activeDuplicateWatcher;
    }

    public DeliveryService deliveryService() {
        return activeDeliveryService;
    }

    public VoidWatcher voidWatcher() {
        return activeVoidWatcher;
    }

    public DiaryService diaryService() {
        return activeDiaryService;
    }

    public DiaryTrackerService diaryTrackerService() {
        return activeDiaryTrackerService;
    }

    public DiaryRestoreService diaryRestoreService() {
        return activeDiaryRestoreService;
    }

    public DiaryPurgeService diaryPurgeService() {
        return activeDiaryPurgeService;
    }

    public RestoreGuiListener restoreGuiListener() {
        return activeRestoreGuiListener;
    }

    public WelcomeBookItem welcomeBookItem() {
        return activeWelcomeBookItem;
    }

    public PerformanceMonitor performanceMonitor() {
        return activePerformanceMonitor;
    }

    private void handleWorldReset() {
        World mainWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (mainWorld == null) {
            return;
        }

        String currentWorldId = mainWorld.getUID().toString();
        String previousWorldId = activeDiaryStore.getLastWorldUid();
        if (previousWorldId == null || !Objects.equals(previousWorldId, currentWorldId)) {
            getLogger().warning("Main world UUID changed or was not recorded; resetting diary issuance state and queued deliveries.");
            activeDiaryStore.resetAllPlayers();
            activeDiaryStore.setLastWorldUid(currentWorldId);
            activeDiaryStore.flushNowBlocking("world reset");
        }
    }

    private void logMigrationReport(String phase) {
        ConfigManager.MigrationReport report = activeConfigManager.lastMigrationReport();
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
        pluginManager.registerEvents(new LumaGuildVaultGuardListener(this), this);
        pluginManager.registerEvents(activeRestoreGuiListener, this);
    }

    private void registerPlanIntegration() {
        if (!activeConfigManager.cfg().getBoolean("integrations.plan.enabled", true)) {
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
