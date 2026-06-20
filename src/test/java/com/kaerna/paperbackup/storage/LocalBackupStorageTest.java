package com.kaerna.paperbackup.storage;

import com.kaerna.paperbackup.backup.BackupResult;
import com.kaerna.paperbackup.backup.ZipStreamWriter;
import com.kaerna.paperbackup.retention.LocalRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalBackupStorageTest {

    @TempDir
    Path tempDir;

    @Mock
    LocalRetentionService retentionService;

    private LocalBackupStorage storage;
    private File backupDir;

    @BeforeEach
    void setUp() throws IOException {
        backupDir = new File(tempDir.toFile(), "backups");
        Files.createDirectories(backupDir.toPath());
        storage = new LocalBackupStorage(backupDir, Logger.getLogger("test"), retentionService);
    }

    @Test
    void save_createsFileOnDisk() throws Exception {
        storage.save("backup-test.zip", out -> out.write(new byte[]{1, 2, 3}));

        assertTrue(new File(backupDir, "backup-test.zip").exists());
    }

    @Test
    void save_returnsLocalResult_withCorrectName() throws Exception {
        BackupResult result = storage.save("backup-test.zip", out -> out.write(new byte[]{1}));

        assertFalse(result.isGoogleDrive());
        assertEquals("backup-test.zip", result.getFileName());
    }

    @Test
    void save_returnsCorrectFileSize() throws Exception {
        byte[] payload = new byte[512];
        BackupResult result = storage.save("backup-test.zip", out -> out.write(payload));

        assertEquals(512L, result.getFileSizeBytes());
    }

    @Test
    void save_callsRetentionAfterSuccess() throws Exception {
        storage.save("backup-test.zip", out -> out.write(new byte[]{1}));

        verify(retentionService, times(1)).prune();
    }

    @Test
    void save_deletesPartialFile_onWriterFailure() {
        ZipStreamWriter failing = out -> {
            out.write(new byte[]{1, 2, 3});
            throw new IOException("Disk full");
        };

        assertThrows(IOException.class, () -> storage.save("bad.zip", failing));
        assertFalse(new File(backupDir, "bad.zip").exists(), "partial file must be deleted");
    }

    @Test
    void save_doesNotCallRetention_onFailure() {
        assertThrows(Exception.class,
                () -> storage.save("bad.zip", out -> { throw new IOException("fail"); }));

        verify(retentionService, never()).prune();
    }

    @Test
    void save_createsMissingBackupDir() throws Exception {
        File nested = new File(tempDir.toFile(), "a/b/c");
        LocalBackupStorage nestedStorage = new LocalBackupStorage(
                nested, Logger.getLogger("test"), retentionService);

        nestedStorage.save("test.zip", out -> out.write(new byte[]{1}));

        assertTrue(nested.exists(), "backup dir should be created automatically");
        assertTrue(new File(nested, "test.zip").exists());
    }
}
