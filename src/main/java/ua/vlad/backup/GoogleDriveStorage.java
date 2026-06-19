package ua.vlad.backup;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;

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
    private static final int MIN_UPLOAD_CHUNK_SIZE = 256 * 1024;

    private final Logger logger;
    private final String authMode;
    private final java.io.File credentialsFile;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final String oauthRefreshToken;
    private final String folderId;
    private final int maxBackups;
    private final long maxTotalSizeMb;
    private final int minimumBackupsToKeep;
    private final int uploadChunkSizeBytes;
    private final int pipeBufferSizeBytes;
    private final boolean keepClientBetweenBackups;
    private Drive drive;

    public GoogleDriveStorage(
            Logger logger,
            String authMode,
            java.io.File credentialsFile,
            String oauthClientId,
            String oauthClientSecret,
            String oauthRefreshToken,
            String folderId,
            int maxBackups,
            long maxTotalSizeMb,
            int minimumBackupsToKeep,
            int uploadChunkSizeKb,
            int pipeBufferSizeKb,
            boolean keepClientBetweenBackups
    ) {
        this.logger = logger;
        this.authMode = authMode == null ? "OAUTH" : authMode.trim().toUpperCase(Locale.ROOT);
        this.credentialsFile = credentialsFile;
        this.oauthClientId = oauthClientId == null ? "" : oauthClientId.trim();
        this.oauthClientSecret = oauthClientSecret == null ? "" : oauthClientSecret.trim();
        this.oauthRefreshToken = oauthRefreshToken == null ? "" : oauthRefreshToken.trim();
        this.folderId = folderId == null ? "" : folderId.trim();
        this.maxBackups = maxBackups;
        this.maxTotalSizeMb = maxTotalSizeMb;
        this.minimumBackupsToKeep = Math.max(1, minimumBackupsToKeep);
        this.uploadChunkSizeBytes = normalizeUploadChunkSize(uploadChunkSizeKb);
        this.pipeBufferSizeBytes = Math.max(MIN_UPLOAD_CHUNK_SIZE, pipeBufferSizeKb * 1024);
        this.keepClientBetweenBackups = keepClientBetweenBackups;
    }

    public UploadResult upload(String fileName, ZipStreamWriter zipStreamWriter) throws IOException, GeneralSecurityException {
        try {
            Drive driveClient = getDrive();

            com.google.api.services.drive.model.File metadata = new com.google.api.services.drive.model.File();
            metadata.setName(fileName);
            if (!folderId.isEmpty()) {
                metadata.setParents(List.of(folderId));
            }

            AtomicReference<Exception> writerException = new AtomicReference<>();
            com.google.api.services.drive.model.File uploaded;
            try (PipedInputStream input = new PipedInputStream(pipeBufferSizeBytes);
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
                Drive.Files.Create createRequest = driveClient.files()
                        .create(metadata, mediaContent)
                        .setFields("id,name,size,webViewLink");
                MediaHttpUploader uploader = createRequest.getMediaHttpUploader();
                uploader.setDirectUploadEnabled(false);
                uploader.setChunkSize(uploadChunkSizeBytes);
                uploaded = createRequest.execute();

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
        } finally {
            if (!keepClientBetweenBackups) {
                drive = null;
            }
        }
    }

    private Drive getDrive() throws IOException, GeneralSecurityException {
        if (drive != null) {
            return drive;
        }
        GoogleCredentials credentials = createCredentials();

        drive = new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("PaperBackup")
                .build();
        return drive;
    }

    private int normalizeUploadChunkSize(int configuredKb) {
        int bytes = Math.max(256, configuredKb) * 1024;
        int remainder = bytes % MIN_UPLOAD_CHUNK_SIZE;
        if (remainder != 0) {
            bytes += MIN_UPLOAD_CHUNK_SIZE - remainder;
        }
        return bytes;
    }

    private GoogleCredentials createCredentials() throws IOException {
        if (authMode.equals("SERVICE_ACCOUNT")) {
            if (!credentialsFile.exists()) {
                throw new IOException("Google Drive service account file does not exist: " + credentialsFile.getPath());
            }

            try (FileInputStream input = new FileInputStream(credentialsFile)) {
                return GoogleCredentials.fromStream(input).createScoped(List.of(DriveScopes.DRIVE));
            }
        }

        if (!authMode.equals("OAUTH")) {
            throw new IOException("Unknown google-drive.auth-mode: " + authMode + ". Use OAUTH or SERVICE_ACCOUNT.");
        }
        if (oauthClientId.isBlank() || oauthClientSecret.isBlank() || oauthRefreshToken.isBlank()) {
            throw new IOException("Google Drive OAuth is not configured. Fill google-drive.oauth.client-id, client-secret, and refresh-token.");
        }

        return UserCredentials.newBuilder()
                .setClientId(oauthClientId)
                .setClientSecret(oauthClientSecret)
                .setRefreshToken(oauthRefreshToken)
                .build();
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
