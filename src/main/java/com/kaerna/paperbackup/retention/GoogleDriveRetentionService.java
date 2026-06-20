package com.kaerna.paperbackup.retention;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

public class GoogleDriveRetentionService {

    private static final String ZIP_MIME_TYPE = "application/zip";

    private final String folderId;
    private final RetentionPolicy policy;
    private final Logger logger;

    public GoogleDriveRetentionService(String folderId, RetentionPolicy policy, Logger logger) {
        this.folderId = folderId == null ? "" : folderId.trim();
        this.policy = policy;
        this.logger = logger;
    }

    public void prune(Drive driveClient) throws IOException {
        List<com.google.api.services.drive.model.File> backups = listBackupFiles(driveClient);
        if (backups.isEmpty()) {
            return;
        }

        backups.sort(Comparator.comparingLong(file -> {
            if (file.getCreatedTime() == null) {
                return Long.MAX_VALUE;
            }
            return file.getCreatedTime().getValue();
        }));

        logger.info(String.format(Locale.ROOT,
                "Google Drive cleanup check: files=%d, total=%.2f MB, max-backups=%d, minimum-backups-to-keep=%d, max-total-size-mb=%d",
                backups.size(), calculateTotalSize(backups) / (1024.0 * 1024.0),
                policy.maxBackups, policy.minimumBackupsToKeep, policy.maxTotalSizeMb));

        pruneByTotalSize(driveClient, backups);
        pruneByCount(driveClient, backups);
    }

    private List<com.google.api.services.drive.model.File> listBackupFiles(Drive driveClient) throws IOException {
        List<com.google.api.services.drive.model.File> result = new ArrayList<>();
        String pageToken = null;
        String query = "trashed = false and mimeType = '" + ZIP_MIME_TYPE + "' and name contains 'backup-'";
        if (!folderId.isEmpty()) {
            query += " and '" + folderId.replace("'", "\\'") + "' in parents";
        }

        do {
            FileList fileList = driveClient.files()
                    .list()
                    .setQ(query)
                    .setSpaces("drive")
                    .setFields("nextPageToken, files(id,name,size,createdTime)")
                    .setPageToken(pageToken)
                    .execute();
            if (fileList.getFiles() != null) {
                for (com.google.api.services.drive.model.File file : fileList.getFiles()) {
                    String name = Objects.toString(file.getName(), "").toLowerCase(Locale.ROOT);
                    if (name.startsWith("backup-") && name.endsWith(".zip")) {
                        result.add(file);
                    }
                }
            }
            pageToken = fileList.getNextPageToken();
        } while (pageToken != null && !pageToken.isBlank());

        return result;
    }

    private void pruneByTotalSize(Drive driveClient, List<com.google.api.services.drive.model.File> backups) throws IOException {
        if (policy.maxTotalSizeMb <= 0) {
            return;
        }
        long maxBytes = policy.maxTotalSizeMb * 1024L * 1024L;
        long currentSize = calculateTotalSize(backups);
        while (currentSize > maxBytes && backups.size() > policy.minimumBackupsToKeep) {
            com.google.api.services.drive.model.File oldest = backups.remove(0);
            long fileSize = oldest.getSize() == null ? 0L : oldest.getSize();
            deleteFile(driveClient, oldest, "size limit");
            currentSize -= fileSize;
        }
    }

    private void pruneByCount(Drive driveClient, List<com.google.api.services.drive.model.File> backups) throws IOException {
        if (policy.maxBackups <= 0) {
            return;
        }
        while (backups.size() > policy.maxBackups) {
            if (backups.size() <= policy.minimumBackupsToKeep) {
                return;
            }
            com.google.api.services.drive.model.File oldest = backups.remove(0);
            deleteFile(driveClient, oldest, "count limit");
        }
    }

    private void deleteFile(Drive driveClient, com.google.api.services.drive.model.File file, String reason) throws IOException {
        driveClient.files().delete(file.getId()).execute();
        logger.info("Deleted Google Drive backup due to " + reason + ": " + file.getName());
    }

    private long calculateTotalSize(List<com.google.api.services.drive.model.File> files) {
        long total = 0L;
        for (com.google.api.services.drive.model.File file : files) {
            if (file.getSize() != null) {
                total += file.getSize();
            }
        }
        return total;
    }
}
