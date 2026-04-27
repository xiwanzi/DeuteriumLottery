package cn.xiwanzi.lottery.menu;

import cn.xiwanzi.lottery.config.ConfigManager;
import cn.xiwanzi.lottery.config.HolidaySettings;
import cn.xiwanzi.lottery.config.LotterySettings;
import cn.xiwanzi.lottery.config.MenuSettings;
import cn.xiwanzi.lottery.mail.MailService;
import cn.xiwanzi.lottery.model.HolidayOutcome;
import cn.xiwanzi.lottery.model.LotteryType;
import cn.xiwanzi.lottery.service.LotteryService;
import cn.xiwanzi.lottery.storage.StorageService;
import cn.xiwanzi.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MenuManager implements Listener {
    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final StorageService storage;
    private final MailService mailService;
    private final LotteryService lotteryService;
    private final Map<String, Long> pendingConfirmations = new HashMap<>();
    private BukkitTask refreshTask;

    public MenuManager(JavaPlugin plugin, ConfigManager configManager, StorageService storage, MailService mailService,
                       LotteryService lotteryService) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.storage = storage;
        this.mailService = mailService;
        this.lotteryService = lotteryService;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAllOpenMenus,
                configManager.menuRefreshTicks(), configManager.menuRefreshTicks());
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (refreshTask != null) {
            refreshTask.cancel();
        }
    }

    public void open(Player player) {
        LotteryMenuHolder holder = new LotteryMenuHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, configManager.menu().title());
        holder.inventory(inventory);
        fill(inventory);
        refresh(inventory, player);
        player.openInventory(inventory);
    }

    public void refreshAllOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory inventory = player.getOpenInventory().getTopInventory();
            if (inventory.getHolder() instanceof LotteryMenuHolder) {
                refresh(inventory, player);
            } else if (inventory.getHolder() instanceof HolidayMenuHolder holder) {
                refreshHoliday(inventory, holder, player);
            }
        }
    }

    private void fill(Inventory inventory) {
        MenuSettings menu = configManager.menu();
        ItemStack filler = namedItem(menu.fillerMaterial(), menu.fillerName(), List.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    private void refresh(Inventory inventory, Player player) {
        MenuSettings menu = configManager.menu();
        inventory.setItem(menu.dailySlot(), lotteryItem(player, LotteryType.DAILY));
        inventory.setItem(menu.holidaySlot(), lotteryItem(player, LotteryType.HOLIDAY));
        inventory.setItem(menu.weeklySlot(), lotteryItem(player, LotteryType.WEEKLY));
        inventory.setItem(menu.emailSlot(), emailItem(player));
    }

    private ItemStack lotteryItem(Player player, LotteryType type) {
        if (type == LotteryType.HOLIDAY) {
            return holidayMainItem(player);
        }
        MenuSettings menu = configManager.menu();
        LotterySettings settings = configManager.lottery(type);
        Material material = switch (type) {
            case DAILY -> menu.dailyMaterial();
            case WEEKLY -> menu.weeklyMaterial();
            case HOLIDAY -> menu.holidayMaterial();
        };
        String name = switch (type) {
            case DAILY -> menu.dailyName();
            case WEEKLY -> menu.weeklyName();
            case HOLIDAY -> menu.holidayName();
        };
        List<String> lore = new ArrayList<>();
        int current = lotteryService.currentPurchases(player, type);
        String lastFirst = lotteryService.lastFirstWinner(type);
        for (String line : menu.loreTemplate()) {
            lore.add(line
                    .replace("%type%", settings.displayName())
                    .replace("%countdown%", Text.countdown(lotteryService.nextDrawAt(type)))
                    .replace("%price%", Text.money(settings.price()))
                    .replace("%pool%", Text.money(lotteryService.currentPool(type)))
                    .replace("%current%", Integer.toString(current))
                    .replace("%max%", Integer.toString(settings.maxPurchasesPerPlayer()))
                    .replace("%last_first_winner%", lastFirst == null ? "" : lastFirst));
        }
        return namedItem(material, name, Text.color(lore));
    }

    private ItemStack holidayMainItem(Player player) {
        MenuSettings menu = configManager.menu();
        HolidaySettings settings = configManager.holiday();
        List<String> amounts = settings.betAmounts().stream().map(Text::money).toList();
        List<String> lore = new ArrayList<>();
        lore.add("&8&m------------------------");
        lore.add("&7类型: &f" + settings.displayName());
        lore.add("&7状态: " + (settings.enabled() ? "&a开放中" : "&c活动未开放"));
        lore.add("&7开奖倒计时: &e" + Text.countdown(lotteryService.nextDrawAt(LotteryType.HOLIDAY)));
        lore.add("&7当前奖池: &a" + Text.money(lotteryService.currentPool(LotteryType.HOLIDAY)));
        lore.add("&7你的投注: &f" + lotteryService.currentPurchases(player, LotteryType.HOLIDAY) + "/" + settings.maxBetsPerPlayer());
        lore.add("&7可选额度: &6" + String.join(" / ", amounts));
        lore.add("&8&m------------------------");
        lore.add(settings.enabled() ? "&e点击进入活动分池" : "&7活动开启后可参与");
        return namedItem(menu.holidayMaterial(), menu.holidayName(), Text.color(lore));
    }

    public void openHoliday(Player player) {
        HolidayMenuHolder holder = new HolidayMenuHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, configManager.holiday().displayName());
        holder.inventory(inventory);
        fill(inventory);
        refreshHoliday(inventory, holder, player);
        player.openInventory(inventory);
    }

    private void refreshHoliday(Inventory inventory, HolidayMenuHolder holder, Player player) {
        fill(inventory);
        holder.clearChoices();
        HolidaySettings settings = configManager.holiday();
        if (!settings.enabled()) {
            inventory.setItem(13, namedItem(Material.BARRIER, "&c活动未开放", Text.color(List.of(
                    "&7节日公益活动当前未开启。",
                    "&7请等待服务器公告。"
            ))));
            return;
        }
        int[] slots = {9, 10, 11, 12, 13, 14, 15, 16, 17};
        int index = 0;
        int current = lotteryService.currentPurchases(player, LotteryType.HOLIDAY);
        for (HolidayOutcome outcome : HolidayOutcome.values()) {
            HolidaySettings.OutcomeSettings outcomeSettings = settings.outcome(outcome);
            for (double amount : settings.betAmounts()) {
                if (index >= slots.length) {
                    return;
                }
                int slot = slots[index++];
                holder.choice(slot, outcome, amount);
                double outcomePool = lotteryService.holidayOutcomePool(outcome);
                double estimatedPool = lotteryService.currentPool(LotteryType.HOLIDAY);
                double estimatedReturn = (outcomePool + amount) <= 0 ? 0 : estimatedPool / (outcomePool + amount);
                List<String> lore = List.of(
                        "&8&m------------------------",
                        "&7选择: &f" + outcomeSettings.displayName(),
                        "&7投注额度: &6" + Text.money(amount),
                        "&7该分池当前: &a" + Text.money(outcomePool),
                        "&7总奖池: &a" + Text.money(estimatedPool),
                        "&7你的投注: &f" + current + "/" + settings.maxBetsPerPlayer(),
                        "&7命中时按同分池投注比例分配",
                        "&7估算回报倍率: &e" + Text.money(estimatedReturn) + "x",
                        "&8&m------------------------",
                        "&e点击参与本分池"
                );
                inventory.setItem(slot, namedItem(outcomeSettings.material(), outcomeSettings.displayName()
                        + " &7- &6" + Text.money(amount), Text.color(lore)));
            }
        }
    }

    private ItemStack emailItem(Player player) {
        MenuSettings menu = configManager.menu();
        return storage.getEmail(player.getUniqueId())
                .map(email -> namedItem(menu.emailBoundMaterial(), menu.emailBoundName(),
                        Text.color(replaceEmailLore(menu.emailBoundLore(), player, mailService.mask(email)))))
                .orElseGet(() -> namedItem(menu.emailUnboundMaterial(), menu.emailUnboundName(),
                        Text.color(replaceEmailLore(menu.emailUnboundLore(), player, ""))));
    }

    private List<String> replaceEmailLore(List<String> template, Player player, String email) {
        List<String> lore = new ArrayList<>();
        for (String line : template) {
            lore.add(line
                    .replace("%player%", player.getName())
                    .replace("%email%", email)
                    .replace("%bind_command%", "/lottery bind <email-or-qq>")
                    .replace("%info_command%", "/lottery info")
                    .replace("%unbind_command%", "/lottery unbind"));
        }
        return lore;
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof LotteryMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != inventory) {
            return;
        }
        LotteryType type = null;
        if (event.getSlot() == configManager.menu().dailySlot()) {
            type = LotteryType.DAILY;
        } else if (event.getSlot() == configManager.menu().weeklySlot()) {
            type = LotteryType.WEEKLY;
        } else if (event.getSlot() == configManager.menu().holidaySlot()) {
            openHoliday(player);
            return;
        }
        if (type == null) {
            return;
        }

        if (requiresConfirmation(player, type)) {
            player.sendMessage(configManager.message("purchase-confirm")
                    .replace("%type%", configManager.lottery(type).displayName()));
            refresh(inventory, player);
            return;
        }

        LotteryService.PurchaseResult result = lotteryService.buyTicket(player, type);
        if (result.success()) {
            player.sendMessage(configManager.message("purchase-success")
                    .replace("%type%", configManager.lottery(type).displayName())
                    .replace("%current%", Integer.toString(result.current()))
                    .replace("%max%", Integer.toString(result.max())));
        } else if (result.limit()) {
            player.sendMessage(configManager.message("purchase-limit"));
        } else if (result.noMoney()) {
            player.sendMessage(configManager.message("not-enough-money"));
        } else {
            player.sendMessage(configManager.message("economy-unavailable"));
        }
        refresh(inventory, player);
    }

    @EventHandler
    public void onHolidayClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof HolidayMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != inventory) {
            return;
        }
        HolidayMenuHolder.Choice choice = holder.choice(event.getSlot());
        if (choice == null) {
            return;
        }
        if (requiresConfirmation(player, LotteryType.HOLIDAY)) {
            player.sendMessage(configManager.message("purchase-confirm")
                    .replace("%type%", configManager.holiday().displayName()));
            refreshHoliday(inventory, holder, player);
            return;
        }
        LotteryService.PurchaseResult result = lotteryService.buyHolidayBet(player, choice.outcome(), choice.amount());
        if (result.success()) {
            player.sendMessage(configManager.message("holiday-bet-success")
                    .replace("%outcome%", configManager.holiday().outcome(choice.outcome()).displayName())
                    .replace("%amount%", Text.money(choice.amount()))
                    .replace("%current%", Integer.toString(result.current()))
                    .replace("%max%", Integer.toString(result.max())));
        } else if (result.disabled()) {
            player.sendMessage(configManager.message("holiday-disabled"));
        } else if (result.limit()) {
            player.sendMessage(configManager.message("purchase-limit"));
        } else if (result.noMoney()) {
            player.sendMessage(configManager.message("not-enough-money"));
        } else {
            player.sendMessage(configManager.message("economy-unavailable"));
        }
        refreshHoliday(inventory, holder, player);
    }

    private boolean requiresConfirmation(Player player, LotteryType type) {
        if (!configManager.purchaseConfirm()) {
            return false;
        }
        String key = confirmationKey(player.getUniqueId(), type);
        long now = System.currentTimeMillis();
        long expiresAt = pendingConfirmations.getOrDefault(key, 0L);
        if (expiresAt >= now) {
            pendingConfirmations.remove(key);
            return false;
        }
        pendingConfirmations.put(key, now + configManager.purchaseConfirmSeconds() * 1000L);
        return true;
    }

    private String confirmationKey(UUID uuid, LotteryType type) {
        return uuid + ":" + type.key();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof LotteryMenuHolder
                || event.getView().getTopInventory().getHolder() instanceof HolidayMenuHolder) {
            event.setCancelled(true);
        }
    }
}
