package ua.vlad.backup;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public class GoogleDriveStorage {

    private static final String ZIP_MIME_TYPE = "application/zip";

    private final Logger logger;
    private final java.io.File credentialsFile;
    private final String folderId;
    private final int maxBackups;
    private final long maxTotalSizeMb;
    private final int minimumBackupsToKeep;
    private Drive drive;

    public GoogleDriveStorage(
            Logger logger,
            java.io.File credentialsFile,
            String folderId,
            int maxBackups,
            long maxTotalSizeMb,
            int minimumBackupsToKeep
    ) {
        this.logger = logger;
        this.credentialsFile = credentialsFile;
        this.folderId = folderId == null ? "" : folderId.trim();
        this.maxBackups = maxBackups;
        this.maxTotalSizeMb = maxTotalSizeMb;
        this.minimumBackupsToKeep = Math.max(1, minimumBackupsToKeep);
    }

    public UploadResult upload(String fileName, ZipStreamWriter zipStreamWriter) throws IOException, GeneralSecurityException {
        Drive driveClient = getDrive();

        com.google.api.services.drive.model.File metadata = new com.google.api.services.drive.model.File();
        metadata.setName(fileName);
        if (!folderId.isEmpty()) {
            metadata.setParents(List.of(folderId));
        }

        AtomicReference<Exception> writerException = new AtomicReference<>();
        com.google.api.services.drive.model.File uploaded;
        try (PipedInputStream input = new PipedInputStream(1024 * 1024);
             PipedOutputStream output = new PipedOutputStream(input)) {

            Thread writerThread = new Thread(() -> {
                try (OutputStream closeableOutput = output) {
                    zipStreamWriter.write(closeableOutput);
                } catch (Exception exception) {
                    writerException.set(exception);
                }
            }, "PaperBackup-GoogleDrive-ZipWriter");
            writerThread.setDaemon(true);
            writerThread.start();

            InputStreamContent mediaContent = new InputStreamContent(ZIP_MIME_TYPE, input);
            mediaContent.setLength(-1L);
            uploaded = driveClient.files()
                    .create(metadata, mediaContent)
                    .setFields("id,name,size,webViewLink")
                    .execute();

            try {
                writerThread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for ZIP stream writer.", exception);
            }
        }

        Exception exception = writerException.get();
        if (exception != null) {
            deletePartialUpload(driveClient, uploaded);
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException("Failed to create ZIP stream.", exception);
        }

        pruneOldBackups(driveClient);

        return new UploadResult(uploaded.getId(), uploaded.getName(), uploaded.getWebViewLink());
    }

    private Drive getDrive() throws IOException, GeneralSecurityException {
        if (drive != null) {
            return drive;
        }
        if (!credentialsFile.exists()) {
            throw new IOException("Google Drive service account file does not exist: " + credentialsFile.getPath());
        }

        GoogleCredentials credentials;
        try (FileInputStream input = new FileInputStream(credentialsFile)) {
            credentials = GoogleCredentials.fromStream(input).createScoped(List.of(DriveScopes.DRIVE));
        }

        drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("PaperBackup")
                .build();
        return drive;
    }

    private void pruneOldBackups(Drive driveClient) throws IOException {
        List<com.google.api.services.drive.model.File> backups = listBackupFiles(driveClient);
        if (backups.isEmpty()) {
            return;
        }

        backups.sort(Comparator.comparing(file -> {
            if (file.getCreatedTime() == null) {
                return 0L;
            }
            return file.getCreatedTime().getValue();
        }));

        logger.info(String.format(Locale.ROOT,
                "Google Drive cleanup check: files=%d, total=%.2f MB, max-backups=%d, minimum-backups-to-keep=%d, max-total-size-mb=%d",
                backups.size(), calculateTotalSize(backups) / (1024.0 * 1024.0),
                maxBackups, minimumBackupsToKeep, maxTotalSizeMb));

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
        if (maxTotalSizeMb <= 0) {
            return;
        }

        long maxTotalSizeBytes = maxTotalSizeMb * 1024L * 1024L;
        long currentTotalSize = calculateTotalSize(backups);
        while (currentTotalSize > maxTotalSizeBytes && backups.size() > minimumBackupsToKeep) {
            com.google.api.services.drive.model.File oldest = backups.remove(0);
            long fileSize = oldest.getSize() == null ? 0L : oldest.getSize();
            deleteDriveFile(driveClient, oldest, "size limit");
            currentTotalSize -= fileSize;
        }
    }

    private void pruneByCount(Drive driveClient, List<com.google.api.services.drive.model.File> backups) throws IOException {
        if (maxBackups <= 0) {
            return;
        }

        while (backups.size() > maxBackups) {
            if (backups.size() <= minimumBackupsToKeep) {
                return;
            }
            com.google.api.services.drive.model.File oldest = backups.remove(0);
            deleteDriveFile(driveClient, oldest, "count limit");
        }
    }

    private void deleteDriveFile(Drive driveClient, com.google.api.services.drive.model.File file, String reason) throws IOException {
        driveClient.files().delete(file.getId()).execute();
        logger.info("Deleted Google Drive backup due to " + reason + ": " + file.getName());
    }

    private void deletePartialUpload(Drive driveClient, com.google.api.services.drive.model.File uploaded) {
        if (uploaded == null || uploaded.getId() == null) {
            return;
        }

        try {
            driveClient.files().delete(uploaded.getId()).execute();
            logger.warning("Deleted incomplete Google Drive backup upload: " + uploaded.getName());
        } catch (IOException cleanupException) {
            logger.warning("Could not delete incomplete Google Drive backup upload "
                    + uploaded.getName() + ": " + cleanupException.getMessage());
        }
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

    public static class UploadResult {
        private final String fileId;
        private final String fileName;
        private final String webViewLink;

        public UploadResult(String fileId, String fileName, String webViewLink) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.webViewLink = webViewLink;
        }

        public String fileId() {
            return fileId;
        }

        public String fileName() {
            return fileName;
        }

        public String webViewLink() {
            return webViewLink;
        }
    }

    @FunctionalInterface
    public interface ZipStreamWriter {
        void write(OutputStream outputStream) throws Exception;
    }
}
