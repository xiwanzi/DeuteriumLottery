package cn.xiwanzi.lottery.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class LotteryMenuHolder implements InventoryHolder {
    private final UUID viewer;
    private Inventory inventory;

    public LotteryMenuHolder(UUID viewer) {
        this.viewer = viewer;
    }

    public UUID viewer() {
        return viewer;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
