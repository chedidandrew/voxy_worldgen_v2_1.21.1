package com.ethan.voxyworldgenv2.client;

import com.ethan.voxyworldgenv2.core.ChunkGenerationManager;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class DebugRenderer {
    
    private DebugRenderer() {}
    
    public static void render(GuiGraphics graphics, DeltaTracker tickDelta) {
        if (!com.ethan.voxyworldgenv2.core.Config.DATA.showF3MenuStats) return;
        
        Minecraft mc = Minecraft.getInstance();
        
        if (!mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        
        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int lineHeight = font.lineHeight + 2;
        
        ChunkGenerationManager manager = ChunkGenerationManager.getInstance();
        GenerationStats stats = manager.getStats();
        
        double rate = stats.getChunksPerSecond();
        int remaining = manager.getRemainingInRadius();
        String eta = "--";
        if (rate > 0.1 && remaining > 0) {
            int seconds = (int) (remaining / rate);
            if (seconds < 60) {
                eta = seconds + "s";
            } else if (seconds < 3600) {
                eta = (seconds / 60) + "m " + (seconds % 60) + "s";
            } else {
                eta = (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            }
        } else if (remaining == 0) {
            eta = "done";
        }
        
        String status;
        if (manager.isThrottled()) {
            status = "§cthrottled (low tps)";
        } else if (remaining == 0) {
            status = "§adone";
        } else {
            status = "§arunning";
        }
        
        String[] lines = {
            "§6[voxy worldgen v2] " + status,
            "§7completed: §a" + formatNumber(stats.getCompleted()),
            "§7skipped: §f" + formatNumber(stats.getSkipped()),
            "§7remaining: §e" + formatNumber(remaining) + " §8(" + eta + ")",
            "§7active: §b" + manager.getActiveTaskCount(),
            "§7rate: §f" + String.format("%.1f", rate) + " c/s",
            "§7voxy: " + (VoxyIntegration.isVoxyAvailable() ? "§aenabled" : "§cdisabled")
        };
        
        int y = screenHeight - (lines.length * lineHeight) - 4;
        
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, font.width(line));
        }
        
        for (String line : lines) {
            int x = screenWidth - font.width(line) - 4;
            int bgX = screenWidth - maxWidth - 6;
            
            graphics.fill(bgX, y - 1, screenWidth - 2, y + font.lineHeight, 0x90505050);
            graphics.drawString(font, line, x, y, 0xFFFFFFFF, false);
            
            y += lineHeight;
        }
    }
    
    private static String formatNumber(long number) {
        if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }
}
