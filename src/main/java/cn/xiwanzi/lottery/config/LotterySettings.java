package cn.xiwanzi.lottery.config;

import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.model.PrizeTier;

import java.util.EnumMap;
import java.util.Map;

public final class LotterySettings {
    private final LotteryType type;
    private final String displayName;
    private final double price;
    private final int maxPurchasesPerPlayer;
    private final double minTotalPool;
    private final double rewardPoolPercent;
    private final double housePoolPercent;
    private final ScheduleSettings schedule;
    private final Map<PrizeTier, RewardSettings> rewards = new EnumMap<>(PrizeTier.class);

    public LotterySettings(LotteryType type, String displayName, double price, int maxPurchasesPerPlayer, double minTotalPool,
                           double rewardPoolPercent, double housePoolPercent, ScheduleSettings schedule) {
        this.type = type;
        this.displayName = displayName;
        this.price = price;
        this.maxPurchasesPerPlayer = maxPurchasesPerPlayer;
        this.minTotalPool = minTotalPool;
        this.rewardPoolPercent = rewardPoolPercent;
        this.housePoolPercent = housePoolPercent;
        this.schedule = schedule;
    }

    public LotteryType type() {
        return type;
    }

    public String displayName() {
        return displayName;
    }

    public double price() {
        return price;
    }

    public int maxPurchasesPerPlayer() {
        return maxPurchasesPerPlayer;
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

    public ScheduleSettings schedule() {
        return schedule;
    }

    public Map<PrizeTier, RewardSettings> rewards() {
        return rewards;
    }
}
