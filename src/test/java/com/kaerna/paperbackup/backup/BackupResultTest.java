package com.kaerna.paperbackup.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackupResultTest {

    @Test
    void local_storesAllFields() {
        BackupResult r = BackupResult.local("backup-2024-01-01.zip", 1_048_576L);

        assertEquals("backup-2024-01-01.zip", r.getFileName());
        assertEquals(1_048_576L, r.getFileSizeBytes());
        assertNull(r.getDriveFileId());
        assertNull(r.getDriveWebViewLink());
        assertFalse(r.isGoogleDrive());
    }

    @Test
    void googleDrive_storesAllFields() {
        BackupResult r = BackupResult.googleDrive("backup-2024-01-01.zip", "fileId123", "https://drive.google.com/view");

        assertEquals("backup-2024-01-01.zip", r.getFileName());
        assertEquals(-1L, r.getFileSizeBytes());
        assertEquals("fileId123", r.getDriveFileId());
        assertEquals("https://drive.google.com/view", r.getDriveWebViewLink());
        assertTrue(r.isGoogleDrive());
    }

    @Test
    void local_isGoogleDrive_false() {
        assertFalse(BackupResult.local("f.zip", 0).isGoogleDrive());
    }

    @Test
    void googleDrive_isGoogleDrive_true() {
        assertTrue(BackupResult.googleDrive("f.zip", "id", null).isGoogleDrive());
    }

    @Test
    void local_zeroSize_allowed() {
        BackupResult r = BackupResult.local("empty.zip", 0L);
        assertEquals(0L, r.getFileSizeBytes());
    }
}
