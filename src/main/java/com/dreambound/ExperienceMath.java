package com.dreambound;

import net.minecraft.world.entity.player.Player;

public final class ExperienceMath {

    private ExperienceMath() {
    }

    public static int currentTotal(Player player) {
        int level = Math.max(0, player.experienceLevel);
        int xpNeeded = player.getXpNeededForNextLevel();
        int progressXp = Math.round(player.experienceProgress * xpNeeded);
        progressXp = Math.clamp(progressXp, 0, xpNeeded - 1);
        return totalToReachLevel(level) + Math.max(0, progressXp);
    }

    private static int totalToReachLevel(int level) {
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (int) (2.5D * level * level - 40.5D * level + 360.0D);
        }
        return (int) (4.5D * level * level - 162.5D * level + 2220.0D);
    }
}
