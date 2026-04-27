package cn.xiwanzi.lottery.model;

public final class PeriodState {
    private final LotteryType type;
    private final long periodId;
    private final long nextDrawAt;
    private final double rollover;
    private final String lastFirstWinner;

    public PeriodState(LotteryType type, long periodId, long nextDrawAt, double rollover, String lastFirstWinner) {
        this.type = type;
        this.periodId = periodId;
        this.nextDrawAt = nextDrawAt;
        this.rollover = rollover;
        this.lastFirstWinner = lastFirstWinner;
    }

    public LotteryType type() {
        return type;
    }

    public long periodId() {
        return periodId;
    }

    public long nextDrawAt() {
        return nextDrawAt;
    }

    public double rollover() {
        return rollover;
    }

    public String lastFirstWinner() {
        return lastFirstWinner;
    }
}
