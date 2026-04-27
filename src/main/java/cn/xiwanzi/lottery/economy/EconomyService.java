package cn.xiwanzi.lottery.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class EconomyService {
    private final JavaPlugin plugin;
    private Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> provider = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return false;
        }
        economy = provider.getProvider();
        plugin.getLogger().info("Economy provider hooked: " + economy.getName());
        return true;
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    @SuppressWarnings("deprecation")
    public boolean hasAccount(String accountName, double amount) {
        return amount <= 0 || (economy != null && economy.has(accountName, amount));
    }

    @SuppressWarnings("deprecation")
    public boolean accountExists(String accountName) {
        return economy != null && economy.hasAccount(accountName);
    }

    @SuppressWarnings("deprecation")
    public boolean createAccount(String accountName) {
        return economy != null && economy.createPlayerAccount(accountName);
    }

    public boolean ensureAccount(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return false;
        }
        return accountExists(accountName) || createAccount(accountName) || accountExists(accountName);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (economy == null) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (economy == null) {
            return false;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    @SuppressWarnings("deprecation")
    public boolean depositAccount(String accountName, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (economy == null) {
            return false;
        }
        EconomyResponse response = economy.depositPlayer(accountName, amount);
        return response.transactionSuccess();
    }

    @SuppressWarnings("deprecation")
    public boolean withdrawAccount(String accountName, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (economy == null) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(accountName, amount);
        return response.transactionSuccess();
    }

    public boolean transferPlayerToAccount(OfflinePlayer player, String accountName, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (!ensureAccount(accountName)) {
            return false;
        }
        if (!withdraw(player, amount)) {
            return false;
        }
        if (depositAccount(accountName, amount)) {
            return true;
        }
        deposit(player, amount);
        return false;
    }

    public boolean transferAccountToPlayer(String accountName, OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (!ensureAccount(accountName)) {
            return false;
        }
        if (!hasAccount(accountName, amount)) {
            return false;
        }
        if (!deposit(player, amount)) {
            return false;
        }
        if (withdrawAccount(accountName, amount)) {
            return true;
        }
        withdraw(player, amount);
        return false;
    }

    public String format(double amount) {
        return economy == null ? Double.toString(amount) : economy.format(amount);
    }
}
