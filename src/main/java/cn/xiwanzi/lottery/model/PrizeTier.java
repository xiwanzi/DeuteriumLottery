package cn.xiwanzi.lottery.model;

public enum PrizeTier {
    FIRST("first", "一等奖"),
    SECOND("second", "二等奖"),
    THIRD("third", "三等奖");

    private final String key;
    private final String displayName;

    PrizeTier(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }
}
