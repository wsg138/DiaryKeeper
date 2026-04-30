package com.p2wn.diary.integrations.plan;

import com.djrapitops.plan.capability.CapabilityService;
import com.djrapitops.plan.extension.ExtensionService;
import com.p2wn.diary.DiaryPlugin;

public final class DiaryPlanHook {

    private final DiaryPlugin plugin;
    private DiaryPlanDataExtension extension;

    public DiaryPlanHook(DiaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void hookIntoPlan() {
        if (!hasRequiredCapabilities()) {
            plugin.getLogger().fine("Plan is installed, but required DataExtension capabilities are unavailable.");
            return;
        }
        registerDataExtension();
        CapabilityService.getInstance().registerEnableListener(enabled -> {
            if (Boolean.TRUE.equals(enabled)) {
                registerDataExtension();
            }
        });
    }

    private boolean hasRequiredCapabilities() {
        CapabilityService capabilities = CapabilityService.getInstance();
        return capabilities.hasCapability("DATA_EXTENSION_VALUES")
                && capabilities.hasCapability("DATA_EXTENSION_TABLES");
    }

    private void registerDataExtension() {
        try {
            unregister();
            extension = new DiaryPlanDataExtension(
                    plugin.diaryStore(),
                    plugin.diaryAnalyticsStore(),
                    plugin.configManager().cfg().getInt("integrations.plan.recent-table-size", 10)
            );
            ExtensionService.getInstance().register(extension);
            plugin.getLogger().info("Registered DiaryKeeper Plan analytics integration.");
        } catch (IllegalStateException ex) {
            plugin.getLogger().fine("Plan is not ready for DiaryKeeper integration: " + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("DiaryKeeper Plan integration is invalid: " + ex.getMessage());
        }
    }

    public void unregister() {
        if (extension == null) {
            return;
        }
        try {
            ExtensionService.getInstance().unregister(extension);
        } catch (IllegalStateException ex) {
            plugin.getLogger().fine("Plan was not ready while unregistering DiaryKeeper integration: " + ex.getMessage());
        } finally {
            extension = null;
        }
    }
}
