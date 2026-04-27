package cn.xiwanzi.lottery;

import cn.xiwanzi.lottery.command.LotteryCommand;
import cn.xiwanzi.lottery.config.ConfigManager;
import cn.xiwanzi.lottery.economy.EconomyService;
import cn.xiwanzi.lottery.mail.MailService;
import cn.xiwanzi.lottery.menu.MenuManager;
import cn.xiwanzi.lottery.placeholder.LotteryExpansion;
import cn.xiwanzi.lottery.service.LotteryService;
import cn.xiwanzi.lottery.storage.StorageService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class LotteryPlugin extends JavaPlugin {
    private ConfigManager configManager;
    private StorageService storageService;
    private EconomyService economyService;
    private MailService mailService;
    private LotteryService lotteryService;
    private MenuManager menuManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        configManager.reload();

        storageService = new StorageService(this);
        storageService.init();

        economyService = new EconomyService(this);
        if (!economyService.setup()) {
            getLogger().severe("Vault economy provider was not found. Disabling lottery.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!economyService.ensureAccount(configManager.systemAccount())) {
            getLogger().warning("Lottery account '" + configManager.systemAccount() + "' could not be created or verified.");
        }

        mailService = new MailService(this, configManager, storageService);
        lotteryService = new LotteryService(this, configManager, storageService, economyService, mailService);
        lotteryService.start();

        menuManager = new MenuManager(this, configManager, storageService, mailService, lotteryService);
        menuManager.register();

        LotteryCommand command = new LotteryCommand(this, configManager, storageService, lotteryService, menuManager, mailService);
        getCommand("lottery").setExecutor(command);
        getCommand("lottery").setTabCompleter(command);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new LotteryExpansion(this, lotteryService).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }
    }

    @Override
    public void onDisable() {
        if (menuManager != null) {
            menuManager.shutdown();
        }
        if (lotteryService != null) {
            lotteryService.shutdown();
        }
        if (storageService != null) {
            storageService.close();
        }
    }

    public void reloadLottery() {
        reloadConfig();
        configManager.reload();
        mailService.reload();
        economyService.ensureAccount(configManager.systemAccount());
        lotteryService.reloadSchedules();
        if (menuManager != null) {
            menuManager.refreshAllOpenMenus();
        }
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public StorageService storage() {
        return storageService;
    }

    public EconomyService economy() {
        return economyService;
    }
}
