package cn.xiwanzi.lottery.model;

import java.util.UUID;

public final class HolidayBet {
    private final long id;
    private final long periodId;
    private final HolidayOutcome outcome;
    private final UUID playerUuid;
    private final String playerName;
    private final double amount;

    public HolidayBet(long id, long periodId, HolidayOutcome outcome, UUID playerUuid, String playerName, double amount) {
        this.id = id;
        this.periodId = periodId;
        this.outcome = outcome;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.amount = amount;
    }

    public long id() {
        return id;
    }

    public long periodId() {
        return periodId;
    }

    public HolidayOutcome outcome() {
        return outcome;
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
