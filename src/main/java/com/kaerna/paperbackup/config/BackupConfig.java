package com.kaerna.paperbackup.config;

import java.io.File;
import java.util.List;

public class BackupConfig {

    public final File serverRoot;
    public final File backupDir;
    public final int maxBackups;
    public final long maxTotalSizeMb;
    public final int minimumBackupsToKeep;
    public final boolean saveWorldsBeforeBackup;
    public final boolean cleanupAfterBackup;
    public final int delayedMemoryLogSeconds;
    public final boolean googleDriveEnabled;
    public final List<String> excludePaths;

    public final int backupIntervalMinutes;
    public final long startupDelaySeconds;
    public final boolean catchUpMissedBackup;

    public final String googleDriveAuthMode;
    public final File googleDriveServiceAccountFile;
    public final String googleDriveOauthClientId;
    public final String googleDriveOauthClientSecret;
    public final String googleDriveOauthRefreshToken;
    public final String googleDriveFolderId;
    public final int googleDriveMaxBackups;
    public final long googleDriveMaxTotalSizeMb;
    public final int googleDriveMinimumBackupsToKeep;
    public final int googleDriveUploadChunkSizeKb;
    public final int googleDrivePipeBufferSizeKb;
    public final boolean googleDriveKeepClientBetweenBackups;

    public BackupConfig(
            File serverRoot, File backupDir,
            int maxBackups, long maxTotalSizeMb, int minimumBackupsToKeep,
            boolean saveWorldsBeforeBackup, boolean cleanupAfterBackup, int delayedMemoryLogSeconds,
            boolean googleDriveEnabled, List<String> excludePaths,
            int backupIntervalMinutes, long startupDelaySeconds, boolean catchUpMissedBackup,
            String googleDriveAuthMode, File googleDriveServiceAccountFile,
            String googleDriveOauthClientId, String googleDriveOauthClientSecret, String googleDriveOauthRefreshToken,
            String googleDriveFolderId, int googleDriveMaxBackups, long googleDriveMaxTotalSizeMb,
            int googleDriveMinimumBackupsToKeep, int googleDriveUploadChunkSizeKb, int googleDrivePipeBufferSizeKb,
            boolean googleDriveKeepClientBetweenBackups
    ) {
        this.serverRoot = serverRoot;
        this.backupDir = backupDir;
        this.maxBackups = maxBackups;
        this.maxTotalSizeMb = maxTotalSizeMb;
        this.minimumBackupsToKeep = minimumBackupsToKeep;
        this.saveWorldsBeforeBackup = saveWorldsBeforeBackup;
        this.cleanupAfterBackup = cleanupAfterBackup;
        this.delayedMemoryLogSeconds = delayedMemoryLogSeconds;
        this.googleDriveEnabled = googleDriveEnabled;
        this.excludePaths = excludePaths;
        this.backupIntervalMinutes = backupIntervalMinutes;
        this.startupDelaySeconds = startupDelaySeconds;
        this.catchUpMissedBackup = catchUpMissedBackup;
        this.googleDriveAuthMode = googleDriveAuthMode;
        this.googleDriveServiceAccountFile = googleDriveServiceAccountFile;
        this.googleDriveOauthClientId = googleDriveOauthClientId;
        this.googleDriveOauthClientSecret = googleDriveOauthClientSecret;
        this.googleDriveOauthRefreshToken = googleDriveOauthRefreshToken;
        this.googleDriveFolderId = googleDriveFolderId;
        this.googleDriveMaxBackups = googleDriveMaxBackups;
        this.googleDriveMaxTotalSizeMb = googleDriveMaxTotalSizeMb;
        this.googleDriveMinimumBackupsToKeep = googleDriveMinimumBackupsToKeep;
        this.googleDriveUploadChunkSizeKb = googleDriveUploadChunkSizeKb;
        this.googleDrivePipeBufferSizeKb = googleDrivePipeBufferSizeKb;
        this.googleDriveKeepClientBetweenBackups = googleDriveKeepClientBetweenBackups;
    }
}
