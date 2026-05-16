package com.dreambound;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class DreamboundConfig {

    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("dreambound.properties");

    public boolean enableBedSleepSnapshots = true;
    public boolean enableRespawnAnchorLogic = true;
    public boolean enableUniversalGravesCompat = true;
    public boolean restoreExperience = true;
    public int experienceLossPercent = 0;
    public boolean clearSnapshotOnRespawn = false;
    public boolean notifySnapshotSaved = true;
    public boolean notifyRespawnRestore = true;
    public boolean debugUniversalGravesCompat = false;

    public static DreamboundConfig load() {
        DreamboundConfig cfg = new DreamboundConfig();

        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                cfg.apply(properties);
                cfg.save();
                return cfg;
            } catch (Exception e) {
                DreamboundMod.LOGGER.warn("Dreambound: failed to load properties config, using defaults", e);
            }
        }

        cfg.save();
        return cfg;
    }

    private void apply(Properties properties) {
        enableBedSleepSnapshots = getBoolean(properties, "enableBedSleepSnapshots", enableBedSleepSnapshots);
        enableRespawnAnchorLogic = getBoolean(properties, "enableRespawnAnchorLogic", enableRespawnAnchorLogic);
        enableUniversalGravesCompat = getBoolean(properties, "enableUniversalGravesCompat", enableUniversalGravesCompat);
        restoreExperience = getBoolean(properties, "restoreExperience", restoreExperience);
        experienceLossPercent = getInt(properties, "experienceLossPercent", experienceLossPercent, 0, 100);
        clearSnapshotOnRespawn = getBoolean(properties, "clearSnapshotOnRespawn", clearSnapshotOnRespawn);
        notifySnapshotSaved = getBoolean(properties, "notifySnapshotSaved", notifySnapshotSaved);
        notifyRespawnRestore = getBoolean(properties, "notifyRespawnRestore", notifyRespawnRestore);
        debugUniversalGravesCompat = getBoolean(properties, "debugUniversalGravesCompat", debugUniversalGravesCompat);
    }

    private static boolean getBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int getInt(Properties properties, String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public int applyExperienceLoss(int xp) {
        return xp * (100 - experienceLossPercent) / 100;
    }

    public void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, toPropertiesString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DreamboundMod.LOGGER.warn("Dreambound: failed to save config", e);
        }
    }

    private String toPropertiesString() {
        return """
            # Dreambound configuration
            # Changes take effect after /dreambound reload or server restart.

            # Save a dream snapshot after a successful bed sleep.
            enableBedSleepSnapshots=%s

            # Save a dream snapshot when setting a new charged respawn anchor spawn in the Nether.
            enableRespawnAnchorLogic=%s

            # Integrate with Universal Graves when it is installed.
            # Restored dream items are kept out of the grave while forfeited items still go into it.
            enableUniversalGravesCompat=%s

            # Restore XP up to the amount the player had in the dream snapshot.
            # Extra XP is dropped at death.
            restoreExperience=%s

            # Percentage of otherwise-restored dream XP lost on death.
            # 0 keeps all restorable XP. 100 keeps none.
            experienceLossPercent=%s

            # Clear the stored dream snapshot after it is used on respawn.
            clearSnapshotOnRespawn=%s

            # Send a styled message after a bed or respawn-anchor snapshot is saved.
            notifySnapshotSaved=%s

            # Send a styled message after dream items are restored on respawn.
            notifyRespawnRestore=%s

            # Log detailed Universal Graves compatibility decisions.
            # Useful when debugging grave compass or item filtering behavior.
            debugUniversalGravesCompat=%s
            """.formatted(
                enableBedSleepSnapshots,
                enableRespawnAnchorLogic,
                enableUniversalGravesCompat,
                restoreExperience,
                experienceLossPercent,
                clearSnapshotOnRespawn,
                notifySnapshotSaved,
                notifyRespawnRestore,
                debugUniversalGravesCompat
            );
    }
}
