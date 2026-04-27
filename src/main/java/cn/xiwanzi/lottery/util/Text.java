package cn.xiwanzi.lottery.util;

import org.bukkit.ChatColor;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class Text {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static List<String> color(List<String> lines) {
        List<String> colored = new ArrayList<>();
        for (String line : lines) {
            colored.add(color(line));
        }
        return colored;
    }

    public static String plain(String text) {
        return ChatColor.stripColor(color(text));
    }

    public static String money(double value) {
        return MONEY.format(value);
    }

    public static String countdown(long targetMillis) {
        if (targetMillis <= 0) {
            return "--:--:--";
        }
        long millis = Math.max(0, targetMillis - System.currentTimeMillis());
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        long seconds = duration.minusDays(days).minusHours(hours).minusMinutes(minutes).getSeconds();
        if (days > 0) {
            return days + "d " + pad(hours) + ":" + pad(minutes) + ":" + pad(seconds);
        }
        return pad(hours) + ":" + pad(minutes) + ":" + pad(seconds);
    }

    private static String pad(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}
