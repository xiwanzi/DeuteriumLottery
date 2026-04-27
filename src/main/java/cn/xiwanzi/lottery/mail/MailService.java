package cn.xiwanzi.lottery.mail;

import cn.xiwanzi.lottery.config.ConfigManager;
import cn.xiwanzi.lottery.storage.StorageService;
import cn.xiwanzi.lottery.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MailService {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final StorageService storage;

    public MailService(JavaPlugin plugin, ConfigManager configManager, StorageService storage) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.storage = storage;
    }

    public void reload() {
    }

    public boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL_PATTERN.matcher(email).matches();
    }

    public String normalizeEmailInput(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.matches("\\d+")) {
            return trimmed + "@qq.com";
        }
        return trimmed;
    }

    public String mask(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "****";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "****" + domain;
        }
        return local.substring(0, 2) + "****" + domain;
    }

    public void sendWinMail(UUID playerUuid, String playerName, String lotteryType, String prize, double amount) {
        ConfigManager.MailSettings settings = configManager.mail();
        if (!settings.enabled()) {
            return;
        }
        storage.getEmail(playerUuid).ifPresent(email -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> values = baseValues(playerName, lotteryType, amount);
                values.put("%prize%", Text.plain(prize));
                send(email, replace(settings.subject(), values), replace(String.join("\n", settings.body()), values));
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to send lottery email to " + playerName + " (" + mask(email) + "): " + ex.getMessage());
            }
        }));
    }

    public void sendHolidayWinMail(UUID playerUuid, String playerName, String lotteryType, long periodId, String outcome, double amount) {
        ConfigManager.MailSettings settings = configManager.mail();
        if (!settings.enabled()) {
            return;
        }
        storage.getEmail(playerUuid).ifPresent(email -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> values = baseValues(playerName, lotteryType, amount);
                values.put("%period%", Long.toString(periodId));
                values.put("%outcome%", Text.plain(outcome));
                values.put("%prize%", Text.plain(outcome));
                send(email, replace(settings.holidaySubject(), values), replace(String.join("\n", settings.holidayBody()), values));
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to send holiday lottery email to " + playerName + " (" + mask(email) + "): " + ex.getMessage());
            }
        }));
    }

    public void sendRefundMail(UUID playerUuid, String playerName, String lotteryType, long periodId,
                               double amount, int count, String operatorName) {
        ConfigManager.MailSettings settings = configManager.mail();
        if (!settings.enabled()) {
            return;
        }
        storage.getEmail(playerUuid).ifPresent(email -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> values = baseValues(playerName, lotteryType, amount);
                values.put("%period%", Long.toString(periodId));
                values.put("%count%", Integer.toString(count));
                values.put("%operator%", operatorName == null || operatorName.isBlank() ? "管理员" : operatorName);
                send(email, replace(settings.refundSubject(), values), replace(String.join("\n", settings.refundBody()), values));
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to send lottery refund email to " + playerName + " (" + mask(email) + "): " + ex.getMessage());
            }
        }));
    }

    public boolean sendTestMail(UUID playerUuid, String playerName) {
        if (!configManager.mail().enabled()) {
            return false;
        }
        Optional<String> email = storage.getEmail(playerUuid);
        if (email.isEmpty()) {
            return false;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, String> values = baseValues(playerName, "建筑彩票", 0);
                values.put("%prize%", "测试邮件");
                send(email.get(), replace(configManager.mail().subject(), values), replace(String.join("\n", configManager.mail().body()), values));
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to send test lottery email to " + playerName + " (" + mask(email.get()) + "): " + ex.getMessage());
            }
        });
        return true;
    }

    private void send(String email, String subject, String body) throws Exception {
        ConfigManager.MailSettings settings = configManager.mail();
        Properties properties = new Properties();
        properties.put("mail.smtp.host", settings.host());
        properties.put("mail.smtp.port", Integer.toString(settings.port()));
        properties.put("mail.smtp.auth", Boolean.toString(!settings.username().isBlank()));
        properties.put("mail.smtp.ssl.enable", Boolean.toString(settings.ssl()));
        properties.put("mail.smtp.starttls.enable", Boolean.toString(settings.starttls()));

        Session session = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(settings.username(), settings.password());
            }
        });

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(settings.from(), settings.senderName(), StandardCharsets.UTF_8.name()));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(email));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        Transport.send(message);
    }

    private Map<String, String> baseValues(String playerName, String lotteryType, double amount) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("%player%", playerName);
        values.put("%lottery_type%", Text.plain(lotteryType));
        values.put("%amount%", Text.money(amount));
        values.put("%time%", LocalDateTime.now().format(TIME_FORMAT));
        return values;
    }

    private String replace(String input, Map<String, String> values) {
        String result = input == null ? "" : input;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
