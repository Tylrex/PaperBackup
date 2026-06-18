package ua.vlad.backup;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class PaperBackup extends JavaPlugin {

    private BackupManager backupManager;
    private BukkitTask scheduledTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();

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

        long ticks = intervalMinutes * 60L * 20L;
        
        scheduledTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            getLogger().info("Starting scheduled server backup...");
            backupManager.runBackup(false);
        }, ticks, ticks);

        getLogger().info("Scheduled automatic backup every " + intervalMinutes + " minutes.");
    }

    private void cancelBackupTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    private void migrateConfig() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                getLogger().warning("Could not find bundled config.yml for migration.");
                return;
            }

            FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            FileConfiguration config = getConfig();
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            saveConfig();
            reloadConfig();
        } catch (IOException exception) {
            getLogger().warning("Could not migrate config.yml: " + exception.getMessage());
        }
    }
}
