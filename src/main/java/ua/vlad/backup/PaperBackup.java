package ua.vlad.backup;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class PaperBackup extends JavaPlugin {

    private BackupManager backupManager;
    private BukkitTask scheduledTask;

    @Override
    public void onEnable() {
        // Save default config if not present
        saveDefaultConfig();

        // Initialize BackupManager
        this.backupManager = new BackupManager(this);

        // Register command
        if (getCommand("backup") != null) {
            getCommand("backup").setExecutor(new BackupCommand(this));
        }

        // Schedule auto backups
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
        
        scheduledTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
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
}
