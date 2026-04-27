package cn.xiwanzi.lottery.model;

import java.util.Locale;
import java.util.Optional;

public enum LotteryType {
    DAILY("daily"),
    WEEKLY("weekly");

    private final String key;

    LotteryType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<LotteryType> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (LotteryType type : values()) {
            if (type.key.equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
