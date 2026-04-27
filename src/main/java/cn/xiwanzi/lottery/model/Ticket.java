package cn.xiwanzi.lottery.model;

import java.util.UUID;

public final class Ticket {
    private final long id;
    private final LotteryType type;
    private final long periodId;
    private final UUID playerUuid;
    private final String playerName;
    private final double price;

    public Ticket(long id, LotteryType type, long periodId, UUID playerUuid, String playerName, double price) {
        this.id = id;
        this.type = type;
        this.periodId = periodId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.price = price;
    }

    public long id() {
        return id;
    }

    public LotteryType type() {
        return type;
    }

    public long periodId() {
        return periodId;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public double price() {
        return price;
    }
}
