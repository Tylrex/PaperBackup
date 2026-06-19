package ua.vlad.backup;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class PaperBackup extends JavaPlugin {

    private static final String CONFIG_FILE_NAME = "google-drive-config.yml";
    private static final String LEGACY_CONFIG_FILE_NAME = "config.yml";

    private BackupManager backupManager;
    private BukkitTask scheduledTask;
    private File stateFile;
    private FileConfiguration stateConfig;
    private File customConfigFile;
    private FileConfiguration customConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();
        loadState();

        this.backupManager = new BackupManager(this);

        if (getCommand("backup") != null) {
            BackupCommand backupCommand = new BackupCommand(this);
            getCommand("backup").setExecutor(backupCommand);
            getCommand("backup").setTabCompleter(backupCommand);
        }

        scheduleBackupTask();

        getLogger().info("PaperBackup has been enabled!");
    }

    @Override
    public void onDisable() {
        cancelBackupTask();
        getLogger().info("PaperBackup has been disabled!");
    }

    public void reloadPlugin() {
        reloadConfig();
        migrateConfig();
        loadState();
        cancelBackupTask();
        this.backupManager.loadConfig();
        scheduleBackupTask();
    }

    private void scheduleBackupTask() {
        int intervalMinutes = getConfig().getInt("backup-interval-minutes", 60);
        if (intervalMinutes <= 0) {
            getLogger().info("Automatic backups are disabled (interval is <= 0).");
            return;
        }

        long intervalMillis = intervalMinutes * 60_000L;
        long now = System.currentTimeMillis();
        long nextRunAt = stateConfig.getLong("next-backup-at-millis", 0L);
        if (nextRunAt <= 0L) {
            long lastSuccessfulBackupAt = stateConfig.getLong("last-scheduled-backup-at-millis", 0L);
            nextRunAt = lastSuccessfulBackupAt > 0L ? lastSuccessfulBackupAt + intervalMillis : now + intervalMillis;
            setNextBackupAt(nextRunAt);
        }

        boolean catchUpMissedBackups = getConfig().getBoolean("catch-up-missed-backup-on-start", true);
        if (nextRunAt <= now && !catchUpMissedBackups) {
            nextRunAt = advancePastNow(nextRunAt, intervalMillis, now);
            setNextBackupAt(nextRunAt);
        }

        long startupDelayMillis = Math.max(0L, getConfig().getLong("startup-delay-seconds", 60L)) * 1000L;
        long delayMillis = Math.max(0L, nextRunAt - now);
        if (nextRunAt <= now && catchUpMissedBackups) {
            delayMillis = startupDelayMillis;
        }

        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        long scheduledFor = nextRunAt;
        scheduledTask = Bukkit.getScheduler().runTaskLater(this, () -> runScheduledBackup(scheduledFor, intervalMillis), delayTicks);

        getLogger().info("Scheduled automatic backups every " + intervalMinutes
                + " minutes. Next backup: " + formatConsoleTime(nextRunAt) + ".");
    }

    private void runScheduledBackup(long scheduledFor, long intervalMillis) {
        if (backupManager.isRunning()) {
            long now = System.currentTimeMillis();
            long nextRunAt = advancePastNow(scheduledFor + intervalMillis, intervalMillis, now);
            getLogger().info("Scheduled backup was due at " + formatConsoleTime(scheduledFor)
                    + ", but another backup is already running. Next backup: " + formatConsoleTime(nextRunAt) + ".");
            setNextBackupAt(nextRunAt);
            saveState();
            cancelBackupTask();
            scheduleBackupTask();
            return;
        }

        getLogger().info("Starting scheduled server backup. Scheduled time: " + formatConsoleTime(scheduledFor) + ".");
        backupManager.runBackup(false, success -> Bukkit.getScheduler().runTask(this, () -> {
            long now = System.currentTimeMillis();
            if (success) {
                stateConfig.set("last-scheduled-backup-at-millis", now);
                stateConfig.set("last-scheduled-backup-at", formatConsoleTime(now));
            }

            long nextRunAt = advancePastNow(scheduledFor + intervalMillis, intervalMillis, now);
            setNextBackupAt(nextRunAt);
            saveState();
            cancelBackupTask();
            scheduleBackupTask();
        }));
    }

    private long advancePastNow(long candidate, long intervalMillis, long now) {
        long next = candidate;
        while (next <= now) {
            next += intervalMillis;
        }
        return next;
    }

    private void setNextBackupAt(long nextRunAt) {
        stateConfig.set("next-backup-at-millis", nextRunAt);
        stateConfig.set("next-backup-at", formatConsoleTime(nextRunAt));
        saveState();
    }

    private void cancelBackupTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    @Override
    public FileConfiguration getConfig() {
        if (customConfig == null) {
            reloadConfig();
        }
        return customConfig;
    }

    @Override
    public void reloadConfig() {
        if (customConfigFile == null) {
            customConfigFile = new File(getDataFolder(), CONFIG_FILE_NAME);
        }
        customConfig = YamlConfiguration.loadConfiguration(customConfigFile);

        try (InputStream stream = getResource(CONFIG_FILE_NAME)) {
            if (stream != null) {
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                customConfig.setDefaults(defaults);
            }
        } catch (IOException exception) {
            getLogger().warning("Could not load bundled " + CONFIG_FILE_NAME + " defaults: " + exception.getMessage());
        }
    }

    @Override
    public void saveConfig() {
        if (customConfig == null || customConfigFile == null) {
            return;
        }

        try {
            customConfig.save(customConfigFile);
        } catch (IOException exception) {
            getLogger().warning("Could not save " + CONFIG_FILE_NAME + ": " + exception.getMessage());
        }
    }

    @Override
    public void saveDefaultConfig() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder for " + CONFIG_FILE_NAME + ".");
        }

        customConfigFile = new File(getDataFolder(), CONFIG_FILE_NAME);
        if (!customConfigFile.exists()) {
            File legacyConfigFile = new File(getDataFolder(), LEGACY_CONFIG_FILE_NAME);
            if (legacyConfigFile.exists()) {
                try {
                    Files.copy(legacyConfigFile.toPath(), customConfigFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    getLogger().info("Migrated " + LEGACY_CONFIG_FILE_NAME + " to " + CONFIG_FILE_NAME + ".");
                } catch (IOException exception) {
                    getLogger().warning("Could not migrate " + LEGACY_CONFIG_FILE_NAME + " to "
                            + CONFIG_FILE_NAME + ": " + exception.getMessage());
                }
            } else {
                saveResource(CONFIG_FILE_NAME, false);
            }
        }

        reloadConfig();
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public List<String> getStatusLines() {
        List<String> lines = new ArrayList<>();
        int intervalMinutes = getConfig().getInt("backup-interval-minutes", 60);
        lines.add("&6=== PaperBackup Status ===");
        lines.add("&eRunning: &f" + backupManager.isRunning());
        lines.add("&eAutomatic interval: &f" + (intervalMinutes <= 0 ? "disabled" : intervalMinutes + " minutes"));
        boolean googleDriveEnabled = getConfig().getBoolean("google-drive.enabled", false);
        lines.add("&eStorage: &f" + (googleDriveEnabled ? "Google Drive (streaming, no local zip)" : "Local folder"));
        if (googleDriveEnabled) {
            lines.add("&eGoogle Drive folder ID: &f" + getConfig().getString("google-drive.folder-id", ""));
        } else {
            lines.add("&eBackup folder: &f" + getConfig().getString("backup-folder", "backups"));
        }
        if (intervalMinutes > 0) {
            long nextBackupAt = stateConfig.getLong("next-backup-at-millis", 0L);
            long lastBackupAt = stateConfig.getLong("last-scheduled-backup-at-millis", 0L);
            lines.add("&eNext scheduled backup: &f" + (nextBackupAt > 0L ? formatConsoleTime(nextBackupAt) : "not scheduled yet"));
            lines.add("&eLast scheduled backup: &f" + (lastBackupAt > 0L ? formatConsoleTime(lastBackupAt) : "none yet"));
        }
        String retentionPrefix = googleDriveEnabled ? "google-drive." : "";
        lines.add("&eRetention: &fmax-backups=" + getConfig().getInt(retentionPrefix + "max-backups", 10)
                + ", minimum-backups-to-keep=" + Math.max(1, getConfig().getInt(retentionPrefix + "minimum-backups-to-keep", 1))
                + ", max-total-size-mb=" + getConfig().getLong(retentionPrefix + "max-total-size-mb", 10240));
        return lines;
    }

    private void migrateConfig() {
        try (InputStream stream = getResource(CONFIG_FILE_NAME)) {
            if (stream == null) {
                getLogger().warning("Could not find bundled " + CONFIG_FILE_NAME + " for migration.");
                return;
            }

            FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            FileConfiguration config = getConfig();
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            saveConfig();
            reloadConfig();
        } catch (IOException exception) {
            getLogger().warning("Could not migrate " + CONFIG_FILE_NAME + ": " + exception.getMessage());
        }
    }

    private void loadState() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder for state.yml.");
        }
        stateFile = new File(getDataFolder(), "state.yml");
        stateConfig = YamlConfiguration.loadConfiguration(stateFile);
    }

    private void saveState() {
        try {
            stateConfig.save(stateFile);
        } catch (IOException exception) {
            getLogger().warning("Could not save state.yml: " + exception.getMessage());
        }
    }

    private String formatConsoleTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).format(new Date(millis));
    }
}
