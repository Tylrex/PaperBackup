package com.kaerna.paperbackup.retention;

import com.kaerna.paperbackup.backup.Notifier;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public class LocalRetentionService {

    private final File backupDir;
    private final RetentionPolicy policy;
    private final Logger logger;
    private final Notifier notifier;

    public LocalRetentionService(File backupDir, RetentionPolicy policy, Logger logger, Notifier notifier) {
        this.backupDir = backupDir;
        this.policy = policy;
        this.logger = logger;
        this.notifier = notifier;
    }

    public void prune() {
        File[] files = backupDir.listFiles((dir, name) -> {
            String lowerName = name.toLowerCase(Locale.ROOT);
            return lowerName.startsWith("backup-") && lowerName.endsWith(".zip");
        });
        if (files == null || files.length == 0) {
            return;
        }

        List<File> zipFiles = new ArrayList<>(List.of(files));
        zipFiles.sort(Comparator.comparingLong(File::lastModified));

        logger.info(String.format(Locale.ROOT,
                "Backup cleanup check: files=%d, total=%.2f MB, max-backups=%d, minimum-backups-to-keep=%d, max-total-size-mb=%d",
                zipFiles.size(), calculateTotalSize(zipFiles) / (1024.0 * 1024.0),
                policy.maxBackups, policy.minimumBackupsToKeep, policy.maxTotalSizeMb));

        pruneByTotalSize(zipFiles);
        pruneByCount(zipFiles);
    }

    private void pruneByTotalSize(List<File> zipFiles) {
        if (policy.maxTotalSizeMb <= 0) {
            return;
        }
        long maxBytes = policy.maxTotalSizeMb * 1024L * 1024L;
        long currentSize = calculateTotalSize(zipFiles);
        while (currentSize > maxBytes && zipFiles.size() > policy.minimumBackupsToKeep) {
            File oldest = zipFiles.remove(0);
            long fileSize = oldest.length();
            if (oldest.delete()) {
                currentSize -= fileSize;
                logger.info("Deleted oldest backup due to size limit: " + oldest.getName());
                notifier.notifyAdmins("&6[PaperBackup] Deleted oldest backup due to size limit: &e" + oldest.getName());
            } else {
                logger.warning("Failed to delete backup file: " + oldest.getName());
            }
        }
    }

    private void pruneByCount(List<File> zipFiles) {
        if (policy.maxBackups <= 0) {
            return;
        }
        while (zipFiles.size() > policy.maxBackups) {
            if (zipFiles.size() <= policy.minimumBackupsToKeep) {
                return;
            }
            File oldest = zipFiles.remove(0);
            if (oldest.delete()) {
                logger.info("Deleted oldest backup due to count limit: " + oldest.getName());
                notifier.notifyAdmins("&6[PaperBackup] Deleted oldest backup due to count limit: &e" + oldest.getName());
            } else {
                logger.warning("Failed to delete backup file: " + oldest.getName());
            }
        }
    }

    private long calculateTotalSize(List<File> files) {
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        return total;
    }
}
