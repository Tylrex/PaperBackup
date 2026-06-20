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
class LocalRetentionServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    Notifier notifier;

    private File backupDir;
    private static final Logger LOG = Logger.getLogger("test");

    @BeforeEach
    void setUp() throws IOException {
        backupDir = tempDir.toFile().getCanonicalFile();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private File createBackup(String name, long sizeBytes, long lastModified) throws IOException {
        File f = new File(backupDir, name);
        Files.write(f.toPath(), new byte[(int) sizeBytes]);
        f.setLastModified(lastModified);
        return f;
    }

    private LocalRetentionService service(int maxBackups, long maxTotalSizeMb, int minKeep) {
        return new LocalRetentionService(
                backupDir,
                new RetentionPolicy(maxBackups, maxTotalSizeMb, minKeep),
                LOG, notifier);
    }

    // ── count-based pruning ────────────────────────────────────────────────────

    @Test
    void prune_byCount_deletesOldestFirst() throws IOException {
        File oldest = createBackup("backup-2024-01-01_00-00-01.zip", 10, 1_000L);
        File middle = createBackup("backup-2024-01-01_00-00-02.zip", 10, 2_000L);
        File newest = createBackup("backup-2024-01-01_00-00-03.zip", 10, 3_000L);

        service(2, -1, 1).prune();

        assertFalse(oldest.exists(), "oldest must be deleted");
        assertTrue(middle.exists());
        assertTrue(newest.exists());
    }

    @Test
    void prune_byCount_deletesMultipleWhenNecessary() throws IOException {
        for (int i = 1; i <= 5; i++) {
            createBackup("backup-2024-01-01_00-00-0" + i + ".zip", 10, i * 1_000L);
        }

        service(2, -1, 1).prune();

        assertEquals(2, backupDir.listFiles().length);
    }

    @Test
    void prune_byCount_doesNothing_whenUnderLimit() throws IOException {
        createBackup("backup-2024-01-01_00-00-01.zip", 10, 1_000L);
        createBackup("backup-2024-01-01_00-00-02.zip", 10, 2_000L);

        service(10, -1, 1).prune();

        assertEquals(2, backupDir.listFiles().length);
    }

    @Test
    void prune_byCount_disabled_whenNegative() throws IOException {
        for (int i = 1; i <= 20; i++) {
            createBackup("backup-2024-01-01_00-00-" + String.format("%02d", i) + ".zip", 10, i * 1_000L);
        }

        service(-1, -1, 1).prune();

        assertEquals(20, backupDir.listFiles().length);
    }

    // ── size-based pruning ─────────────────────────────────────────────────────

    @Test
    void prune_bySize_deletesOldestFirst() throws IOException {
        int oneMb = 1024 * 1024;
        File oldest = createBackup("backup-2024-01-01_00-00-01.zip", oneMb, 1_000L);
        File newest = createBackup("backup-2024-01-01_00-00-02.zip", oneMb, 2_000L);

        service(-1, 1, 1).prune(); // max 1 MB, have 2 MB

        assertFalse(oldest.exists(), "oldest must be deleted to fit size limit");
        assertTrue(newest.exists());
    }

    @Test
    void prune_bySize_doesNothing_whenUnderLimit() throws IOException {
        createBackup("backup-2024-01-01_00-00-01.zip", 100, 1_000L);
        createBackup("backup-2024-01-01_00-00-02.zip", 100, 2_000L);

        service(-1, 100, 1).prune();

        assertEquals(2, backupDir.listFiles().length);
    }

    // ── minimum floor ──────────────────────────────────────────────────────────

    @Test
    void prune_respectsMinimumFloor_byCount() throws IOException {
        File f1 = createBackup("backup-2024-01-01_00-00-01.zip", 10, 1_000L);
        File f2 = createBackup("backup-2024-01-01_00-00-02.zip", 10, 2_000L);

        service(1, -1, 2).prune(); // max 1, but min 2 → nothing deleted

        assertTrue(f1.exists(), "minimum floor must prevent deletion");
        assertTrue(f2.exists(), "minimum floor must prevent deletion");
    }

    @Test
    void prune_respectsMinimumFloor_bySize() throws IOException {
        int oneMb = 1024 * 1024;
        File f1 = createBackup("backup-2024-01-01_00-00-01.zip", oneMb, 1_000L);
        File f2 = createBackup("backup-2024-01-01_00-00-02.zip", oneMb, 2_000L);

        service(-1, 0, 2).prune(); // max 0 MB, but min 2 → nothing deleted

        assertTrue(f1.exists());
        assertTrue(f2.exists());
    }

    // ── pattern matching ────────────────────────────────────────────────────────

    @Test
    void prune_ignoresFilesWithWrongPattern() throws IOException {
        Files.createFile(backupDir.toPath().resolve("worlds.zip"));
        Files.createFile(backupDir.toPath().resolve("config.yml"));
        Files.createFile(backupDir.toPath().resolve("backup.zip")); // missing timestamp

        service(1, 1, 1).prune();

        assertEquals(3, backupDir.listFiles().length); // nothing matches backup-*.zip
    }

    @Test
    void prune_onlyMatchesBackupDashStar() throws IOException {
        // Two real backups (3 MB each = 6 MB total) + an unrelated zip that must not be touched
        File oldest = createBackup("backup-2024-01-01_00-00-01.zip", 1024 * 1024 * 3, 1_000L);
        File newest = createBackup("backup-2024-01-01_00-00-02.zip", 1024 * 1024 * 3, 2_000L);
        Files.createFile(backupDir.toPath().resolve("not-a-backup.zip"));

        // max 2 backups, max 1 MB → oldest deleted, newest kept (min=1 floor)
        service(1, 1, 1).prune();

        assertFalse(oldest.exists(), "oldest matching backup should be pruned by count limit");
        assertTrue(newest.exists(), "newest backup should survive");
        assertTrue(new File(backupDir, "not-a-backup.zip").exists(), "non-matching file must be left alone");
    }

    // ── empty dir ──────────────────────────────────────────────────────────────

    @Test
    void prune_emptyDir_doesNothing() {
        assertDoesNotThrow(() -> service(5, 100, 1).prune());
    }
}
