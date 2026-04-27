package cn.xiwanzi.lottery.config;

import org.bukkit.Material;

import java.util.List;

public record MenuSettings(String title, Material fillerMaterial, String fillerName, int dailySlot, Material dailyMaterial,
                           String dailyName, int weeklySlot, Material weeklyMaterial, String weeklyName,
                           int emailSlot, Material emailUnboundMaterial, String emailUnboundName,
                           Material emailBoundMaterial, String emailBoundName, List<String> emailUnboundLore,
                           List<String> emailBoundLore, List<String> loreTemplate) {
}
