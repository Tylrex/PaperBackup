package com.kaerna.paperbackup.retention;

import com.kaerna.paperbackup.backup.Notifier;
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

@ExtendWith(MockitoExtension.class)
class LocalRetentionServiceEdgeCaseTest {

    @TempDir Path tempDir;
    @Mock Notifier notifier;

    private File backupDir;
    private static final Logger LOG = Logger.getLogger("test");

    @BeforeEach
    void setUp() throws IOException {
        backupDir = tempDir.toFile().getCanonicalFile();
    }

    private File createBackup(String name, long sizeBytes, long lastModified) throws IOException {
        File f = new File(backupDir, name);
        Files.write(f.toPath(), new byte[(int) sizeBytes]);
        f.setLastModified(lastModified);
        return f;
    }

    private LocalRetentionService service(int maxBackups, long maxSizeMb, int minKeep) {
        return new LocalRetentionService(
                backupDir, new RetentionPolicy(maxBackups, maxSizeMb, minKeep), LOG, notifier);
    }

    // ── both count and size limits active simultaneously ───────────────────────

    @Test
    void prune_bothLimits_mostRestrictiveWins() throws IOException {
        int mb = 1024 * 1024;
        createBackup("backup-2024-01-01_00-00-01.zip", mb, 1_000L);
        createBackup("backup-2024-01-01_00-00-02.zip", mb, 2_000L);
        createBackup("backup-2024-01-01_00-00-03.zip", mb, 3_000L);

        // max 2 by count AND max 1 MB by size; size limit is more restrictive
        service(2, 1, 1).prune();

        // Size prune: 3 MB > 1 MB, need to remove 2, but min is 1 → remove 2 oldest
        // Count prune: 1 file ≤ 2 max → no more removals
        assertEquals(1, backupDir.listFiles().length, "size limit should leave only 1 file");
    }

    // ── files with identical lastModified: order is stable ────────────────────

    @Test
    void prune_sameTimestamp_stillPrunesExpectedCount() throws IOException {
        long ts = 1_000L;
        for (int i = 1; i <= 5; i++) {
            createBackup("backup-2024-01-01_00-00-0" + i + ".zip", 10, ts);
        }

        service(2, -1, 1).prune();

        assertEquals(2, backupDir.listFiles().length, "should keep exactly 2 files");
    }

    // ── zero-byte backup files do not count toward size limit ─────────────────

    @Test
    void prune_zeroSizeFiles_doNotTriggerSizePrune() throws IOException {
        for (int i = 1; i <= 10; i++) {
            createBackup("backup-2024-01-01_00-00-0" + i + ".zip", 0, i * 1_000L);
        }

        service(-1, 1, 1).prune(); // max 1 MB, but all files are 0 bytes

        assertEquals(10, backupDir.listFiles().length, "no files should be pruned when total size is 0");
    }

    // ── single file at minimum floor ──────────────────────────────────────────

    @Test
    void prune_singleFile_neverDeleted_dueToMinFloor() throws IOException {
        File f = createBackup("backup-2024-01-01_00-00-01.zip", 1024 * 1024 * 100, 1_000L);

        service(1, 1, 1).prune(); // over every limit, but min=1 means keep it

        assertTrue(f.exists(), "only file must survive minimum floor");
    }

    // ── mixed matching and non-matching files ─────────────────────────────────

    @Test
    void prune_mixedFiles_onlyMatchingCountedAndDeleted() throws IOException {
        File backup1 = createBackup("backup-2024-01-01_00-00-01.zip", 100, 1_000L);
        File backup2 = createBackup("backup-2024-01-01_00-00-02.zip", 100, 2_000L);
        File backup3 = createBackup("backup-2024-01-01_00-00-03.zip", 100, 3_000L);

        // Non-matching files that must never be touched
        File configFile = new File(backupDir, "config.yml");
        File notABackup = new File(backupDir, "worlds.zip");
        Files.createFile(configFile.toPath());
        Files.createFile(notABackup.toPath());

        service(1, -1, 1).prune(); // max 1 real backup

        // 2 oldest backups pruned, newest + 2 non-matching files remain
        assertTrue(backup3.exists(),   "newest backup survives");
        assertFalse(backup1.exists(),  "oldest deleted");
        assertFalse(backup2.exists(),  "middle deleted");
        assertTrue(configFile.exists(), "config.yml must not be touched");
        assertTrue(notABackup.exists(), "worlds.zip must not be touched");
    }

    // ── missing backup dir: prune is a no-op ──────────────────────────────────

    @Test
    void prune_missingBackupDir_doesNotThrow() {
        File nonExistentDir = new File(backupDir, "does-not-exist");
        LocalRetentionService svc = new LocalRetentionService(
                nonExistentDir, new RetentionPolicy(5, 100, 1), LOG, notifier);
        assertDoesNotThrow(svc::prune);
    }

    // ── no-op when both limits disabled and no minKeep pressure ───────────────

    @Test
    void prune_bothLimitsDisabled_nothingDeleted() throws IOException {
        for (int i = 1; i <= 50; i++) {
            createBackup("backup-2024-01-01_00-00-" + String.format("%02d", i) + ".zip", 1024 * 1024, i * 1_000L);
        }

        service(-1, -1, 1).prune(); // no limits

        assertEquals(50, backupDir.listFiles().length);
    }
}
