package cn.xiwanzi.lottery.config;

import cn.xiwanzi.lottery.model.HolidayOutcome;
import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.model.PrizeTier;
import cn.xiwanzi.lottery.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ConfigManager {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaPlugin plugin;
    private final Map<LotteryType, LotterySettings> lotteries = new EnumMap<>(LotteryType.class);
    private HolidaySettings holidaySettings;
    private MailSettings mailSettings;
    private MenuSettings menuSettings;
    private String prefix;
    private String systemAccount;
    private int menuRefreshTicks;
    private int drawCheckTicks;
    private boolean samePlayerSinglePrize;
    private boolean purchaseConfirm;
    private int purchaseConfirmSeconds;
    private boolean broadcastEnabled;
    private String broadcastEmpty;
    private boolean broadcastHideEmptyPrizeLines;
    private List<String> broadcastLines;
    private List<String> broadcastCanceledLines;
    private List<String> broadcastNoTicketsLines;
    private boolean reminderEnabled;
    private List<Integer> reminderMinutes;
    private List<String> reminderLines;
    private Set<UUID> resetAllowedUuids = Set.of();

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();
        systemAccount = config.getString("settings.system-account", "lottery");
        menuRefreshTicks = Math.max(20, config.getInt("settings.menu-refresh-ticks", 20));
        drawCheckTicks = Math.max(20, config.getInt("settings.draw-check-ticks", 20));
        samePlayerSinglePrize = config.getBoolean("settings.same-player-single-prize", true);
        purchaseConfirm = config.getBoolean("settings.purchase-confirm", false);
        purchaseConfirmSeconds = Math.max(1, config.getInt("settings.purchase-confirm-seconds", 10));
        broadcastEnabled = config.getBoolean("broadcast.enabled", true);
        broadcastEmpty = config.getString("broadcast.empty", "");
        broadcastHideEmptyPrizeLines = config.getBoolean("broadcast.hide-empty-prize-lines", true);
        broadcastLines = config.getStringList("broadcast.lines");
        broadcastCanceledLines = config.getStringList("broadcast.canceled-lines");
        broadcastNoTicketsLines = config.getStringList("broadcast.no-tickets-lines");
        if (broadcastNoTicketsLines.isEmpty()) {
            broadcastNoTicketsLines = List.of(
                    "&8[&6建筑彩票&8] &e第 &f%period% &e期 %type% &7因无人购票，本期未开奖。",
                    "&8[&6建筑彩票&8] &7当前奖池: &f%pool%"
            );
        }
        reminderEnabled = config.getBoolean("reminder.enabled", true);
        reminderMinutes = config.getIntegerList("reminder.minutes");
        reminderLines = config.getStringList("reminder.lines");
        resetAllowedUuids = loadResetAllowedUuids(config);
        prefix = Text.color(config.getString("messages.prefix", "&8[&6建筑彩票&8] &r"));

        lotteries.clear();
        for (LotteryType type : LotteryType.values()) {
            lotteries.put(type, loadLottery(config, type));
        }
        holidaySettings = loadHoliday(config);
        mailSettings = loadMail(config);
        menuSettings = loadMenu(config);
    }

    private LotterySettings loadLottery(FileConfiguration config, LotteryType type) {
        String path = type.key();
        double rewardPoolPercent = clampPercent(config.getDouble(path + ".reward-pool-percent", 70));
        double configuredHousePercent = clampPercent(config.getDouble(path + ".house-pool-percent", 100 - rewardPoolPercent));
        double housePoolPercent = 100 - rewardPoolPercent;
        if (Math.abs(configuredHousePercent - housePoolPercent) > 0.0001) {
            plugin.getLogger().warning(type.key() + " house-pool-percent was normalized to " + housePoolPercent
                    + " because reward-pool-percent is " + rewardPoolPercent + ".");
        }
        LotterySettings settings = new LotterySettings(
                type,
                Text.color(config.getString(path + ".display-name", type.key())),
                Math.max(0, config.getDouble(path + ".price", 0)),
                Math.max(1, config.getInt(path + ".max-purchases-per-player", 1)),
                Math.max(0, config.getDouble(path + ".min-total-pool", 0)),
                rewardPoolPercent,
                housePoolPercent,
                loadSchedule(config, path + ".schedules", type)
        );

        for (PrizeTier tier : PrizeTier.values()) {
            String rewardPath = path + ".rewards." + tier.key();
            int winners = Math.max(0, config.getInt(rewardPath + ".winners", tier == PrizeTier.FIRST ? 1 : 0));
            if (tier == PrizeTier.FIRST) {
                winners = 1;
            }
            settings.rewards().put(tier, new RewardSettings(winners, clampPercent(config.getDouble(rewardPath + ".pool-percent", 0))));
        }
        return settings;
    }

    private HolidaySettings loadHoliday(FileConfiguration config) {
        String path = "holiday";
        double rewardPoolPercent = clampPercent(config.getDouble(path + ".reward-pool-percent", 90));
        double configuredHousePercent = clampPercent(config.getDouble(path + ".house-pool-percent", 100 - rewardPoolPercent));
        double housePoolPercent = 100 - rewardPoolPercent;
        if (Math.abs(configuredHousePercent - housePoolPercent) > 0.0001) {
            plugin.getLogger().warning("holiday house-pool-percent was normalized to " + housePoolPercent
                    + " because reward-pool-percent is " + rewardPoolPercent + ".");
        }
        List<Double> betAmounts = config.getDoubleList(path + ".bet-amounts").stream()
                .filter(amount -> amount > 0)
                .distinct()
                .toList();
        if (betAmounts.isEmpty()) {
            double fallback = Math.max(1, config.getDouble(path + ".price", 200));
            betAmounts = List.of(fallback);
        }
        HolidaySettings settings = new HolidaySettings(
                config.getBoolean(path + ".enabled", false),
                Text.color(config.getString(path + ".display-name", "节日公益活动")),
                Math.max(0, config.getDouble(path + ".min-total-pool", 1500)),
                rewardPoolPercent,
                housePoolPercent,
                Math.max(1, config.getInt(path + ".max-bets-per-player",
                        config.getInt(path + ".max-purchases-per-player", 5))),
                config.getBoolean(path + ".refund.enabled", true),
                Math.max(0, config.getInt(path + ".refund.lock-before-minutes", 60)),
                loadSchedule(config, path + ".schedules", LotteryType.HOLIDAY),
                betAmounts
        );
        for (HolidayOutcome outcome : HolidayOutcome.values()) {
            String outcomePath = path + ".outcomes." + outcome.key();
            settings.outcomes().put(outcome, new HolidaySettings.OutcomeSettings(
                    Text.color(config.getString(outcomePath + ".display-name", outcome.defaultDisplayName())),
                    material(config.getString(outcomePath + ".material", defaultOutcomeMaterialName(outcome)), defaultOutcomeMaterial(outcome)),
                    clampPercent(config.getDouble(outcomePath + ".chance-percent", defaultOutcomeChance(outcome)))
            ));
        }
        return settings;
    }

    private Material defaultOutcomeMaterial(HolidayOutcome outcome) {
        return switch (outcome) {
            case REDSTONE -> Material.REDSTONE_BLOCK;
            case OBSIDIAN -> Material.OBSIDIAN;
            case GOLD -> Material.GOLD_BLOCK;
        };
    }

    private String defaultOutcomeMaterialName(HolidayOutcome outcome) {
        return switch (outcome) {
            case REDSTONE -> "REDSTONE_BLOCK";
            case OBSIDIAN -> "OBSIDIAN";
            case GOLD -> "GOLD_BLOCK";
        };
    }

    private double defaultOutcomeChance(HolidayOutcome outcome) {
        return outcome == HolidayOutcome.GOLD ? 10 : 45;
    }

    private ScheduleSettings loadSchedule(FileConfiguration config, String path, LotteryType type) {
        ScheduleSettings.FixedDaily fixedDaily = new ScheduleSettings.FixedDaily(
                config.getBoolean(path + ".fixed-daily.enabled", false),
                parseTime(config.getString(path + ".fixed-daily.time", "21:00"))
        );
        ScheduleSettings.FixedWeekly fixedWeekly = new ScheduleSettings.FixedWeekly(
                config.getBoolean(path + ".fixed-weekly.enabled", false),
                parseDay(config.getString(path + ".fixed-weekly.day", "SUNDAY")),
                parseTime(config.getString(path + ".fixed-weekly.time", "21:00"))
        );
        ScheduleSettings.Interval interval = new ScheduleSettings.Interval(
                config.getBoolean(path + ".interval.enabled", false),
                Math.max(1, config.getInt(path + ".interval.minutes", 10))
        );

        int enabled = (fixedDaily.enabled() ? 1 : 0) + (fixedWeekly.enabled() ? 1 : 0) + (interval.enabled() ? 1 : 0);
        if (enabled > 1) {
            plugin.getLogger().warning(type.key() + " has multiple draw schedules enabled. Automatic draw is paused for this lottery.");
        }
        if (enabled == 0) {
            plugin.getLogger().warning(type.key() + " has no draw schedule enabled. Only manual draw will work.");
        }
        return new ScheduleSettings(fixedDaily, fixedWeekly, interval, enabled == 1);
    }

    private MailSettings loadMail(FileConfiguration config) {
        return new MailSettings(
                config.getBoolean("mail.enabled", false),
                config.getString("mail.smtp.host", ""),
                config.getInt("mail.smtp.port", 465),
                config.getBoolean("mail.smtp.ssl", true),
                config.getBoolean("mail.smtp.starttls", false),
                config.getString("mail.smtp.username", ""),
                config.getString("mail.smtp.password", ""),
                config.getString("mail.sender.from", ""),
                config.getString("mail.sender.name", "建筑彩票通知"),
                config.getString("mail.templates.win-subject", "[建筑彩票] 恭喜您中奖！"),
                config.getStringList("mail.templates.win-body")
        );
    }

    private MenuSettings loadMenu(FileConfiguration config) {
        Material filler = material(config.getString("menu.filler.material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        Material daily = material(config.getString("menu.daily.material", "SUNFLOWER"), Material.SUNFLOWER);
        Material weekly = material(config.getString("menu.weekly.material", "CLOCK"), Material.CLOCK);
        Material holiday = material(config.getString("menu.holiday.material",
                config.getString("holiday-menu.material", "EMERALD")), Material.EMERALD);
        Material emailUnbound = material(config.getString("menu.email.unbound.material", "WRITABLE_BOOK"), Material.WRITABLE_BOOK);
        Material emailBound = material(config.getString("menu.email.bound.material", "ENCHANTED_BOOK"), Material.ENCHANTED_BOOK);
        int dailySlot = config.getInt("menu.daily.slot", 11);
        int weeklySlot = config.getInt("menu.weekly.slot", 15);
        int emailSlot = config.getInt("menu.email.slot", 22);
        int holidaySlot = safeHolidaySlot(config.getInt("menu.holiday.slot", config.getInt("holiday-menu.slot", 13)),
                dailySlot, weeklySlot, emailSlot);
        return new MenuSettings(
                Text.color(config.getString("menu.title", "&6Lottery")),
                filler,
                Text.color(config.getString("menu.filler.name", " ")),
                dailySlot,
                daily,
                Text.color(config.getString("menu.daily.name", "&eDaily Lottery")),
                weeklySlot,
                weekly,
                Text.color(config.getString("menu.weekly.name", "&bWeekly Lottery")),
                holidaySlot,
                holiday,
                Text.color(config.getString("menu.holiday.name",
                        config.getString("holiday-menu.name", "&d&lHoliday Lottery"))),
                emailSlot,
                emailUnbound,
                Text.color(config.getString("menu.email.unbound.name", "&eBind Notification")),
                emailBound,
                Text.color(config.getString("menu.email.bound.name", "&aNotification Bound")),
                config.getStringList("menu.email.unbound.lore"),
                config.getStringList("menu.email.bound.lore"),
                config.getStringList("menu.lore")
        );
    }

    private int safeHolidaySlot(int preferred, int dailySlot, int weeklySlot, int emailSlot) {
        if (preferred >= 0 && preferred < 27 && preferred != dailySlot && preferred != weeklySlot && preferred != emailSlot) {
            return preferred;
        }
        for (int slot : List.of(13, 10, 12, 14, 16)) {
            if (slot != dailySlot && slot != weeklySlot && slot != emailSlot) {
                return slot;
            }
        }
        for (int slot = 0; slot < 27; slot++) {
            if (slot != dailySlot && slot != weeklySlot && slot != emailSlot) {
                return slot;
            }
        }
        return preferred;
    }

    private Set<UUID> loadResetAllowedUuids(FileConfiguration config) {
        Set<UUID> allowed = new HashSet<>();
        List<String> configured = config.getStringList("danger-zone.reset.allowed-uuids");
        for (String value : configured) {
            try {
                allowed.add(UUID.fromString(value));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Invalid reset allowed UUID: " + value);
            }
        }
        return allowed;
    }

    private Material material(String name, Material fallback) {
        Material material = Material.matchMaterial(name == null ? "" : name.toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    private LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value, TIME_FORMAT);
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid time '" + value + "', using 21:00.");
            return LocalTime.of(21, 0);
        }
    }

    private DayOfWeek parseDay(String value) {
        try {
            return DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            plugin.getLogger().warning("Invalid day '" + value + "', using SUNDAY.");
            return DayOfWeek.SUNDAY;
        }
    }

    private double clampPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }

    public LotterySettings lottery(LotteryType type) {
        return lotteries.get(type);
    }

    public HolidaySettings holiday() {
        return holidaySettings;
    }

    public String message(String key) {
        return prefix + Text.color(plugin.getConfig().getString("messages." + key, defaultMessage(key)));
    }

    public String prefixed(String message) {
        return prefix + Text.color(message);
    }

    public String invalidTypeMessage() {
        return prefix + Text.color("&c彩票类型只能是 daily、weekly 或 holiday。");
    }

    private String defaultMessage(String key) {
        return switch (key) {
            case "reset-denied" -> "&c你不是该高危操作的授权执行者。";
            case "reset-confirm" -> "&c高危操作：将清空 &e%type% &c的插件奖池记录并重置到第 1 期。&7请再次输入: &f%command%";
            case "reset-success" -> "&a已重置 &e%type% &a到第 1 期，清空显示奖池: &e%amount%";
            case "holiday-disabled" -> "&c节日公益活动当前未开放。";
            case "holiday-bet-success" -> "&a已参与 &e%outcome% &a分池，额度 &e%amount% &7(%current%/%max%)";
            case "holiday-pool-locked" -> "&cYou already selected another holiday pool this period. Refund before lock time to choose again.";
            case "holiday-refund-success" -> "&aRefunded &e%count% &aholiday bet(s), amount &e%amount%&a.";
            case "holiday-refund-empty" -> "&7You have no refundable holiday bets.";
            case "holiday-refund-locked" -> "&cHoliday refund is locked for this period.";
            case "holiday-refund-disabled" -> "&cHoliday refund is disabled.";
            case "holiday-refund-failed" -> "&cHoliday refund failed. Please contact an administrator.";
            case "refund-holiday-only" -> "&cOnly holiday public event bets can be refunded.";
            case "admin-refund-success" -> "&aRefunded &e%player% &aperiod &e%period% &aholiday bet(s): &e%count% &7/ &e%amount%";
            case "admin-refund-empty" -> "&7No refundable holiday bets were found.";
            case "invalid-period" -> "&cInvalid period number.";
            default -> key;
        };
    }

    public boolean canReset(UUID uuid) {
        return resetAllowedUuids.contains(uuid);
    }

    public String systemAccount() {
        return systemAccount;
    }

    public int menuRefreshTicks() {
        return menuRefreshTicks;
    }

    public int drawCheckTicks() {
        return drawCheckTicks;
    }

    public boolean samePlayerSinglePrize() {
        return samePlayerSinglePrize;
    }

    public boolean purchaseConfirm() {
        return purchaseConfirm;
    }

    public int purchaseConfirmSeconds() {
        return purchaseConfirmSeconds;
    }

    public MailSettings mail() {
        return mailSettings;
    }

    public MenuSettings menu() {
        return menuSettings;
    }

    public boolean broadcastEnabled() {
        return broadcastEnabled;
    }

    public String broadcastEmpty() {
        return broadcastEmpty;
    }

    public boolean broadcastHideEmptyPrizeLines() {
        return broadcastHideEmptyPrizeLines;
    }

    public List<String> broadcastLines() {
        return broadcastLines;
    }

    public List<String> broadcastCanceledLines() {
        return broadcastCanceledLines;
    }

    public List<String> broadcastNoTicketsLines() {
        return broadcastNoTicketsLines;
    }

    public boolean reminderEnabled() {
        return reminderEnabled;
    }

    public List<Integer> reminderMinutes() {
        return reminderMinutes;
    }

    public List<String> reminderLines() {
        return reminderLines;
    }

    public List<String> adminHelp() {
        List<String> help = new java.util.ArrayList<>(plugin.getConfig().getStringList("admin-help"));
        if (help.stream().noneMatch(line -> line.contains("holiday"))) {
            int insertAt = Math.max(0, help.size() - 1);
            help.add(insertAt, "&e/lottery draw <daily|weekly|holiday> &7- 手动开奖");
            help.add(insertAt + 1, "&e/lottery preview <daily|weekly|holiday> &7- 预览当前期开奖状态");
            help.add(insertAt + 2, "&e/lottery period <daily|weekly|holiday> &7- 查看当前期状态");
            help.add(insertAt + 3, "&e/lottery pool add <daily|weekly|holiday> <金额> &7- 给当前期增加额外奖池");
            help.add(insertAt + 4, "&e/lottery history [daily|weekly|holiday] &7- 查看上一期开奖结果");
            help.add(insertAt + 5, "&e/lottery refund <player> holiday [period] [redstone|obsidian|gold|all] &7- Refund holiday bets");
            help.add(insertAt + 6, "&e/lottery history [daily|weekly|holiday] [period] &7- View draw history");
        }
        return Text.color(help);
    }

    public long nextDrawAt(LotteryType type, long afterMillis) {
        ScheduleSettings schedule = type == LotteryType.HOLIDAY ? holiday().schedule() : lottery(type).schedule();
        if (!schedule.valid()) {
            return 0;
        }

        LocalDateTime after = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(afterMillis), ZoneId.systemDefault());
        if (schedule.interval().enabled()) {
            return after.plusMinutes(schedule.interval().minutes()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (schedule.fixedDaily().enabled()) {
            LocalDateTime next = after.toLocalDate().atTime(schedule.fixedDaily().time());
            if (!next.isAfter(after)) {
                next = next.plusDays(1);
            }
            return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (schedule.fixedWeekly().enabled()) {
            LocalDateTime next = after.toLocalDate().atTime(schedule.fixedWeekly().time());
            int addDays = schedule.fixedWeekly().day().getValue() - after.getDayOfWeek().getValue();
            if (addDays < 0 || (addDays == 0 && !next.isAfter(after))) {
                addDays += 7;
            }
            next = next.plusDays(addDays);
            return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return 0;
    }

    public record MailSettings(boolean enabled, String host, int port, boolean ssl, boolean starttls, String username,
                               String password, String from, String senderName, String subject, List<String> body) {
    }
}
