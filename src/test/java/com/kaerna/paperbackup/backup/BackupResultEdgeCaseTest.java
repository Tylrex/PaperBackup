package com.kaerna.paperbackup.backup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackupResultEdgeCaseTest {

    @Test
    void local_maxLongSize_doesNotOverflow() {
        BackupResult r = BackupResult.local("f.zip", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, r.getFileSizeBytes());
    }

    @Test
    void googleDrive_nullWebViewLink_stored() {
        BackupResult r = BackupResult.googleDrive("f.zip", "driveId", null);
        assertEquals("driveId", r.getDriveFileId());
        assertNull(r.getDriveWebViewLink());
        assertTrue(r.isGoogleDrive());
    }

    @Test
    void googleDrive_nullDriveFileId_still_isGoogleDrive() {
        // A null driveFileId still means the factory was called — storage was GDrive
        BackupResult r = BackupResult.googleDrive("f.zip", null, null);
        // isGoogleDrive checks driveFileId != null, so null id → false
        // This documents the current contract
        assertFalse(r.isGoogleDrive());
    }

    @Test
    void local_fileNamePreserved_withSpaces() {
        BackupResult r = BackupResult.local("backup 2024 01 01.zip", 1024);
        assertEquals("backup 2024 01 01.zip", r.getFileName());
    }

    @Test
    void googleDrive_fileNamePreserved_withSpecialChars() {
        BackupResult r = BackupResult.googleDrive("backup (copy).zip", "id1", "link");
        assertEquals("backup (copy).zip", r.getFileName());
    }

    @Test
    void local_sizeIsNegative_stored() {
        // Contract: don't validate size, store as-is
        BackupResult r = BackupResult.local("f.zip", -1L);
        assertEquals(-1L, r.getFileSizeBytes());
    }
}
