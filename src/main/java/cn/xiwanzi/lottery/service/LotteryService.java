package cn.xiwanzi.lottery.service;

import cn.xiwanzi.lottery.config.ConfigManager;
import cn.xiwanzi.lottery.config.HolidaySettings;
import cn.xiwanzi.lottery.config.LotterySettings;
import cn.xiwanzi.lottery.config.RewardSettings;
import cn.xiwanzi.lottery.economy.EconomyService;
import cn.xiwanzi.lottery.mail.MailService;
import cn.xiwanzi.lottery.model.Award;
import cn.xiwanzi.lottery.model.HolidayBet;
import cn.xiwanzi.lottery.model.HolidayOutcome;
import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.model.PeriodState;
import cn.xiwanzi.lottery.model.PrizeTier;
import cn.xiwanzi.lottery.model.Ticket;
import cn.xiwanzi.lottery.storage.StorageService;
import cn.xiwanzi.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class LotteryService {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final StorageService storage;
    private final EconomyService economy;
    private final MailService mail;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> sentReminders = new HashSet<>();
    private BukkitTask drawTask;

    public LotteryService(JavaPlugin plugin, ConfigManager configManager, StorageService storage, EconomyService economy, MailService mail) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.storage = storage;
        this.economy = economy;
        this.mail = mail;
    }

    public void start() {
        long now = System.currentTimeMillis();
        for (LotteryType type : LotteryType.values()) {
            storage.getOrCreatePeriod(type, configManager.nextDrawAt(type, now));
        }
        drawTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDraws, configManager.drawCheckTicks(), configManager.drawCheckTicks());
    }

    public void shutdown() {
        if (drawTask != null) {
            drawTask.cancel();
        }
    }

    public synchronized void reloadSchedules() {
        long now = System.currentTimeMillis();
        for (LotteryType type : LotteryType.values()) {
            storage.getOrCreatePeriod(type, configManager.nextDrawAt(type, now));
            storage.setNextDrawAt(type, configManager.nextDrawAt(type, now));
        }
    }

    public synchronized void ensurePeriods() {
        long now = System.currentTimeMillis();
        for (LotteryType type : LotteryType.values()) {
            storage.getOrCreatePeriod(type, configManager.nextDrawAt(type, now));
        }
    }

    public synchronized PurchaseResult buyTicket(Player player, LotteryType type) {
        if (type == LotteryType.HOLIDAY) {
            return PurchaseResult.economyErrorResult();
        }
        LotterySettings settings = configManager.lottery(type);
        PeriodState period = storage.getOrCreatePeriod(type, configManager.nextDrawAt(type, System.currentTimeMillis()));
        int current = storage.countPlayerTickets(type, period.periodId(), player.getUniqueId());
        if (current >= settings.maxPurchasesPerPlayer()) {
            return PurchaseResult.limit(current, settings.maxPurchasesPerPlayer());
        }
        if (!economy.has(player, settings.price())) {
            return PurchaseResult.noMoney(current, settings.maxPurchasesPerPlayer());
        }
        if (!economy.transferPlayerToAccount(player, configManager.systemAccount(), settings.price())) {
            return PurchaseResult.economyErrorResult(current, settings.maxPurchasesPerPlayer());
        }

        try {
            storage.addPurchasedTicket(type, period.periodId(), player.getUniqueId(), player.getName(), settings.price());
        } catch (RuntimeException ex) {
            economy.transferAccountToPlayer(configManager.systemAccount(), player, settings.price());
            throw ex;
        }
        return PurchaseResult.success(current + 1, settings.maxPurchasesPerPlayer());
    }

    public synchronized PurchaseResult buyHolidayBet(Player player, HolidayOutcome outcome, double amount) {
        HolidaySettings settings = configManager.holiday();
        if (!settings.enabled()) {
            return PurchaseResult.disabledResult();
        }
        boolean allowedAmount = settings.betAmounts().stream().anyMatch(configured -> Math.abs(configured - amount) < 0.0001);
        if (!allowedAmount) {
            return PurchaseResult.invalidResult();
        }
        PeriodState period = storage.getOrCreatePeriod(LotteryType.HOLIDAY, configManager.nextDrawAt(LotteryType.HOLIDAY, System.currentTimeMillis()));
        int current = storage.countHolidayPlayerBets(period.periodId(), player.getUniqueId());
        if (current >= settings.maxBetsPerPlayer()) {
            return PurchaseResult.limit(current, settings.maxBetsPerPlayer());
        }
        if (!economy.has(player, amount)) {
            return PurchaseResult.noMoney(current, settings.maxBetsPerPlayer());
        }
        if (!economy.transferPlayerToAccount(player, configManager.systemAccount(), amount)) {
            return PurchaseResult.economyErrorResult();
        }
        try {
            storage.addPurchasedHolidayBet(period.periodId(), outcome, player.getUniqueId(), player.getName(), amount);
        } catch (RuntimeException ex) {
            if (!economy.transferAccountToPlayer(configManager.systemAccount(), player, amount)) {
                plugin.getLogger().warning("Holiday bet storage failed and refund failed for " + player.getName()
                        + " amount " + Text.money(amount) + ": " + ex.getMessage());
            }
            throw ex;
        }
        return PurchaseResult.success(current + 1, settings.maxBetsPerPlayer());
    }

    public synchronized void drawNow(LotteryType type) {
        draw(type);
    }

    public synchronized boolean addPool(LotteryType type, double amount, String operatorName) {
        if (amount <= 0) {
            return false;
        }
        PeriodState period = storage.getOrCreatePeriod(type, configManager.nextDrawAt(type, System.currentTimeMillis()));
        if (!economy.depositAccount(configManager.systemAccount(), amount)) {
            return false;
        }
        storage.addRollover(type, amount);
        storage.recordLedger(type, period.periodId(), "POOL_ADD", null, configManager.systemAccount(), amount,
                "operator:" + operatorName);
        return true;
    }

    public synchronized ResetResult resetPool(LotteryType type, UUID operatorUuid, String operatorName) {
        double clearedPool = currentPool(type);
        long nextDrawAt = configManager.nextDrawAt(type, System.currentTimeMillis());
        storage.resetLottery(type, nextDrawAt, clearedPool, operatorUuid, operatorName);
        sentReminders.removeIf(key -> key.startsWith(type.key() + ":"));
        plugin.getLogger().warning(operatorName + " reset " + type.key() + " lottery to period 1. Cleared pool: " + Text.money(clearedPool));
        return new ResetResult(type, clearedPool, nextDrawAt);
    }

    private synchronized void checkDraws() {
        long now = System.currentTimeMillis();
        for (LotteryType type : LotteryType.values()) {
            if (type == LotteryType.HOLIDAY && !configManager.holiday().enabled()) {
                continue;
            }
            Optional<PeriodState> period = storage.getPeriod(type);
            period.ifPresent(state -> sendReminders(type, state, now));
            if (period.isPresent() && period.get().nextDrawAt() > 0 && now >= period.get().nextDrawAt()) {
                draw(type);
            }
        }
    }

    private void sendReminders(LotteryType type, PeriodState period, long now) {
        if (!configManager.reminderEnabled() || period.nextDrawAt() <= 0) {
            return;
        }
        long remainingMillis = period.nextDrawAt() - now;
        if (remainingMillis <= 0) {
            return;
        }
        for (int minute : configManager.reminderMinutes()) {
            if (minute <= 0) {
                continue;
            }
            long threshold = minute * 60_000L;
            if (remainingMillis <= threshold) {
                String key = type.key() + ":" + period.periodId() + ":" + minute;
                if (sentReminders.add(key)) {
                    broadcastReminder(type, minute);
                }
            }
        }
    }

    private void broadcastReminder(LotteryType type, int minutes) {
        LotterySettings settings = configManager.lottery(type);
        for (String line : configManager.reminderLines()) {
            Bukkit.broadcastMessage(Text.color(line
                    .replace("%type%", settings.displayName())
                    .replace("%minutes%", Integer.toString(minutes))
                    .replace("%pool%", Text.money(currentPool(type)))
                    .replace("%min_pool%", Text.money(settings.minTotalPool()))));
        }
    }

    private void draw(LotteryType type) {
        if (type == LotteryType.HOLIDAY) {
            drawHoliday();
            return;
        }
        LotterySettings settings = configManager.lottery(type);
        PeriodState period = storage.getOrCreatePeriod(type, configManager.nextDrawAt(type, System.currentTimeMillis()));
        List<Ticket> tickets = storage.tickets(type, period.periodId());
        double ticketPool = storage.ticketPool(type, period.periodId());
        double effectivePool = rewardPool(ticketPool, settings) + period.rollover();
        long nextDrawAt = configManager.nextDrawAt(type, System.currentTimeMillis());

        if (tickets.isEmpty()) {
            broadcastNoTickets(type, period.periodId(), settings, effectivePool);
            storage.advancePeriod(type, nextDrawAt, period.rollover(), period.lastFirstWinner());
            plugin.getLogger().info(type.key() + " period " + period.periodId() + " canceled because no tickets were sold. Pool: " + Text.money(effectivePool));
            return;
        }

        if (effectivePool < settings.minTotalPool()) {
            boolean refunded = refund(type, period, tickets);
            if (!refunded) {
                plugin.getLogger().warning(type.key() + " period " + period.periodId() + " refund failed. Period was not advanced.");
                return;
            }
            broadcastCanceled(type, period.periodId(), settings, effectivePool);
            storage.advancePeriod(type, nextDrawAt, period.rollover(), period.lastFirstWinner());
            plugin.getLogger().info(type.key() + " period " + period.periodId() + " canceled and refunded. Pool: " + Text.money(effectivePool));
            return;
        }

        double houseAmount = ticketPool * settings.housePoolPercent() / 100.0;
        if (houseAmount > 0 && !storage.hasLedger(type, period.periodId(), "HOUSE_RETAIN", null, "house share")) {
            storage.recordLedger(type, period.periodId(), "HOUSE_RETAIN", null, configManager.systemAccount(), houseAmount, "house share");
        }

        double prizePool = rewardPool(ticketPool, settings) + period.rollover();
        DrawOutcome outcome = prepareDrawOutcome(type, period.periodId(), settings, tickets, prizePool);
        if (!payAwards(type, period.periodId(), settings, outcome.awards())) {
            plugin.getLogger().warning(type.key() + " period " + period.periodId() + " prize payment failed. Period was not advanced.");
            return;
        }
        broadcastDraw(type, period.periodId(), settings, outcome);
        storage.advancePeriod(type, nextDrawAt, outcome.rollover(), outcome.firstWinnerName());
        plugin.getLogger().info(type.key() + " period " + period.periodId() + " drawn. Prize pool: " + Text.money(prizePool)
                + ", rollover: " + Text.money(outcome.rollover()));
    }

    private boolean refund(LotteryType type, PeriodState period, List<Ticket> tickets) {
        boolean success = true;
        for (Ticket ticket : tickets) {
            String note = "ticket-" + ticket.id();
            if (storage.hasLedger(type, period.periodId(), "REFUND", ticket.playerUuid(), note)) {
                continue;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(ticket.playerUuid());
            if (!economy.transferAccountToPlayer(configManager.systemAccount(), player, ticket.price())) {
                plugin.getLogger().warning("Failed to refund " + ticket.playerName() + " amount " + Text.money(ticket.price()));
                storage.recordLedger(type, period.periodId(), "REFUND_FAILED", ticket.playerUuid(), ticket.playerName(), ticket.price(), note);
                success = false;
                continue;
            }
            storage.recordLedger(type, period.periodId(), "REFUND", ticket.playerUuid(), ticket.playerName(), ticket.price(), note);
        }
        return success;
    }

    private DrawOutcome prepareDrawOutcome(LotteryType type, long periodId, LotterySettings settings, List<Ticket> tickets, double prizePool) {
        List<Award> existingAwards = storage.awards(type, periodId);
        if (!existingAwards.isEmpty()) {
            return outcomeFromAwards(existingAwards, prizePool);
        }

        Map<PrizeTier, Double> tierPools = new EnumMap<>(PrizeTier.class);
        double configuredPercent = 0;
        for (PrizeTier tier : PrizeTier.values()) {
            RewardSettings reward = settings.rewards().get(tier);
            configuredPercent += reward.poolPercent();
        }
        double scale = configuredPercent > 100 ? 100.0 / configuredPercent : 1.0;

        double allocated = 0;
        for (PrizeTier tier : PrizeTier.values()) {
            RewardSettings reward = settings.rewards().get(tier);
            double tierPool = prizePool * (reward.poolPercent() * scale) / 100.0;
            tierPools.put(tier, tierPool);
            allocated += tierPool;
        }

        Set<UUID> excludedPlayers = new HashSet<>();
        String firstWinnerName = "";
        double rollover = Math.max(0, prizePool - allocated);
        Map<PrizeTier, List<String>> namesByTier = new EnumMap<>(PrizeTier.class);
        List<Award> awards = new ArrayList<>();

        for (PrizeTier tier : PrizeTier.values()) {
            RewardSettings reward = settings.rewards().get(tier);
            int wantedWinners = tier == PrizeTier.FIRST ? 1 : reward.winners();
            List<Ticket> winningTickets = chooseWinners(tickets, excludedPlayers, wantedWinners);
            List<String> tierNames = new ArrayList<>();
            namesByTier.put(tier, tierNames);
            double tierPool = tierPools.getOrDefault(tier, 0.0);
            if (winningTickets.isEmpty()) {
                rollover += tierPool;
                continue;
            }

            double amountEach = tierPool / winningTickets.size();
            for (Ticket ticket : winningTickets) {
                awards.add(new Award(type, periodId, tier, ticket.playerUuid(), ticket.playerName(), amountEach));
                tierNames.add(ticket.playerName());
                if (tier == PrizeTier.FIRST) {
                    firstWinnerName = ticket.playerName();
                }
            }
            if (configManager.samePlayerSinglePrize()) {
                for (Ticket ticket : winningTickets) {
                    excludedPlayers.add(ticket.playerUuid());
                }
            }
        }

        storage.saveAwards(awards);
        return new DrawOutcome(rollover, firstWinnerName, namesByTier, awards);
    }

    private DrawOutcome outcomeFromAwards(List<Award> awards, double prizePool) {
        Map<PrizeTier, List<String>> namesByTier = new EnumMap<>(PrizeTier.class);
        double paid = 0;
        String firstWinnerName = "";
        for (Award award : awards) {
            namesByTier.computeIfAbsent(award.tier(), ignored -> new ArrayList<>()).add(award.playerName());
            paid += award.amount();
            if (award.tier() == PrizeTier.FIRST && firstWinnerName.isBlank()) {
                firstWinnerName = award.playerName();
            }
        }
        return new DrawOutcome(Math.max(0, prizePool - paid), firstWinnerName, namesByTier, awards);
    }

    private boolean payAwards(LotteryType type, long periodId, LotterySettings settings, List<Award> awards) {
        boolean success = true;
        for (Award award : awards) {
            String action = "PRIZE_" + award.tier().key().toUpperCase();
            String note = award.tier().key() + "-" + award.playerUuid();
            if (storage.hasLedger(type, periodId, action, award.playerUuid(), note)) {
                continue;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(award.playerUuid());
            if (!economy.transferAccountToPlayer(configManager.systemAccount(), player, award.amount())) {
                plugin.getLogger().warning("Failed to pay " + award.playerName() + " amount " + Text.money(award.amount()));
                storage.recordLedger(type, periodId, action + "_FAILED", award.playerUuid(), award.playerName(), award.amount(), note);
                success = false;
                continue;
            }
            storage.recordLedger(type, periodId, action, award.playerUuid(), award.playerName(), award.amount(), note);
            mail.sendWinMail(award.playerUuid(), award.playerName(), settings.displayName(), award.tier().displayName(), award.amount());
        }
        return success;
    }

    private double rewardPool(double ticketPool, LotterySettings settings) {
        return ticketPool * settings.rewardPoolPercent() / 100.0;
    }

    private double holidayRewardPool(double totalPool, HolidaySettings settings) {
        return totalPool * settings.rewardPoolPercent() / 100.0;
    }

    private void drawHoliday() {
        HolidaySettings settings = configManager.holiday();
        PeriodState period = storage.getOrCreatePeriod(LotteryType.HOLIDAY, configManager.nextDrawAt(LotteryType.HOLIDAY, System.currentTimeMillis()));
        List<HolidayBet> bets = storage.holidayBets(period.periodId());
        double totalPool = storage.holidayBetPool(period.periodId());
        double effectivePool = holidayRewardPool(totalPool, settings) + period.rollover();
        long nextDrawAt = configManager.nextDrawAt(LotteryType.HOLIDAY, System.currentTimeMillis());

        if (bets.isEmpty()) {
            broadcastNoTickets(LotteryType.HOLIDAY, period.periodId(), configManager.lottery(LotteryType.HOLIDAY), effectivePool);
            storage.advancePeriod(LotteryType.HOLIDAY, nextDrawAt, period.rollover(), period.lastFirstWinner());
            plugin.getLogger().info("holiday period " + period.periodId() + " canceled because no bets were placed. Pool: "
                    + Text.money(effectivePool));
            return;
        }

        if (effectivePool < settings.minTotalPool()) {
            boolean refunded = refundHoliday(period, bets);
            if (!refunded) {
                plugin.getLogger().warning("holiday period " + period.periodId() + " refund failed. Period was not advanced.");
                return;
            }
            broadcastCanceled(LotteryType.HOLIDAY, period.periodId(), configManager.lottery(LotteryType.HOLIDAY), effectivePool);
            storage.advancePeriod(LotteryType.HOLIDAY, nextDrawAt, period.rollover(), period.lastFirstWinner());
            plugin.getLogger().info("holiday period " + period.periodId() + " canceled and refunded. Pool: " + Text.money(effectivePool));
            return;
        }

        double houseAmount = totalPool * settings.housePoolPercent() / 100.0;
        if (houseAmount > 0 && !storage.hasLedger(LotteryType.HOLIDAY, period.periodId(), "HOLIDAY_FEE", null, "holiday fee")) {
            storage.recordLedger(LotteryType.HOLIDAY, period.periodId(), "HOLIDAY_FEE", null, configManager.systemAccount(), houseAmount, "holiday fee");
        }

        HolidayOutcome outcome = storedOrDrawHolidayOutcome(period.periodId(), settings);
        List<HolidayBet> winningBets = bets.stream()
                .filter(bet -> bet.outcome() == outcome)
                .toList();
        if (winningBets.isEmpty()) {
            if (!storage.hasLedger(LotteryType.HOLIDAY, period.periodId(), "HOLIDAY_ROLLOVER", null, outcome.key())) {
                storage.recordLedger(LotteryType.HOLIDAY, period.periodId(), "HOLIDAY_ROLLOVER", null,
                        configManager.systemAccount(), effectivePool, outcome.key());
            }
            broadcastHolidayRollover(period.periodId(), settings, outcome, effectivePool);
            storage.advancePeriod(LotteryType.HOLIDAY, nextDrawAt, effectivePool, period.lastFirstWinner());
            plugin.getLogger().info("holiday period " + period.periodId() + " drawn with no winners. Outcome: "
                    + outcome.key() + ", rollover: " + Text.money(effectivePool));
            return;
        }

        List<HolidayAward> awards = holidayAwards(winningBets, effectivePool);
        if (!payHolidayAwards(period.periodId(), settings, outcome, awards)) {
            plugin.getLogger().warning("holiday period " + period.periodId() + " prize payment failed. Period was not advanced.");
            return;
        }
        String topWinner = awards.stream()
                .max(Comparator.comparingDouble(HolidayAward::amount))
                .map(HolidayAward::playerName)
                .orElse(period.lastFirstWinner());
        broadcastHolidayDraw(period.periodId(), settings, outcome, awards, effectivePool);
        storage.advancePeriod(LotteryType.HOLIDAY, nextDrawAt, 0, topWinner);
        plugin.getLogger().info("holiday period " + period.periodId() + " drawn. Outcome: " + outcome.key()
                + ", paid: " + Text.money(effectivePool));
    }

    private HolidayOutcome storedOrDrawHolidayOutcome(long periodId, HolidaySettings settings) {
        Optional<String> stored = storage.firstLedgerNote(LotteryType.HOLIDAY, periodId, "HOLIDAY_OUTCOME");
        if (stored.isPresent()) {
            return HolidayOutcome.from(stored.get()).orElse(HolidayOutcome.REDSTONE);
        }
        HolidayOutcome outcome = drawHolidayOutcome(settings);
        storage.recordLedger(LotteryType.HOLIDAY, periodId, "HOLIDAY_OUTCOME", null, configManager.systemAccount(), 0, outcome.key());
        return outcome;
    }

    private HolidayOutcome drawHolidayOutcome(HolidaySettings settings) {
        double totalWeight = settings.outcomes().values().stream()
                .mapToDouble(HolidaySettings.OutcomeSettings::chancePercent)
                .sum();
        if (totalWeight <= 0) {
            return HolidayOutcome.REDSTONE;
        }
        double point = random.nextDouble() * totalWeight;
        double current = 0;
        for (HolidayOutcome outcome : HolidayOutcome.values()) {
            HolidaySettings.OutcomeSettings outcomeSettings = settings.outcome(outcome);
            current += outcomeSettings == null ? 0 : outcomeSettings.chancePercent();
            if (point <= current) {
                return outcome;
            }
        }
        return HolidayOutcome.REDSTONE;
    }

    private List<HolidayAward> holidayAwards(List<HolidayBet> winningBets, double prizePool) {
        double winningStake = winningBets.stream().mapToDouble(HolidayBet::amount).sum();
        Map<UUID, HolidayAwardAccumulator> byPlayer = new java.util.LinkedHashMap<>();
        for (HolidayBet bet : winningBets) {
            HolidayAwardAccumulator accumulator = byPlayer.computeIfAbsent(bet.playerUuid(),
                    ignored -> new HolidayAwardAccumulator(bet.playerUuid(), bet.playerName()));
            accumulator.amount += winningStake <= 0 ? 0 : prizePool * bet.amount() / winningStake;
        }
        List<HolidayAward> awards = new ArrayList<>();
        for (HolidayAwardAccumulator accumulator : byPlayer.values()) {
            awards.add(new HolidayAward(accumulator.playerUuid, accumulator.playerName, accumulator.amount));
        }
        return awards;
    }

    private boolean payHolidayAwards(long periodId, HolidaySettings settings, HolidayOutcome outcome, List<HolidayAward> awards) {
        boolean success = true;
        for (HolidayAward award : awards) {
            String note = "holiday-" + outcome.key() + "-" + award.playerUuid();
            if (storage.hasLedger(LotteryType.HOLIDAY, periodId, "HOLIDAY_PAYOUT", award.playerUuid(), note)) {
                continue;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(award.playerUuid());
            if (!economy.transferAccountToPlayer(configManager.systemAccount(), player, award.amount())) {
                plugin.getLogger().warning("Failed to pay holiday prize to " + award.playerName() + " amount " + Text.money(award.amount()));
                storage.recordLedger(LotteryType.HOLIDAY, periodId, "HOLIDAY_PAYOUT_FAILED", award.playerUuid(),
                        award.playerName(), award.amount(), note);
                success = false;
                continue;
            }
            storage.recordLedger(LotteryType.HOLIDAY, periodId, "HOLIDAY_PAYOUT", award.playerUuid(), award.playerName(), award.amount(), note);
            mail.sendWinMail(award.playerUuid(), award.playerName(), settings.displayName(),
                    settings.outcome(outcome).displayName(), award.amount());
        }
        return success;
    }

    private boolean refundHoliday(PeriodState period, List<HolidayBet> bets) {
        boolean success = true;
        for (HolidayBet bet : bets) {
            String note = "holiday-bet-" + bet.id();
            if (storage.hasLedger(LotteryType.HOLIDAY, period.periodId(), "REFUND", bet.playerUuid(), note)) {
                continue;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(bet.playerUuid());
            if (!economy.transferAccountToPlayer(configManager.systemAccount(), player, bet.amount())) {
                plugin.getLogger().warning("Failed to refund holiday bet for " + bet.playerName() + " amount " + Text.money(bet.amount()));
                storage.recordLedger(LotteryType.HOLIDAY, period.periodId(), "REFUND_FAILED", bet.playerUuid(), bet.playerName(), bet.amount(), note);
                success = false;
                continue;
            }
            storage.recordLedger(LotteryType.HOLIDAY, period.periodId(), "REFUND", bet.playerUuid(), bet.playerName(), bet.amount(), note);
        }
        return success;
    }

    private void broadcastDraw(LotteryType type, long periodId, LotterySettings settings, DrawOutcome outcome) {
        if (!configManager.broadcastEnabled()) {
            return;
        }
        for (String line : configManager.broadcastLines()) {
            List<String> firstWinners = outcome.namesByTier().get(PrizeTier.FIRST);
            List<String> secondWinners = outcome.namesByTier().get(PrizeTier.SECOND);
            List<String> thirdWinners = outcome.namesByTier().get(PrizeTier.THIRD);
            if (shouldHidePrizeLine(line, "%first%", firstWinners)
                    || shouldHidePrizeLine(line, "%second%", secondWinners)
                    || shouldHidePrizeLine(line, "%third%", thirdWinners)) {
                continue;
            }
            Bukkit.broadcastMessage(Text.color(line
                    .replace("%period%", Long.toString(periodId))
                    .replace("%type%", settings.displayName())
                    .replace("%first%", names(firstWinners))
                    .replace("%second%", names(secondWinners))
                    .replace("%third%", names(thirdWinners))));
        }
    }

    private void broadcastCanceled(LotteryType type, long periodId, LotterySettings settings, double pool) {
        if (!configManager.broadcastEnabled()) {
            return;
        }
        for (String line : configManager.broadcastCanceledLines()) {
            Bukkit.broadcastMessage(Text.color(line
                    .replace("%period%", Long.toString(periodId))
                    .replace("%type%", settings.displayName())
                    .replace("%pool%", Text.money(pool))
                    .replace("%min_pool%", Text.money(settings.minTotalPool()))));
        }
    }

    private void broadcastNoTickets(LotteryType type, long periodId, LotterySettings settings, double pool) {
        if (!configManager.broadcastEnabled()) {
            return;
        }
        for (String line : configManager.broadcastNoTicketsLines()) {
            Bukkit.broadcastMessage(Text.color(line
                    .replace("%period%", Long.toString(periodId))
                    .replace("%type%", settings.displayName())
                    .replace("%pool%", Text.money(pool))
                    .replace("%min_pool%", Text.money(settings.minTotalPool()))));
        }
    }

    private void broadcastHolidayDraw(long periodId, HolidaySettings settings, HolidayOutcome outcome,
                                      List<HolidayAward> awards, double pool) {
        if (!configManager.broadcastEnabled()) {
            return;
        }
        String winners = awards.stream()
                .map(award -> award.playerName() + "(" + Text.money(award.amount()) + ")")
                .collect(Collectors.joining(", "));
        Bukkit.broadcastMessage(Text.color("&8[&6建筑彩票&8] &e第 &f" + periodId + " &e期 "
                + settings.displayName() + " &e正式开奖！"));
        Bukkit.broadcastMessage(Text.color("&8[&6建筑彩票&8] &7本期结果: &f"
                + settings.outcome(outcome).displayName() + " &7奖池: &a" + Text.money(pool)));
        Bukkit.broadcastMessage(Text.color("&8[&6建筑彩票&8] &6分池得主: &f" + winners));
    }

    private void broadcastHolidayRollover(long periodId, HolidaySettings settings, HolidayOutcome outcome, double pool) {
        if (!configManager.broadcastEnabled()) {
            return;
        }
        Bukkit.broadcastMessage(Text.color("&8[&6建筑彩票&8] &e第 &f" + periodId + " &e期 "
                + settings.displayName() + " &e正式开奖！"));
        Bukkit.broadcastMessage(Text.color("&8[&6建筑彩票&8] &7本期结果: &f"
                + settings.outcome(outcome).displayName() + " &7无人命中，奖池 &a" + Text.money(pool) + " &7滚入下一期。"));
    }

    private boolean shouldHidePrizeLine(String line, String placeholder, List<String> winners) {
        return configManager.broadcastHideEmptyPrizeLines()
                && line.contains(placeholder)
                && (winners == null || winners.isEmpty());
    }

    private String names(List<String> names) {
        if (names == null || names.isEmpty()) {
            return configManager.broadcastEmpty();
        }
        return String.join(", ", names);
    }

    private List<Ticket> chooseWinners(List<Ticket> tickets, Set<UUID> excludedPlayers, int wantedWinners) {
        List<Ticket> winners = new ArrayList<>();
        Set<UUID> pickedThisTier = new HashSet<>();
        for (int i = 0; i < wantedWinners; i++) {
            List<Ticket> eligible = new ArrayList<>();
            for (Ticket ticket : tickets) {
                if (!excludedPlayers.contains(ticket.playerUuid()) && !pickedThisTier.contains(ticket.playerUuid())) {
                    eligible.add(ticket);
                }
            }
            if (eligible.isEmpty()) {
                break;
            }
            Ticket selected = eligible.get(random.nextInt(eligible.size()));
            winners.add(selected);
            pickedThisTier.add(selected.playerUuid());
        }
        return winners;
    }

    public long nextDrawAt(LotteryType type) {
        return storage.getPeriod(type).map(PeriodState::nextDrawAt).orElse(0L);
    }

    public double currentPool(LotteryType type) {
        Optional<PeriodState> period = storage.getPeriod(type);
        if (period.isEmpty()) {
            return 0;
        }
        if (type == LotteryType.HOLIDAY) {
            return holidayRewardPool(storage.holidayBetPool(period.get().periodId()), configManager.holiday()) + period.get().rollover();
        }
        return rewardPool(storage.ticketPool(type, period.get().periodId()), configManager.lottery(type)) + period.get().rollover();
    }

    public int currentTickets(LotteryType type) {
        Optional<PeriodState> period = storage.getPeriod(type);
        if (type == LotteryType.HOLIDAY) {
            return period.map(state -> storage.countHolidayBets(state.periodId())).orElse(0);
        }
        return period.map(state -> storage.countTickets(type, state.periodId())).orElse(0);
    }

    public double holidayOutcomePool(HolidayOutcome outcome) {
        Optional<PeriodState> period = storage.getPeriod(LotteryType.HOLIDAY);
        return period.map(state -> storage.holidayBetPool(state.periodId(), outcome)).orElse(0.0);
    }

    public int currentPurchases(Player player, LotteryType type) {
        Optional<PeriodState> period = storage.getPeriod(type);
        if (type == LotteryType.HOLIDAY) {
            return period.map(state -> storage.countHolidayPlayerBets(state.periodId(), player.getUniqueId())).orElse(0);
        }
        return period.map(state -> storage.countPlayerTickets(type, state.periodId(), player.getUniqueId())).orElse(0);
    }

    public String lastFirstWinner(LotteryType type) {
        return storage.getPeriod(type).map(PeriodState::lastFirstWinner).orElse("");
    }

    public Preview preview(LotteryType type) {
        if (type == LotteryType.HOLIDAY) {
            HolidaySettings settings = configManager.holiday();
            Optional<PeriodState> period = storage.getPeriod(type);
            if (period.isEmpty()) {
                return new Preview(type, 0, 0, 0, 0, settings.minTotalPool(), false, List.of(), 0);
            }
            List<HolidayBet> bets = storage.holidayBets(period.get().periodId());
            double prizePool = holidayRewardPool(storage.holidayBetPool(period.get().periodId()), settings) + period.get().rollover();
            List<String> players = bets.stream().map(HolidayBet::playerName).distinct().collect(Collectors.toList());
            return new Preview(type, period.get().periodId(), bets.size(), players.size(), prizePool,
                    settings.minTotalPool(), prizePool >= settings.minTotalPool(), players, period.get().nextDrawAt());
        }
        LotterySettings settings = configManager.lottery(type);
        Optional<PeriodState> period = storage.getPeriod(type);
        if (period.isEmpty()) {
            return new Preview(type, 0, 0, 0, 0, settings.minTotalPool(), false, List.of(), 0);
        }
        List<Ticket> tickets = storage.tickets(type, period.get().periodId());
        double ticketPool = storage.ticketPool(type, period.get().periodId());
        double prizePool = rewardPool(ticketPool, settings) + period.get().rollover();
        List<String> players = tickets.stream().map(Ticket::playerName).distinct().collect(Collectors.toList());
        return new Preview(type, period.get().periodId(), tickets.size(), players.size(), prizePool,
                settings.minTotalPool(), prizePool >= settings.minTotalPool(), players, period.get().nextDrawAt());
    }

    public record Preview(LotteryType type, long periodId, int tickets, int players, double pool, double minPool,
                          boolean drawable, List<String> playerNames, long nextDrawAt) {
    }

    public record ResetResult(LotteryType type, double clearedPool, long nextDrawAt) {
    }

    public record PurchaseResult(boolean success, boolean limit, boolean noMoney, boolean economyError,
                                 boolean disabled, boolean invalid, int current, int max) {
        public static PurchaseResult success(int current, int max) {
            return new PurchaseResult(true, false, false, false, false, false, current, max);
        }

        public static PurchaseResult limit(int current, int max) {
            return new PurchaseResult(false, true, false, false, false, false, current, max);
        }

        public static PurchaseResult noMoney(int current, int max) {
            return new PurchaseResult(false, false, true, false, false, false, current, max);
        }

        public static PurchaseResult economyErrorResult() {
            return new PurchaseResult(false, false, false, true, false, false, 0, 0);
        }

        public static PurchaseResult economyErrorResult(int current, int max) {
            return new PurchaseResult(false, false, false, true, false, false, current, max);
        }

        public static PurchaseResult disabledResult() {
            return new PurchaseResult(false, false, false, false, true, false, 0, 0);
        }

        public static PurchaseResult invalidResult() {
            return new PurchaseResult(false, false, false, false, false, true, 0, 0);
        }
    }

    private record DrawOutcome(double rollover, String firstWinnerName, Map<PrizeTier, List<String>> namesByTier,
                               List<Award> awards) {
    }

    private record HolidayAward(UUID playerUuid, String playerName, double amount) {
    }

    private static final class HolidayAwardAccumulator {
        private final UUID playerUuid;
        private final String playerName;
        private double amount;

        private HolidayAwardAccumulator(UUID playerUuid, String playerName) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }
    }
}
