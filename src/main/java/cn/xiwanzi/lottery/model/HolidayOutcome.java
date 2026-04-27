package cn.xiwanzi.lottery.model;

import java.util.Locale;
import java.util.Optional;

public enum HolidayOutcome {
    REDSTONE("redstone", "红石"),
    OBSIDIAN("obsidian", "黑曜石"),
    GOLD("gold", "金块");

    private final String key;
    private final String defaultDisplayName;

    HolidayOutcome(String key, String defaultDisplayName) {
        this.key = key;
        this.defaultDisplayName = defaultDisplayName;
    }

    public String key() {
        return key;
    }

    public String defaultDisplayName() {
        return defaultDisplayName;
    }

    public static Optional<HolidayOutcome> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (HolidayOutcome outcome : values()) {
            if (outcome.key.equals(normalized)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }
}
