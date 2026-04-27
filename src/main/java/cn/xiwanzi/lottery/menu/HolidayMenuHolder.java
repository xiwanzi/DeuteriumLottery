package cn.xiwanzi.lottery.menu;

import cn.xiwanzi.lottery.model.HolidayOutcome;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HolidayMenuHolder implements InventoryHolder {
    private final UUID viewer;
    private final Map<Integer, Choice> choices = new HashMap<>();
    private Inventory inventory;

    public HolidayMenuHolder(UUID viewer) {
        this.viewer = viewer;
    }

    public UUID viewer() {
        return viewer;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public void choice(int slot, HolidayOutcome outcome, double amount) {
        choices.put(slot, new Choice(outcome, amount));
    }

    public Choice choice(int slot) {
        return choices.get(slot);
    }

    public void clearChoices() {
        choices.clear();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public record Choice(HolidayOutcome outcome, double amount) {
    }
}
