package cn.xiwanzi.lottery.config;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record ScheduleSettings(FixedDaily fixedDaily, FixedWeekly fixedWeekly, Interval interval, boolean valid) {
    public record FixedDaily(boolean enabled, LocalTime time) {
    }

    public record FixedWeekly(boolean enabled, DayOfWeek day, LocalTime time) {
    }

    public record Interval(boolean enabled, int minutes) {
    }
}
