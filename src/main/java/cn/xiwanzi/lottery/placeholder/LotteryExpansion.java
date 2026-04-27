package cn.xiwanzi.lottery.placeholder;

import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.service.LotteryService;
import cn.xiwanzi.lottery.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class LotteryExpansion extends PlaceholderExpansion {
    private final JavaPlugin plugin;
    private final LotteryService lotteryService;

    public LotteryExpansion(JavaPlugin plugin, LotteryService lotteryService) {
        this.plugin = plugin;
        this.lotteryService = lotteryService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "lottery";
    }

    @Override
    public @NotNull String getAuthor() {
        return "xiwanzi";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase()) {
            case "daily_pool" -> Text.money(lotteryService.currentPool(LotteryType.DAILY));
            case "daily_tickets" -> Integer.toString(lotteryService.currentTickets(LotteryType.DAILY));
            case "daily_next_draw" -> Text.countdown(lotteryService.nextDrawAt(LotteryType.DAILY));
            case "daily_last_first_winner" -> lotteryService.lastFirstWinner(LotteryType.DAILY);
            case "weekly_pool" -> Text.money(lotteryService.currentPool(LotteryType.WEEKLY));
            case "weekly_tickets" -> Integer.toString(lotteryService.currentTickets(LotteryType.WEEKLY));
            case "weekly_next_draw" -> Text.countdown(lotteryService.nextDrawAt(LotteryType.WEEKLY));
            case "weekly_last_first_winner" -> lotteryService.lastFirstWinner(LotteryType.WEEKLY);
            case "holiday_pool" -> Text.money(lotteryService.currentPool(LotteryType.HOLIDAY));
            case "holiday_tickets" -> Integer.toString(lotteryService.currentTickets(LotteryType.HOLIDAY));
            case "holiday_next_draw" -> Text.countdown(lotteryService.nextDrawAt(LotteryType.HOLIDAY));
            case "holiday_last_first_winner" -> lotteryService.lastFirstWinner(LotteryType.HOLIDAY);
            default -> null;
        };
    }
}
