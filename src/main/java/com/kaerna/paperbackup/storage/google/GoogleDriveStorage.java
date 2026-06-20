package com.kaerna.paperbackup.storage.google;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.kaerna.paperbackup.backup.BackupResult;
import com.kaerna.paperbackup.backup.ZipStreamWriter;
import com.kaerna.paperbackup.retention.GoogleDriveRetentionService;
import com.kaerna.paperbackup.storage.BackupStorage;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public class GoogleDriveStorage implements BackupStorage {

    private static final String ZIP_MIME_TYPE = "application/zip";
    private static final int MIN_CHUNK_SIZE = 256 * 1024;
    private static final long WRITER_JOIN_TIMEOUT_MS = 30_000L;

    private final Logger logger;
    private final GoogleDriveClientFactory clientFactory;
    private final String folderId;
    private final int uploadChunkSizeBytes;
    private final int pipeBufferSizeBytes;
    private final boolean keepClientBetweenBackups;
    private final GoogleDriveRetentionService retentionService;
    private Drive drive;

    public GoogleDriveStorage(
            Logger logger,
            GoogleDriveClientFactory clientFactory,
            String folderId,
            int uploadChunkSizeKb,
            int pipeBufferSizeKb,
            boolean keepClientBetweenBackups,
            GoogleDriveRetentionService retentionService
    ) {
        this.logger = logger;
        this.clientFactory = clientFactory;
        this.folderId = folderId == null ? "" : folderId.trim();
        this.uploadChunkSizeBytes = normalizeChunkSize(uploadChunkSizeKb);
        this.pipeBufferSizeBytes = Math.max(MIN_CHUNK_SIZE, pipeBufferSizeKb * 1024);
        this.keepClientBetweenBackups = keepClientBetweenBackups;
        this.retentionService = retentionService;
    }

    @Override
    public BackupResult save(String fileName, ZipStreamWriter writer) throws Exception {
        try {
            Drive driveClient = getDrive();

            com.google.api.services.drive.model.File metadata = new com.google.api.services.drive.model.File();
            metadata.setName(fileName);
            if (!folderId.isEmpty()) {
                metadata.setParents(List.of(folderId));
            }

            AtomicReference<Exception> writerException = new AtomicReference<>();
            com.google.api.services.drive.model.File uploaded;

            // Declared outside so it remains reachable in the fail-safe join after the TWR block.
            Thread writerThread = null;

            try (PipedInputStream input = new PipedInputStream(pipeBufferSizeBytes);
                 PipedOutputStream output = new PipedOutputStream(input)) {

                writerThread = new Thread(() -> {
                    try (OutputStream closeableOutput = output) {
                        writer.write(closeableOutput);
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

                // Happy path: all data uploaded. Join here while pipe is still technically open
                // (writerThread already closed output after writing, so join returns instantly).
                try {
                    writerThread.join();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for ZIP stream writer.", exception);
                }
                writerThread = null; // mark as joined — skip fail-safe below
            }

            // Fail-safe: execute() threw before we could join. The TWR has now closed both pipe
            // ends, so the writer thread will observe a broken pipe and terminate on its own.
            // We wait for it to confirm there are no lingering threads.
            if (writerThread != null) {
                try {
                    writerThread.join(WRITER_JOIN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Exception writerEx = writerException.get();
            if (writerEx != null) {
                deletePartialUpload(driveClient, uploaded);
                if (writerEx instanceof IOException) {
                    throw (IOException) writerEx;
                }
                throw new IOException("Failed to create ZIP stream.", writerEx);
            }

            retentionService.prune(driveClient);

            return BackupResult.googleDrive(uploaded.getName(), uploaded.getId(), uploaded.getWebViewLink());
        } catch (IOException | GeneralSecurityException exception) {
            throw new IOException(enhanceErrorMessage(exception), exception);
        } finally {
            if (!keepClientBetweenBackups) {
                drive = null;
            }
        }
    }

    private Drive getDrive() throws IOException, GeneralSecurityException {
        if (drive == null) {
            drive = clientFactory.create();
        }
        return drive;
    }

    private int normalizeChunkSize(int configuredKb) {
        int bytes = Math.max(256, configuredKb) * 1024;
        int remainder = bytes % MIN_CHUNK_SIZE;
        if (remainder != 0) {
            bytes += MIN_CHUNK_SIZE - remainder;
        }
        return bytes;
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

    private String enhanceErrorMessage(Exception exception) {
        String message = exception.getMessage();
        if (message != null && message.contains("Service Accounts do not have storage quota")) {
            return "Service account cannot upload to a normal personal Google Drive because it has no storage quota. "
                    + "Use google-drive.auth-mode: OAUTH, or use SERVICE_ACCOUNT only with a Google Workspace Shared Drive.";
        }
        return message == null ? exception.getClass().getSimpleName() : message;
    }
}
