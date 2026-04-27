package cn.xiwanzi.lottery.model;

public final class LedgerEntry {
    private final LotteryType type;
    private final long periodId;
    private final String action;
    private final String playerName;
    private final double amount;
    private final long createdAt;
    private final String note;

    public LedgerEntry(LotteryType type, long periodId, String action, String playerName, double amount, long createdAt, String note) {
        this.type = type;
        this.periodId = periodId;
        this.action = action;
        this.playerName = playerName;
        this.amount = amount;
        this.createdAt = createdAt;
        this.note = note;
    }

    public LotteryType type() {
        return type;
    }

    public long periodId() {
        return periodId;
    }

    public String action() {
        return action;
    }

    public String playerName() {
        return playerName;
    }

    public double amount() {
        return amount;
    }

    public long createdAt() {
        return createdAt;
    }

    public String note() {
        return note;
    }
}
