package com.kaerna.paperbackup;

import com.kaerna.paperbackup.backup.BackupNotifier;
import com.kaerna.paperbackup.backup.BackupService;
import com.kaerna.paperbackup.backup.MemoryReporter;
import com.kaerna.paperbackup.backup.zip.ExclusionMatcher;
import com.kaerna.paperbackup.backup.zip.ZipBackupWriter;
import com.kaerna.paperbackup.command.BackupCommand;
import com.kaerna.paperbackup.config.BackupConfig;
import com.kaerna.paperbackup.config.ConfigService;
import com.kaerna.paperbackup.config.StateService;
import com.kaerna.paperbackup.retention.GoogleDriveRetentionService;
import com.kaerna.paperbackup.retention.LocalRetentionService;
import com.kaerna.paperbackup.retention.RetentionPolicy;
import com.kaerna.paperbackup.scheduler.BackupScheduler;
import com.kaerna.paperbackup.storage.BackupStorage;
import com.kaerna.paperbackup.storage.LocalBackupStorage;
import com.kaerna.paperbackup.storage.google.GoogleDriveClientFactory;
import com.kaerna.paperbackup.storage.google.GoogleDriveStorage;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class PaperBackup extends JavaPlugin {

    private ConfigService configService;
    private StateService stateService;
    private BackupService backupService;
    private BackupScheduler scheduler;

    @Override
    public void onEnable() {
        configService = new ConfigService(this);
        configService.initialize();

        stateService = new StateService(this);
        stateService.load();

        BackupConfig config = configService.buildConfig();
        backupService = buildBackupService(config);

        scheduler = new BackupScheduler(this, stateService);

        if (getCommand("backup") != null) {
            BackupCommand cmd = new BackupCommand(this);
            getCommand("backup").setExecutor(cmd);
            getCommand("backup").setTabCompleter(cmd);
        }

        scheduler.start(config, backupService);
        getLogger().info("PaperBackup has been enabled!");
    }

    @Override
    public void onDisable() {
        if (scheduler != null) {
            scheduler.stop();
        }
        getLogger().info("PaperBackup has been disabled!");
    }

    public boolean reloadPlugin() {
        if (backupService.isRunning()) {
            getLogger().warning("Cannot reload while a backup is in progress. Try again after the backup completes.");
            return false;
        }
        scheduler.stop();
        configService.reload();
        stateService.load();

        BackupConfig config = configService.buildConfig();
        BackupNotifier notifier = new BackupNotifier(this);
        MemoryReporter memoryReporter = new MemoryReporter(this, getLogger(),
                config.cleanupAfterBackup, config.delayedMemoryLogSeconds);
        ExclusionMatcher exclusionMatcher = new ExclusionMatcher(
                config.serverRoot, config.backupDir, config.excludePaths, getLogger());
        ZipBackupWriter zipWriter = new ZipBackupWriter(exclusionMatcher, config.serverRoot, getLogger());
        BackupStorage storage = buildStorage(config, notifier);

        backupService.reload(config, storage, zipWriter, notifier, memoryReporter);
        scheduler.start(config, backupService);
        return true;
    }

    public BackupService getBackupService() {
        return backupService;
    }

    public List<String> getStatusLines() {
        BackupConfig config = configService.buildConfig();
        List<String> lines = new ArrayList<>();
        lines.add("&6=== PaperBackup Status ===");
        lines.add("&eRunning: &f" + backupService.isRunning());
        lines.add("&eAutomatic interval: &f" + (config.backupIntervalMinutes <= 0
                ? "disabled" : config.backupIntervalMinutes + " minutes"));
        lines.add("&eStorage: &f" + (config.googleDriveEnabled
                ? "Google Drive (streaming, no local zip)" : "Local folder"));
        if (config.googleDriveEnabled) {
            lines.add("&eGoogle Drive folder ID: &f" + config.googleDriveFolderId);
        } else {
            lines.add("&eBackup folder: &f" + config.backupDir.getPath());
        }
        if (config.backupIntervalMinutes > 0) {
            long nextAt = stateService.getNextBackupAtMillis();
            long lastAt = stateService.getLastScheduledBackupAtMillis();
            lines.add("&eNext scheduled backup: &f" + (nextAt > 0L
                    ? StateService.formatTime(nextAt) : "not scheduled yet"));
            lines.add("&eLast scheduled backup: &f" + (lastAt > 0L
                    ? StateService.formatTime(lastAt) : "none yet"));
        }
        String prefix = config.googleDriveEnabled ? "google-drive." : "";
        int maxBackups = config.googleDriveEnabled ? config.googleDriveMaxBackups : config.maxBackups;
        int minKeep = config.googleDriveEnabled ? config.googleDriveMinimumBackupsToKeep : config.minimumBackupsToKeep;
        long maxSize = config.googleDriveEnabled ? config.googleDriveMaxTotalSizeMb : config.maxTotalSizeMb;
        lines.add("&eRetention: &fmax-backups=" + maxBackups
                + ", minimum-backups-to-keep=" + minKeep
                + ", max-total-size-mb=" + maxSize);
        return lines;
    }

    private BackupService buildBackupService(BackupConfig config) {
        BackupNotifier notifier = new BackupNotifier(this);
        MemoryReporter memoryReporter = new MemoryReporter(this, getLogger(),
                config.cleanupAfterBackup, config.delayedMemoryLogSeconds);
        ExclusionMatcher exclusionMatcher = new ExclusionMatcher(
                config.serverRoot, config.backupDir, config.excludePaths, getLogger());
        ZipBackupWriter zipWriter = new ZipBackupWriter(exclusionMatcher, config.serverRoot, getLogger());
        BackupStorage storage = buildStorage(config, notifier);

        return new BackupService(this, getLogger(), config, storage, zipWriter, notifier, memoryReporter);
    }

    private BackupStorage buildStorage(BackupConfig config, BackupNotifier notifier) {
        if (config.googleDriveEnabled) {
            GoogleDriveClientFactory clientFactory = new GoogleDriveClientFactory(
                    config.googleDriveAuthMode,
                    config.googleDriveServiceAccountFile,
                    config.googleDriveOauthClientId,
                    config.googleDriveOauthClientSecret,
                    config.googleDriveOauthRefreshToken
            );
            RetentionPolicy drivePolicy = new RetentionPolicy(
                    config.googleDriveMaxBackups,
                    config.googleDriveMaxTotalSizeMb,
                    config.googleDriveMinimumBackupsToKeep
            );
            GoogleDriveRetentionService driveRetention = new GoogleDriveRetentionService(
                    config.googleDriveFolderId, drivePolicy, getLogger());
            return new GoogleDriveStorage(
                    getLogger(), clientFactory,
                    config.googleDriveFolderId,
                    config.googleDriveUploadChunkSizeKb,
                    config.googleDrivePipeBufferSizeKb,
                    config.googleDriveKeepClientBetweenBackups,
                    driveRetention
            );
        }

        RetentionPolicy localPolicy = new RetentionPolicy(
                config.maxBackups, config.maxTotalSizeMb, config.minimumBackupsToKeep);
        LocalRetentionService localRetention = new LocalRetentionService(
                config.backupDir, localPolicy, getLogger(), notifier);
        return new LocalBackupStorage(config.backupDir, getLogger(), localRetention);
    }
}
