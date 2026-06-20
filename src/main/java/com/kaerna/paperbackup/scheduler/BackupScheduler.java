package com.kaerna.paperbackup.scheduler;

import com.kaerna.paperbackup.backup.BackupService;
import com.kaerna.paperbackup.config.BackupConfig;
import com.kaerna.paperbackup.config.StateService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class BackupScheduler {

    private final Plugin plugin;
    private final StateService stateService;
    private BukkitTask task;

    public BackupScheduler(Plugin plugin, StateService stateService) {
        this.plugin = plugin;
        this.stateService = stateService;
    }

    public void start(BackupConfig config, BackupService backupService) {
        if (config.backupIntervalMinutes <= 0) {
            plugin.getLogger().info("Automatic backups are disabled (interval is <= 0).");
            return;
        }

        long intervalMillis = config.backupIntervalMinutes * 60_000L;
        long now = System.currentTimeMillis();
        long nextRunAt = stateService.getNextBackupAtMillis();

        if (nextRunAt <= 0L) {
            long lastSuccessAt = stateService.getLastScheduledBackupAtMillis();
            nextRunAt = lastSuccessAt > 0L ? lastSuccessAt + intervalMillis : now + intervalMillis;
            stateService.setNextBackupAtMillis(nextRunAt);
        }

        if (nextRunAt <= now && !config.catchUpMissedBackup) {
            nextRunAt = advancePastNow(nextRunAt, intervalMillis, now);
            stateService.setNextBackupAtMillis(nextRunAt);
        }

        long startupDelayMillis = config.startupDelaySeconds * 1000L;
        long delayMillis = Math.max(0L, nextRunAt - now);
        if (nextRunAt <= now && config.catchUpMissedBackup) {
            delayMillis = startupDelayMillis;
        }

        long delayTicks = Math.max(1L, (delayMillis + 49L) / 50L);
        long scheduledFor = nextRunAt;

        task = Bukkit.getScheduler().runTaskLater(plugin,
                () -> runScheduledBackup(config, backupService, scheduledFor, intervalMillis),
                delayTicks);

        plugin.getLogger().info("Scheduled automatic backups every " + config.backupIntervalMinutes
                + " minutes. Next backup: " + StateService.formatTime(nextRunAt) + ".");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void runScheduledBackup(BackupConfig config, BackupService backupService,
                                    long scheduledFor, long intervalMillis) {
        if (backupService.isRunning()) {
            long now = System.currentTimeMillis();
            long nextRunAt = advancePastNow(scheduledFor + intervalMillis, intervalMillis, now);
            plugin.getLogger().info("Scheduled backup was due at " + StateService.formatTime(scheduledFor)
                    + ", but another backup is already running. Next backup: " + StateService.formatTime(nextRunAt) + ".");
            stateService.setNextBackupAtMillis(nextRunAt);
            stop();
            start(config, backupService);
            return;
        }

        plugin.getLogger().info("Starting scheduled server backup. Scheduled time: " + StateService.formatTime(scheduledFor) + ".");
        backupService.runBackup(false, success -> Bukkit.getScheduler().runTask(plugin, () -> {
            long now = System.currentTimeMillis();
            if (success) {
                stateService.setLastScheduledBackupAtMillis(now);
            }
            long nextRunAt = advancePastNow(scheduledFor + intervalMillis, intervalMillis, now);
            stateService.setNextBackupAtMillis(nextRunAt);
            stop();
            start(config, backupService);
        }));
    }

    private long advancePastNow(long candidate, long intervalMillis, long now) {
        long next = candidate;
        while (next <= now) {
            next += intervalMillis;
        }
        return next;
    }
}
