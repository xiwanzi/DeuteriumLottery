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
            if (!hasTopLevel(lines, "config-version")) {
                additions.append(System.lineSeparator())
                        .append("# Added by lottery 1.1.0 config migration.").append(System.lineSeparator())
                        .append("# This version marker is appended to avoid rewriting existing production config.").append(System.lineSeparator())
                        .append("config-version: 2").append(System.lineSeparator());
            }
            if (!hasTopLevel(lines, "holiday")) {
                additions.append(System.lineSeparator()).append(HOLIDAY_CONFIG);
            }
            if (!hasPath(lines, "menu", "holiday") && !hasTopLevel(lines, "holiday-menu")) {
                additions.append(System.lineSeparator()).append(HOLIDAY_MENU_CONFIG);
            }
            if (additions.length() == 0) {
                return false;
            }
            Files.writeString(configPath, additions.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            plugin.getLogger().info("Config migration appended lottery 1.1.0 settings without rewriting existing config.");
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

    private static final String HOLIDAY_CONFIG = """
# 节日公益活动。该玩法更偏活动竞争，默认单注更高、限购更多、一等奖占比更大。
holiday:
  display-name: "&6节日公益活动"
  price: 200
  max-purchases-per-player: 5
  min-total-pool: 1500
  reward-pool-percent: 90
  house-pool-percent: 10

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

  rewards:
    first:
      winners: 1
      pool-percent: 70
    second:
      winners: 1
      pool-percent: 20
    third:
      winners: 3
      pool-percent: 10
""";

    private static final String HOLIDAY_MENU_CONFIG = """
# 旧配置增量升级专用：如果原 menu 段落中没有 holiday，本段会作为节日彩票菜单入口配置。
holiday-menu:
  slot: 13
  material: "EMERALD"
  name: "&6&l节日公益活动"
""";
}
