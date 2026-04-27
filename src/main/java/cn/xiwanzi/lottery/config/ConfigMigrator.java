package cn.xiwanzi.lottery.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class ConfigMigrator {
    private ConfigMigrator() {
    }

    public static boolean migrate(JavaPlugin plugin) {
        Path configPath = plugin.getDataFolder().toPath().resolve("config.yml");
        if (!Files.exists(configPath)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            StringBuilder additions = new StringBuilder();
            boolean editedLines = false;
            if (!hasTopLevel(lines, "config-version")) {
                additions.append(System.lineSeparator())
                        .append("# Added by lottery 1.1.x config migration.").append(System.lineSeparator())
                        .append("# This version marker is appended to avoid rewriting existing production config.").append(System.lineSeparator())
                        .append("config-version: 4").append(System.lineSeparator());
            } else {
                editedLines = bumpConfigVersion(lines);
            }
            if (!hasTopLevel(lines, "holiday")) {
                additions.append(System.lineSeparator()).append(HOLIDAY_CONFIG);
            } else if (!hasPath(lines, "holiday", "refund")) {
                editedLines = insertHolidayRefund(lines) || editedLines;
            }
            if (!hasPath(lines, "menu", "holiday") && !hasTopLevel(lines, "holiday-menu")) {
                additions.append(System.lineSeparator()).append(HOLIDAY_MENU_CONFIG);
            }
            if (additions.length() == 0 && !editedLines) {
                return false;
            }
            if (editedLines) {
                Files.write(configPath, lines, StandardCharsets.UTF_8);
            }
            Files.writeString(configPath, additions.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            plugin.getLogger().info("Config migration applied lottery 1.1.x settings without replacing existing production config.");
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to migrate config.yml: " + ex.getMessage());
            return false;
        }
    }

    private static boolean hasTopLevel(List<String> lines, String key) {
        String prefix = key + ":";
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bumpConfigVersion(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("config-version:")) {
                if (lines.get(i).equals("config-version: 4")) {
                    return false;
                }
                lines.set(i, "config-version: 4");
                return true;
            }
        }
        return false;
    }

    private static boolean hasPath(List<String> lines, String parent, String child) {
        boolean inParent = false;
        String parentPrefix = parent + ":";
        String childPrefix = "  " + child + ":";
        for (String line : lines) {
            if (line.startsWith(parentPrefix)) {
                inParent = true;
                continue;
            }
            if (inParent && !line.isBlank() && !line.startsWith(" ")) {
                return false;
            }
            if (inParent && line.startsWith(childPrefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean insertHolidayRefund(List<String> lines) {
        boolean inHoliday = false;
        int insertAt = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("holiday:")) {
                inHoliday = true;
                insertAt = i + 1;
                continue;
            }
            if (inHoliday && !line.isBlank() && !line.startsWith(" ")) {
                break;
            }
            if (inHoliday && line.startsWith("  schedules:")) {
                insertAt = i;
                break;
            }
        }
        if (insertAt < 0) {
            return false;
        }
        lines.add(insertAt, "");
        lines.add(insertAt + 1, "  refund:");
        lines.add(insertAt + 2, "    # true = players can refund current holiday bets before the draw lock time.");
        lines.add(insertAt + 3, "    enabled: true");
        lines.add(insertAt + 4, "    # Player self-refund is locked this many minutes before draw. Admin refund ignores this.");
        lines.add(insertAt + 5, "    lock-before-minutes: 60");
        return true;
    }

    private static final String HOLIDAY_CONFIG = """
# Added by lottery 1.1.x. The event is disabled by default and does not modify daily/weekly data.
holiday:
  display-name: "&6节日公益活动"
  enabled: false
  max-bets-per-player: 5
  bet-amounts:
    - 100
    - 300
    - 600
  min-total-pool: 1500
  reward-pool-percent: 90
  house-pool-percent: 10

  refund:
    enabled: true
    lock-before-minutes: 60

  schedules:
    fixed-daily:
      enabled: true
      time: "22:00"
    fixed-weekly:
      enabled: false
      day: "SUNDAY"
      time: "22:00"
    interval:
      enabled: false
      minutes: 10

  outcomes:
    redstone:
      display-name: "&c红石"
      material: "REDSTONE_BLOCK"
      chance-percent: 45
    obsidian:
      display-name: "&8黑曜石"
      material: "OBSIDIAN"
      chance-percent: 45
    gold:
      display-name: "&6金块"
      material: "GOLD_BLOCK"
      chance-percent: 10
""";

    private static final String HOLIDAY_MENU_CONFIG = """
# Compatibility menu entry for existing configs that do not have menu.holiday.
holiday-menu:
  slot: 13
  material: "EMERALD"
  name: "&6&l节日公益活动"
""";
}
