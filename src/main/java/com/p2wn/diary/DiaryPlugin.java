package com.p2wn.diary;

import com.p2wn.diary.commands.DiaryCommand;
import com.p2wn.diary.config.ConfigManager;
import com.p2wn.diary.data.DiaryStore;
import com.p2wn.diary.logic.DeliveryService;
import com.p2wn.diary.logic.DuplicateWatcher;
import com.p2wn.diary.logic.VoidWatcher;
import com.p2wn.diary.listeners.*;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class DiaryPlugin extends JavaPlugin {

    private static DiaryPlugin instance;
    public static DiaryPlugin get() { return instance; }

    private ConfigManager configManager;
    private DiaryStore diaryStore;
    private DuplicateWatcher duplicateWatcher;
    private VoidWatcher voidWatcher;
    private DeliveryService deliveryService;

    // PDC keys
    private NamespacedKey keyIsDiary;
    private NamespacedKey keyOwnerUuid;
    private NamespacedKey keyDiaryId;
    private NamespacedKey keyLastDropper;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("messages.yml", false);

        this.configManager = new ConfigManager(this);
        this.diaryStore = new DiaryStore(this);

        // Detect "world reset": if the main world's UUID changed since last run, reset diary registry
        World main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (main != null) {
            String current = main.getUID().toString();
            String last = diaryStore.getLastWorldUid();
            if (last == null || !Objects.equals(last, current)) {
                getLogger().warning("World UUID changed or first run â€” resetting diary registry and queues.");
                diaryStore.resetAllPlayers();   // clears known diary IDs & queues
                diaryStore.setLastWorldUid(current);
                diaryStore.save();
            }
        }

        this.duplicateWatcher = new DuplicateWatcher(this);
        this.deliveryService = new DeliveryService(this);
        this.voidWatcher = new VoidWatcher(this, deliveryService);

        keyIsDiary     = new NamespacedKey(this, "is_diary");
        keyOwnerUuid   = new NamespacedKey(this, "owner_uuid");
        keyDiaryId     = new NamespacedKey(this, "diary_id");
        keyLastDropper = new NamespacedKey(this, "last_dropper");

        getCommand("diary").setExecutor(new DiaryCommand(this));

        var pm = getServer().getPluginManager();
        pm.registerEvents(new JoinListener(this), this);
        pm.registerEvents(new EditListener(this), this);
        pm.registerEvents(new InventoryOpenListener(this), this);
        pm.registerEvents(new ItemProtectionListener(this), this);
        pm.registerEvents(new DropTrackListener(this), this);
        pm.registerEvents(new AnvilGuardListener(), this);
        pm.registerEvents(new GrindstoneGuardListener(), this);
        pm.registerEvents(new MetaGuardListener(), this);
        pm.registerEvents(new EnderChestGuardListener(), this);
        pm.registerEvents(new EnderChestGuardListener(), this);
        pm.registerEvents(new BundleGuardListener(), this);
        pm.registerEvents(new ShulkerGuardListener(), this);
        pm.registerEvents(new ContainerGuardListener(), this);




        // Optional startup sweep
        if (configManager.cfg().getBoolean("duplicates.warn-on-startup", true)) {
            duplicateWatcher.sweepStartup();
        }

        getLogger().info("DiaryKeeper enabled.");
    }

    @Override
    public void onDisable() {
        diaryStore.save();
        voidWatcher.stopIfIdle(true);
        deliveryService.stopIfIdle(true);
    }

    public ConfigManager configManager() { return configManager; }
    public DiaryStore diaryStore() { return diaryStore; }
    public DuplicateWatcher duplicateWatcher() { return duplicateWatcher; }
    public VoidWatcher voidWatcher() { return voidWatcher; }
    public DeliveryService deliveryService() { return deliveryService; }

    public NamespacedKey keyIsDiary() { return keyIsDiary; }
    public NamespacedKey keyOwnerUuid() { return keyOwnerUuid; }
    public NamespacedKey keyDiaryId() { return keyDiaryId; }
    public NamespacedKey keyLastDropper() { return keyLastDropper; }
}
