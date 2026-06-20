package com.kaerna.paperbackup.storage;

import com.kaerna.paperbackup.backup.BackupResult;
import com.kaerna.paperbackup.backup.ZipStreamWriter;
import com.kaerna.paperbackup.retention.LocalRetentionService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

public class LocalBackupStorage implements BackupStorage {

    private final File backupDir;
    private final Logger logger;
    private final LocalRetentionService retentionService;

    public LocalBackupStorage(File backupDir, Logger logger, LocalRetentionService retentionService) {
        this.backupDir = backupDir;
        this.logger = logger;
        this.retentionService = retentionService;
    }

    @Override
    public BackupResult save(String fileName, ZipStreamWriter writer) throws Exception {
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            throw new IOException("Failed to create backup directory: " + backupDir.getPath());
        }

        File zipFile = new File(backupDir, fileName);
        try (FileOutputStream fileOutput = new FileOutputStream(zipFile)) {
            writer.write(fileOutput);
        } catch (Exception exception) {
            deletePartial(zipFile);
            throw exception;
        }

        long sizeBytes = zipFile.length();
        retentionService.prune();
        return BackupResult.local(fileName, sizeBytes);
    }

    private void deletePartial(File zipFile) {
        if (zipFile.exists() && !zipFile.delete()) {
            logger.warning("Could not delete failed partial backup: " + zipFile.getName());
        }
    }
}
