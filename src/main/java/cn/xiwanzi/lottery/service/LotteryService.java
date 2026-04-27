package cn.xiwanzi.lottery.service;

import cn.xiwanzi.lottery.config.ConfigManager;
import cn.xiwanzi.lottery.config.LotterySettings;
import cn.xiwanzi.lottery.config.RewardSettings;
import cn.xiwanzi.lottery.economy.EconomyService;
import cn.xiwanzi.lottery.mail.MailService;
import cn.xiwanzi.lottery.model.Award;
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

    public synchronized PurchaseResult buyTicket(Player player, LotteryType type) {
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
            return PurchaseResult.economyError(current, settings.maxPurchasesPerPlayer());
        }

        try {
            storage.addPurchasedTicket(type, period.periodId(), player.getUniqueId(), player.getName(), settings.price());
        } catch (RuntimeException ex) {
            economy.transferAccountToPlayer(configManager.systemAccount(), player, settings.price());
            throw ex;
        }
        return PurchaseResult.success(current + 1, settings.maxPurchasesPerPlayer());
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
        return rewardPool(storage.ticketPool(type, period.get().periodId()), configManager.lottery(type)) + period.get().rollover();
    }

    public int currentTickets(LotteryType type) {
        Optional<PeriodState> period = storage.getPeriod(type);
        return period.map(state -> storage.countTickets(type, state.periodId())).orElse(0);
    }

    public int currentPurchases(Player player, LotteryType type) {
        Optional<PeriodState> period = storage.getPeriod(type);
        return period.map(state -> storage.countPlayerTickets(type, state.periodId(), player.getUniqueId())).orElse(0);
    }

    public String lastFirstWinner(LotteryType type) {
        return storage.getPeriod(type).map(PeriodState::lastFirstWinner).orElse("");
    }

    public Preview preview(LotteryType type) {
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

    public record PurchaseResult(boolean success, boolean limit, boolean noMoney, boolean economyError, int current, int max) {
        public static PurchaseResult success(int current, int max) {
            return new PurchaseResult(true, false, false, false, current, max);
        }

        public static PurchaseResult limit(int current, int max) {
            return new PurchaseResult(false, true, false, false, current, max);
        }

        public static PurchaseResult noMoney(int current, int max) {
            return new PurchaseResult(false, false, true, false, current, max);
        }

        public static PurchaseResult economyError(int current, int max) {
            return new PurchaseResult(false, false, false, true, current, max);
        }
    }

    private record DrawOutcome(double rollover, String firstWinnerName, Map<PrizeTier, List<String>> namesByTier,
                               List<Award> awards) {
    }
}
