package cn.xiwanzi.lottery.model;

import java.util.UUID;

public final class Award {
    private final LotteryType type;
    private final long periodId;
    private final PrizeTier tier;
    private final UUID playerUuid;
    private final String playerName;
    private final double amount;

    public Award(LotteryType type, long periodId, PrizeTier tier, UUID playerUuid, String playerName, double amount) {
        this.type = type;
        this.periodId = periodId;
        this.tier = tier;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.amount = amount;
    }

    public LotteryType type() {
        return type;
    }

    public long periodId() {
        return periodId;
    }

    public PrizeTier tier() {
        return tier;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public double amount() {
        return amount;
    }
}
