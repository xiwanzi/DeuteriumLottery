package cn.xiwanzi.lottery.config;

import cn.xiwanzi.lottery.model.HolidayOutcome;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class HolidaySettings {
    private final boolean enabled;
    private final String displayName;
    private final double minTotalPool;
    private final double rewardPoolPercent;
    private final double housePoolPercent;
    private final int maxBetsPerPlayer;
    private final boolean refundEnabled;
    private final int refundLockBeforeMinutes;
    private final ScheduleSettings schedule;
    private final List<Double> betAmounts;
    private final Map<HolidayOutcome, OutcomeSettings> outcomes = new EnumMap<>(HolidayOutcome.class);

    public HolidaySettings(boolean enabled, String displayName, double minTotalPool, double rewardPoolPercent,
                           double housePoolPercent, int maxBetsPerPlayer, boolean refundEnabled,
                           int refundLockBeforeMinutes, ScheduleSettings schedule, List<Double> betAmounts) {
        this.enabled = enabled;
        this.displayName = displayName;
        this.minTotalPool = minTotalPool;
        this.rewardPoolPercent = rewardPoolPercent;
        this.housePoolPercent = housePoolPercent;
        this.maxBetsPerPlayer = maxBetsPerPlayer;
        this.refundEnabled = refundEnabled;
        this.refundLockBeforeMinutes = refundLockBeforeMinutes;
        this.schedule = schedule;
        this.betAmounts = betAmounts;
    }

    public boolean enabled() {
        return enabled;
    }

    public String displayName() {
        return displayName;
    }

    public double minTotalPool() {
        return minTotalPool;
    }

    public double rewardPoolPercent() {
        return rewardPoolPercent;
    }

    public double housePoolPercent() {
        return housePoolPercent;
    }

    public int maxBetsPerPlayer() {
        return maxBetsPerPlayer;
    }

    public boolean refundEnabled() {
        return refundEnabled;
    }

    public int refundLockBeforeMinutes() {
        return refundLockBeforeMinutes;
    }

    public ScheduleSettings schedule() {
        return schedule;
    }

    public List<Double> betAmounts() {
        return betAmounts;
    }

    public Map<HolidayOutcome, OutcomeSettings> outcomes() {
        return outcomes;
    }

    public OutcomeSettings outcome(HolidayOutcome outcome) {
        return outcomes.get(outcome);
    }

    public record OutcomeSettings(String displayName, Material material, double chancePercent) {
    }
}
