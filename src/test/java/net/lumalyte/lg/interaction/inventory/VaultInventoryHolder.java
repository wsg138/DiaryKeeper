package net.lumalyte.lg.interaction.inventory;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Test-only fixture matching the external LumaGuilds vault-holder class name contract. */
public final class VaultInventoryHolder implements InventoryHolder {
    private final Inventory inventory;

    public VaultInventoryHolder(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
