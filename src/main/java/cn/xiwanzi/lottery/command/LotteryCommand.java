package cn.xiwanzi.lottery.command;

import cn.xiwanzi.lottery.config.ConfigManager;
import cn.xiwanzi.lottery.mail.MailService;
import cn.xiwanzi.lottery.menu.MenuManager;
import cn.xiwanzi.lottery.model.Award;
import cn.xiwanzi.lottery.model.LedgerEntry;
import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.model.PeriodState;
import cn.xiwanzi.lottery.service.LotteryService;
import cn.xiwanzi.lottery.storage.StorageService;
import cn.xiwanzi.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class LotteryCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final StorageService storage;
    private final LotteryService lotteryService;
    private final MenuManager menuManager;
    private final MailService mailService;

    public LotteryCommand(JavaPlugin plugin, ConfigManager configManager, StorageService storage, LotteryService lotteryService,
                          MenuManager menuManager, MailService mailService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.storage = storage;
        this.lotteryService = lotteryService;
        this.menuManager = menuManager;
        this.mailService = mailService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            return open(sender, args);
        }
        if (args[0].equalsIgnoreCase("reload")) {
            return reload(sender);
        }
        if (args[0].equalsIgnoreCase("email")) {
            return email(sender, args);
        }
        if (args[0].equalsIgnoreCase("info")) {
            return emailInfo(sender, args, 1);
        }
        if (args[0].equalsIgnoreCase("bind")) {
            return emailBind(sender, args, 1);
        }
        if (args[0].equalsIgnoreCase("unbind")) {
            return emailUnbind(sender);
        }
        if (args[0].equalsIgnoreCase("edit")) {
            return emailEdit(sender, args, 1);
        }
        if (args[0].equalsIgnoreCase("draw")) {
            return draw(sender, args);
        }
        if (args[0].equalsIgnoreCase("preview")) {
            return preview(sender, args);
        }
        if (args[0].equalsIgnoreCase("history")) {
            return history(sender, args);
        }
        if (args[0].equalsIgnoreCase("ledger")) {
            return ledger(sender, args);
        }
        if (args[0].equalsIgnoreCase("period")) {
            return period(sender, args);
        }
        if (args[0].equalsIgnoreCase("mailtest")) {
            return mailtest(sender, args);
        }
        if (args[0].equalsIgnoreCase("help")) {
            return help(sender);
        }
        if (args[0].equalsIgnoreCase("pool")) {
            return pool(sender, args);
        }
        if (args[0].equalsIgnoreCase("reset")) {
            return reset(sender, args);
        }
        sender.sendMessage("/" + label + " [open|reload|info|bind|unbind|edit|email|draw|reset]");
        return true;
    }

    private boolean open(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            if (!sender.hasPermission("lottery.admin.open")) {
                sender.sendMessage(configManager.message("no-permission"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(configManager.message("player-not-found"));
                return true;
            }
            menuManager.open(target);
            sender.sendMessage(configManager.message("target-menu-opened").replace("%player%", target.getName()));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (!player.hasPermission("lottery.use")) {
            player.sendMessage(configManager.message("no-permission"));
            return true;
        }
        menuManager.open(player);
        player.sendMessage(configManager.message("menu-opened"));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin.reload")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (plugin instanceof cn.xiwanzi.lottery.LotteryPlugin lotteryPlugin) {
            lotteryPlugin.reloadLottery();
        }
        sender.sendMessage(configManager.message("reload-success"));
        return true;
    }

    private boolean draw(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.draw")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("/lottery draw <daily|weekly>");
            return true;
        }
        Optional<LotteryType> type = LotteryType.from(args[1]);
        if (type.isEmpty()) {
            sender.sendMessage(configManager.message("invalid-type"));
            return true;
        }
        lotteryService.drawNow(type.get());
        sender.sendMessage(configManager.message("draw-started").replace("%type%", type.get().key()));
        return true;
    }

    private boolean preview(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.preview")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        Optional<LotteryType> type = parseType(sender, args, 1);
        if (type.isEmpty()) {
            return true;
        }
        LotteryService.Preview preview = lotteryService.preview(type.get());
        sender.sendMessage(configManager.prefixed("&e" + configManager.lottery(type.get()).displayName()
                + " &7period &f" + preview.periodId() + " &7preview"));
        sender.sendMessage(configManager.prefixed("&7Pool: &f" + Text.money(preview.pool()) + " &7/ Min: &f" + Text.money(preview.minPool())));
        sender.sendMessage(configManager.prefixed("&7Tickets: &f" + preview.tickets() + " &7Players: &f" + preview.players()));
        sender.sendMessage(configManager.prefixed("&7Status: " + (preview.drawable() ? "&aDrawable" : "&cPool too low")));
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        LotteryType type = LotteryType.from(args.length >= 2 ? args[1] : "daily").orElse(LotteryType.DAILY);
        Optional<PeriodState> period = storage.getPeriod(type);
        if (period.isEmpty() || period.get().periodId() <= 1) {
            sender.sendMessage(configManager.prefixed("&7No draw history."));
            return true;
        }
        long historyPeriod = period.get().periodId() - 1;
        List<Award> awards = storage.awards(type, historyPeriod);
        sender.sendMessage(configManager.prefixed("&e" + configManager.lottery(type).displayName()
                + " &7period &f" + historyPeriod + " &7result"));
        if (awards.isEmpty()) {
            sender.sendMessage(configManager.prefixed("&7No winner records. This period may have been canceled."));
            return true;
        }
        for (Award award : awards) {
            sender.sendMessage(configManager.prefixed("&7" + award.tier().displayName() + ": &f" + award.playerName()
                    + " &7Amount: &f" + Text.money(award.amount())));
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean ledger(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.ledger")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("/lottery ledger <player>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(configManager.message("player-not-found"));
            return true;
        }
        List<LedgerEntry> entries = storage.playerLedger(target.getUniqueId(), 10);
        sender.sendMessage(configManager.prefixed("&e" + (target.getName() == null ? args[1] : target.getName())
                + " &7recent ledger"));
        if (entries.isEmpty()) {
            sender.sendMessage(configManager.prefixed("&7No ledger entries."));
            return true;
        }
        for (LedgerEntry entry : entries) {
            sender.sendMessage(configManager.prefixed("&7" + entry.type().key() + " #" + entry.periodId() + " &f" + entry.action()
                    + " &7Amount: &f" + Text.money(entry.amount()) + " &8" + entry.note()));
        }
        return true;
    }

    private boolean period(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.period")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        Optional<LotteryType> type = parseType(sender, args, 1);
        if (type.isEmpty()) {
            return true;
        }
        LotteryService.Preview preview = lotteryService.preview(type.get());
        sender.sendMessage(configManager.prefixed("&e" + configManager.lottery(type.get()).displayName()
                + " &7current period: &f" + preview.periodId()));
        sender.sendMessage(configManager.prefixed("&7Next draw: &f" + Text.countdown(preview.nextDrawAt())));
        sender.sendMessage(configManager.prefixed("&7Pool: &f" + Text.money(preview.pool())));
        sender.sendMessage(configManager.prefixed("&7Tickets: &f" + preview.tickets() + " &7Players: &f" + preview.players()));
        sender.sendMessage(configManager.prefixed("&7Last first winner: &f" + lotteryService.lastFirstWinner(type.get())));
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean mailtest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.mailtest")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("/lottery mailtest <player>");
            return true;
        }
        if (!configManager.mail().enabled()) {
            sender.sendMessage(configManager.message("mailtest-disabled"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(configManager.message("player-not-found"));
            return true;
        }
        String playerName = target.getName() == null ? args[1] : target.getName();
        if (!mailService.sendTestMail(target.getUniqueId(), playerName)) {
            sender.sendMessage(configManager.message("mailtest-no-email").replace("%player%", playerName));
            return true;
        }
        sender.sendMessage(configManager.message("mailtest-sent").replace("%player%", playerName));
        return true;
    }

    private boolean help(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin.help")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        for (String line : configManager.adminHelp()) {
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean pool(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.pool")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("add")) {
            sender.sendMessage("/lottery pool add <daily|weekly> <amount>");
            return true;
        }
        Optional<LotteryType> type = LotteryType.from(args[2]);
        if (type.isEmpty()) {
            sender.sendMessage(configManager.message("invalid-type"));
            return true;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("/lottery pool add <daily|weekly> <amount>");
            return true;
        }
        if (amount <= 0) {
            sender.sendMessage("/lottery pool add <daily|weekly> <amount>");
            return true;
        }
        if (!lotteryService.addPool(type.get(), amount, sender.getName())) {
            sender.sendMessage(configManager.message("pool-add-failed"));
            return true;
        }
        sender.sendMessage(configManager.message("pool-add-success")
                .replace("%type%", configManager.lottery(type.get()).displayName())
                .replace("%amount%", Text.money(amount)));
        return true;
    }

    private boolean reset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin.pool")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (!configManager.canReset(player.getUniqueId())) {
            sender.sendMessage(configManager.message("reset-denied"));
            return true;
        }
        Optional<LotteryType> type = parseType(sender, args, 1);
        if (type.isEmpty()) {
            return true;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            sender.sendMessage(configManager.message("reset-confirm")
                    .replace("%type%", configManager.lottery(type.get()).displayName())
                    .replace("%command%", "/lottery reset " + type.get().key() + " confirm"));
            return true;
        }
        LotteryService.ResetResult result = lotteryService.resetPool(type.get(), player.getUniqueId(), player.getName());
        sender.sendMessage(configManager.message("reset-success")
                .replace("%type%", configManager.lottery(type.get()).displayName())
                .replace("%amount%", Text.money(result.clearedPool())));
        return true;
    }

    private Optional<LotteryType> parseType(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(configManager.message("invalid-type"));
            return Optional.empty();
        }
        Optional<LotteryType> type = LotteryType.from(args[index]);
        if (type.isEmpty()) {
            sender.sendMessage(configManager.message("invalid-type"));
        }
        return type;
    }

    private boolean email(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("/lottery email <info|bind|unbind|edit>");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("info")) {
            return emailInfo(sender, args, 2);
        }
        if (sub.equals("bind")) {
            return emailBind(sender, args, 2);
        }
        if (sub.equals("unbind")) {
            return emailUnbind(sender);
        }
        if (sub.equals("edit")) {
            return emailEdit(sender, args, 2);
        }
        sender.sendMessage("/lottery email <info|bind|unbind|edit>");
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean emailInfo(CommandSender sender, String[] args, int playerIndex) {
        if (args.length > playerIndex) {
            if (!sender.hasPermission("lottery.admin.email")) {
                sender.sendMessage(configManager.message("no-permission"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[playerIndex]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage(configManager.message("player-not-found"));
                return true;
            }
            String playerName = target.getName() == null ? args[playerIndex] : target.getName();
            storage.getEmail(target.getUniqueId())
                    .ifPresentOrElse(
                            email -> sender.sendMessage(configManager.message("email-info-target")
                                    .replace("%player%", playerName)
                                    .replace("%email%", mailService.mask(email))),
                            () -> sender.sendMessage(configManager.message("email-info-target-empty").replace("%player%", playerName))
                    );
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (!player.hasPermission("lottery.email.info")) {
            player.sendMessage(configManager.message("no-permission"));
            return true;
        }
        storage.getEmail(player.getUniqueId())
                .ifPresentOrElse(
                        email -> player.sendMessage(configManager.message("email-info").replace("%email%", mailService.mask(email))),
                        () -> player.sendMessage(configManager.message("email-info-empty"))
                );
        return true;
    }

    private boolean emailBind(CommandSender sender, String[] args, int valueIndex) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (!player.hasPermission("lottery.email.bind")) {
            player.sendMessage(configManager.message("no-permission"));
            return true;
        }
        if (args.length <= valueIndex) {
            player.sendMessage(configManager.message("email-invalid"));
            return true;
        }
        String email = mailService.normalizeEmailInput(args[valueIndex]);
        if (!mailService.isValidEmail(email)) {
            player.sendMessage(configManager.message("email-invalid"));
            return true;
        }
        storage.setEmail(player.getUniqueId(), player.getName(), email);
        player.sendMessage(configManager.message("email-bound").replace("%email%", mailService.mask(email)));
        return true;
    }

    private boolean emailUnbind(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(configManager.message("player-only"));
            return true;
        }
        if (!player.hasPermission("lottery.email.unbind")) {
            player.sendMessage(configManager.message("no-permission"));
            return true;
        }
        storage.clearEmail(player.getUniqueId());
        player.sendMessage(configManager.message("email-unbound"));
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean emailEdit(CommandSender sender, String[] args, int playerIndex) {
        if (!sender.hasPermission("lottery.admin.email")) {
            sender.sendMessage(configManager.message("no-permission"));
            return true;
        }
        int valueIndex = playerIndex + 1;
        if (args.length <= valueIndex) {
            sender.sendMessage(playerIndex == 1 ? "/lottery edit <player> <email|qq|clear>" : "/lottery email edit <player> <email|qq|clear>");
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[playerIndex]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(configManager.message("player-not-found"));
            return true;
        }
        if (args[valueIndex].equalsIgnoreCase("clear")) {
            storage.clearEmail(target.getUniqueId());
            sender.sendMessage(configManager.message("email-cleared").replace("%player%", target.getName() == null ? args[playerIndex] : target.getName()));
            return true;
        }
        String email = mailService.normalizeEmailInput(args[valueIndex]);
        if (!mailService.isValidEmail(email)) {
            sender.sendMessage(configManager.message("email-invalid"));
            return true;
        }
        String playerName = target.getName() == null ? args[playerIndex] : target.getName();
        storage.setEmail(target.getUniqueId(), playerName, email);
        sender.sendMessage(configManager.message("email-edited")
                .replace("%player%", playerName)
                .replace("%email%", mailService.mask(email)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "reload", "info", "bind", "unbind", "edit", "email", "draw",
                    "preview", "history", "ledger", "period", "mailtest", "help", "pool", "reset"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("email")) {
            return filter(List.of("info", "bind", "unbind", "edit"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            return playerNames(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("email") && args[1].equalsIgnoreCase("info")) {
            return playerNames(args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("draw")) {
            return filter(List.of("daily", "weekly"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("preview")
                || args[0].equalsIgnoreCase("period")
                || args[0].equalsIgnoreCase("history"))) {
            return filter(List.of("daily", "weekly"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("ledger")
                || args[0].equalsIgnoreCase("mailtest"))) {
            return playerNames(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pool")) {
            return filter(List.of("add"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pool") && args[1].equalsIgnoreCase("add")) {
            return filter(List.of("daily", "weekly"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            return filter(List.of("daily", "weekly"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            return filter(List.of("confirm"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            return playerNames(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("email") && args[1].equalsIgnoreCase("edit")) {
            return playerNames(args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("email") && args[1].equalsIgnoreCase("edit")) {
            return filter(List.of("clear"), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            return playerNames(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("edit")) {
            return filter(List.of("clear"), args[2]);
        }
        return List.of();
    }

    private List<String> playerNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return filter(names, prefix);
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }
}
